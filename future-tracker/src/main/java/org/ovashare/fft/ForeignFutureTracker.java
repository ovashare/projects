/*
 * Copyright (c) 2013 Bill Dortch / Ovashare
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.ovashare.fft;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ForeignFutureTracker (FFT) provides a way to get a callback when an aribitrary
 * {@code java.util.concurrent.Future} completes or times out. It is intended for use with
 * external/third-party (hence "foreign") libraries that return Futures for async operations,
 * but provide no way to wait on them without blocking, such as various Memcached and Redis
 * clients, and the AWS SDK.
 * <p>
 * The FFT should NOT be used with Futures that do provide a callback mechanism, like those
 * returned by Netty 4 or the Cassandra Java driver, as those mechanisms will be more efficient
 * and straightforward to use directly.
 * <p>
 * Unlike some other solutions which tie up a thread for each pending Future, the FFT uses a single
 * thread to poll outstanding Futures at a configurable interval (default once per millisecond).
 * The caller supplies an {@code Executor} to be used for the callback; for example, the Netty 4
 * {@code ChannelHandler} Executor associated with a handler instance, returned by 
 * {@code ChannelHandlerContext.executor()}.
 * <p>
 * FFT is threadsafe, and can (and should) be shared by callers from multiple threads. A single,
 * shared (static) FFT instance consumes negligible CPU even with several thousand outstanding
 * Futures. However, no more than one or two instances should be active at the same time on a 
 * given machine, or they will begin to take a noticeable toll on CPU.
 * <p>
 * Example: Spymemcached under Netty 4:
 * <pre>
 *  // in some shared location
 *  public static final ForeignFutureTracker FFT = new ForeignFutureTracker();
 * 
 *  // ...
 *  
 *  public class MyChannelHandler extends ChannelInboundHandlerAdapter {
 *    
 *    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
 *    // ...
 *      final GetFuture&lt;Object&gt; future = memcachedClient.asyncGet("foo");
 *      
 *      FFT.addListener(future, 2, TimeUnit.SECONDS, ctx.executor, 
 *        new ForeignFutureListener&lt;Object,GetFuture&lt;Object&gt;&gt;() {
 *
 *          public void operationSuccess(Object value) {
 *            // do something
 *          }
 *          public void operationTimeout(GetFuture&lt;Object&gt; f) {
 *            // do something
 *          }
 *          public void operationFailure(Exception e) {
 *            // do something
 *          }
 *        });
 *    }
 *    // ...
 *  }
 *  
 * </pre>
 * 
 * 
 * @author Bill Dortch @ gmail
 */
public class ForeignFutureTracker extends ReentrantLock {
    private static final long serialVersionUID = 1L;
    
    public static final long DEFAULT_POLLING_INTERVAL_MILLIS  = 1L;  // one millisecond
    
    private final ScheduledExecutorService executor;
    private final long pollingInterval;
    private final TimeUnit pollingIntervalUnit;
    
    // linked list and counts are accessed only under lock, so do not need to be volatile
    @SuppressWarnings("rawtypes")
    private Entry list;
    private int count;
    private int peakCount;
    private boolean shutdown;
    
    /**
     * Create a new ForeignFutureTracker using the specified polling interval.
     * 
     * @param pollingInterval the polling interval, in the time unit specified.
     * @param pollingIntervalUnit the time unit to apply to the specified polling interval.
     */
    public ForeignFutureTracker(long pollingInterval, TimeUnit pollingIntervalUnit) {
        // using less-expensive non-fair lock, as lock durations will be very short
        super(false);
        checkNull(pollingIntervalUnit, "pollingIntervalUnit");
        if (pollingInterval <= 0) {
            throw new IllegalArgumentException("pollingInterval");
        }
        this.pollingInterval = pollingInterval;
        this.pollingIntervalUnit = pollingIntervalUnit;
        // TODO: optional user-supplied thread factory
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("ForeignFutureTracker @"+Integer.toHexString(t.hashCode()));
                t.setDaemon(true);
                return t;
            }
        });
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, pollingInterval, pollingInterval, pollingIntervalUnit);
    }
    
    /**
     * Create a new ForeignFutureTracker using the default polling interval of 1 millisecond.
     */
    public ForeignFutureTracker() {
        this(DEFAULT_POLLING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Calls the underlying Executor's shutdown() method. Any subsequent calls to
     * addListener() will be rejected with an IllegalStateException.
     */
    public void shutdown() {
        lock();
        try {
            if (!shutdown) {
                executor.shutdown();
                shutdown = true;
                list = null;
            }
        } finally {
            unlock();
        }
    }
    
    /**
     * Calls the underlying Executor's shutdownNow() method. Any subsequent calls to
     * addListener() will be rejected with an IllegalStateException.
     */
    public void shutdownNow() {
        lock();
        try {
            if (!shutdown) {
                executor.shutdownNow();
                shutdown = true;
                list = null;
            }
        } finally {
            unlock();
        }
    }
    
    public boolean isShutdown() {
        lock();
        try {
            return shutdown;
        } finally {
            unlock();
        }
    }
    
    public long getPollingInterval() {
        return pollingInterval;
    }

    public TimeUnit getPollingIntervalUnit() {
        return pollingIntervalUnit;
    }

    /**
     * Returns the number of Futures currently being tracked.
     * 
     * @return the number of Futures currently being tracked.
     */
    public int getCount() {
        lock();
        try {
            return count;
        } finally {
            unlock();
        }
    }

    /**
     * Returns the highest number of concurrently-active Futures tracked so far.
     * 
     * @return the highest number of concurrently-active Futures tracked so far.
     */
    public int getPeakCount() {
        lock();
        try {
            return peakCount;
        } finally {
            unlock();
        }
    }
    

    /**
     * Add a listener for the specified Future to this tracker, with the specified timeout.
     * Upon completion, timeout or failure, the appropriate listener method will be called 
     * on a/the thread of the specified Executor.
     * <p>
     * For Netty 4.0+ use, if the listener executor is obtained from ChannelHandlerContext.executor(),
     * the listener will be called on the thread associated with the corresponding ChannelHandler.
     * Note that this tracker should NOT be used with Netty's own io.netty.util.concurrent.Future
     * objects; use the Future.addListener() methods to add listeners directly to those.
     * <p>
     * 
     * @param future the future to be tracked.
     * @param timeout the maximum time to wait for the Future to complete, in the time unit specified.
     * @param timeUnit the time unit applied to the timeout value.
     * @param listenerExecutor the Executor to use to call listener methods.
     * @param listener the listener to be called upon completion, timeout or failure of the Future's operation.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T, F extends java.util.concurrent.Future<T>> void addListener(
            F future, long timeout, TimeUnit timeUnit,
            Executor listenerExecutor, ForeignFutureListener<T, F> listener)
    {
        checkNull(future, "future");
        checkNull(timeUnit, "timeUnit");
        checkNull(listenerExecutor, "executor");
        checkNull(listener,"listener");
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout");
        }
        long timeoutNanos = TimeUnit.NANOSECONDS.convert(timeout, timeUnit);
        long currentNanos = System.nanoTime();
        // add entry to list under lock
        lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("shutdown");
            }
            // prepend new entry to list
            list = new Entry(listenerExecutor, future, listener, currentNanos, timeoutNanos, list);
            int newCount;
            if ((newCount = ++count) > peakCount) {
                peakCount = newCount;
            }
        } finally {
            unlock();
        }
        
    }
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void poll() {
        Entry ready = null;
        Entry prev = null;
        Entry next = null;
        // remove any entries that are done, cancelled, or timed-out, adding 
        // those not cancelled to the "ready" list. (cancelled are just gc'd)
        lock();
        try {
            long currentNanos = System.nanoTime();
            int c = count; // (count in local var for faster decrements)
            for (Entry e = list; e != null; e = next) {
                next = e.next;
                Future<?> future = e.future;
                if (future.isDone() || future.isCancelled() || currentNanos - e.startNanos >= e.timeoutNanos) {
                    if (!future.isCancelled()) {
                        // prepend non-cancelled to ready list
                        e.next = ready;
                        ready = e;
                    } else {
                        e.next = null; // kindness to gc; could be a very long list to traverse
                    }
                    // remove entry from list
                    if (prev == null) {
                        list = next;
                    } else {
                        prev.next = next;
                    }
                    // decrement count
                    --c;
                } else {
                    // keeping in list
                    prev = e;
                }
            }
            // update count field
            count = c;
        } finally {
            unlock();
        }
        // process the ready list outside the lock so new entries can be added while we do this.
        for (Entry e = ready; e != null; e = next) {
            next = e.next;
            e.next = null;
            try {
                e.executor.execute(e);
            } catch (Exception ex) {
                // FIXME: LOG THIS (unlikely) EXCEPTION
            }
        }
    }
    
    private static void checkNull(Object arg, String message) {
        if (arg == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private static class Entry<T, F extends java.util.concurrent.Future<T>> implements Runnable {
        private final Executor executor;
        private final F future;
        private final ForeignFutureListener<T,F> listener;
        private final long startNanos;
        private final long timeoutNanos;
        // next Entry always accessed under lock
        @SuppressWarnings("rawtypes")
        private Entry next;
        
        @SuppressWarnings("rawtypes")
        public Entry(Executor executor, F future, ForeignFutureListener<T,F> listener,
                long startNanos, long timeoutNanos, Entry next) {
            this.executor = executor;
            this.future = future;
            this.listener = listener;
            this.startNanos = startNanos;
            this.timeoutNanos = timeoutNanos;
            this.next = next;
        }
        
        // runs in a thread of the specified executor, e.g. ChannelHandlerContext.executor() 
        public void run() {
            if (future.isDone()) {
                T value;
                try {
                    // note that we do NOT call listener.onSuccess() inside this try/catch,
                    // as we only want exceptions that might arise from future.get()
                    value = future.get();
                } catch (Exception e) {
                    listener.operationFailure(e);
                    return;
                }
                listener.operationSuccess(value);
            } else {
                listener.operationTimeout(future);
            }
        }
        
    }

}
