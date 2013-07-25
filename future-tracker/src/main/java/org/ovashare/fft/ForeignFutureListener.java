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

/**
 * Listener interface for the ForeignFutureTracker. 
 * 
 * @author Bill Dortch @ gmail
 *
 * @param <T> the type of the value returned by the Future.
 * @param <F> the type of the Future.
 */
public interface ForeignFutureListener<T, F extends java.util.concurrent.Future<T>> {

    /**
     * Called if the operation succeeds, passing the value returned by the {@code Future.get()} method.
     * 
     * @param value the value returned by the {@code Future.get()} method.
     */
    void operationSuccess(T value);
    
    /**
     * Called if the listen timeout expires before the operation completes.
     * <p>
     * Note that the ForeignFutureTracker keeps its own timer and does not call the 
     * {@code Future.get(timeout, TimeUnit)} method, so for some types of Future it may still be
     * possible to retrieve information after the timeout.
     * 
     * (For example, the Spymemcached {@code BulkFuture.getSome(0, TimeUnit)} method will return any 
     * items retrieved so far.)
     * 
     * @param future the original future.
     */
    void operationTimeout(F future);

    /**
     * Called if the {@link java.util.concurrent.Future#get() Future.get()} operation throws an exception.
     * 
     * @param ex
     */
    void operationFailure(Exception ex);
    
}
