
# ForeignFutureTracker (FFT)

The `ForeignFutureTracker` (FFT) provides a way to get a callback when an aribitrary `java.util.concurrent.Future` completes or times out. It is intended for use with external/third-party (hence "foreign") libraries that return opaque Futures for async operations, but provide no way to wait on them without blocking, such as various Memcached and Redis clients, and the AWS SDK.
 
FFT should __not__ be used with Futures that _do_ provide a callback mechanism, like those returned by Netty 4 or the Cassandra Java driver, as those mechanisms will be much more efficient and straightforward to use directly. FFT is a temporary, band-aid solution until more async APIs provide callback support.
 
Unlike some other solutions that tie up a thread for each pending Future, the FFT uses a single thread to poll outstanding Futures at a configurable interval (default once per millisecond). The caller supplies an `Executor` to be used for the callback; for example, the Netty 4 `ChannelHandler` `Executor` associated with a handler instance, returned by `ChannelHandlerContext.executor()`.
 
FFT is threadsafe, and can (and should) be shared by callers from multiple threads. A single, shared (static) FFT instance polling once per millisecond consumes negligible CPU even with several thousand outstanding Futures. However, _no more than one or two FFT instances should be active at the same time_ on a given machine, or they will begin to have a noticeable impact on CPU.

## Example: Spymemcached under Netty 4:
 
 ```java
  // make a global FFT instance, somewhere you can access it throughout your app.
  // you might want a second instance with a longer polling interval, say 10-20 ms,
  // for higher-latency Futures, like S3. but more than 2 instances may start to be
  // a burden on CPU. DO NOT make an instance per handler!
  public static final ForeignFutureTracker FFT = new ForeignFutureTracker(1, TimeUnit.MILLISECONDS);
 
  // ...
  
  public class MyChannelHandler extends ChannelInboundHandlerAdapter {
    
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    // ...
      final GetFuture<byte[]> future = memcachedClient.asyncGet("foo", binaryTranscoder());
      
      // add a listener for the Future, with a timeout in 2 seconds, and pass
      // the Executor for the current context so the callback will run
      // on the same thread.
      Global.FFT.addListener(future, 2, TimeUnit.SECONDS, ctx.executor(), 
        new ForeignFutureListener<Object,GetFuture<byte[]>>() {
 
          public void operationSuccess(byte[] value) {
            // do something ...
            ctx.fireChannelRead(someval);
          }
          public void operationTimeout(GetFuture<byte[]> f) {
            // do something ...
          }
          public void operationFailure(Exception e) {
            // do something ...
          }
        });
    }
    // ...
  }
```

