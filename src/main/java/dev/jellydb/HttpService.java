package dev.jellydb;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One HTTP/1 request per connection. Four aggregate bodies at most; no blocking work on I/O loops.
 */
public final class HttpService implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(HttpService.class);
  private final EventLoopGroup boss = new NioEventLoopGroup(1), io = new NioEventLoopGroup(1);
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(2),
          new ThreadPoolExecutor.AbortPolicy());
  private final Semaphore connections = new Semaphore(4);
  private final Api api;
  private final Admission admission;
  private Channel server;
  private final AtomicLong completed = new AtomicLong(),
      failed = new AtomicLong(),
      rejectedTransport = new AtomicLong();

  public HttpService(NamespaceStore store, Admission admission) {
    this.api = new Api(store);
    this.admission = admission;
  }

  public int start(String host, int port) {
    server =
        new ServerBootstrap()
            .group(boss, io)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_RCVBUF, 16384)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  protected void initChannel(SocketChannel ch) {
                    var guard = new Guard();
                    ch.pipeline().addLast(new HttpServerCodec(2048, 8192, 8192));
                    ch.pipeline().addLast(guard);
                    ch.pipeline()
                        .addLast(
                            new HttpObjectAggregator(Api.MAX_WIRE) {
                              protected void handleOversizedMessage(
                                  ChannelHandlerContext ctx, HttpMessage msg) {
                                guard.error(
                                    ctx,
                                    new ApiException(
                                        413, "body_too_large", "Wire body exceeds limit"));
                              }
                            });
                    ch.pipeline()
                        .addLast(
                            new SimpleChannelInboundHandler<FullHttpRequest>() {
                              protected void channelRead0(
                                  ChannelHandlerContext ctx, FullHttpRequest request) {
                                if (guard.done.get() || guard.submitted) return;
                                guard.submitted = true;
                                ctx.channel().config().setAutoRead(false);
                                request.retain();
                                try {
                                  workers.execute(
                                      () -> {
                                        long start = System.nanoTime();
                                        int status = 500;
                                        String operation = "invalid";
                                        boolean admitted = false;
                                        try {
                                          if (guard.done.get())
                                            return; // queue deadline elapsed; never start a stale
                                          // mutation
                                          String method = request.method().name();
                                          operation =
                                              Set.of("GET", "PUT", "POST", "DELETE", "OPTIONS")
                                                      .contains(method)
                                                  ? method
                                                  : "invalid";
                                          if (!request.decoderResult().isSuccess())
                                            throw ApiException.invalid("Malformed HTTP request");
                                          if (method.equals("OPTIONS")) {
                                            status = 204;
                                            guard.reply(ctx, new Api.Response(204, null, null));
                                            return;
                                          }
                                          String target = request.uri();
                                          if (target.contains("%")
                                              || target.contains("#")
                                              || target.contains("\\"))
                                            throw ApiException.invalid(
                                                "Use canonical base64url paths and cursors");
                                          int q = target.indexOf('?');
                                          String path = q < 0 ? target : target.substring(0, q);
                                          Map<String, String> query =
                                              query(q < 0 ? "" : target.substring(q + 1));
                                          Admission.Kind kind =
                                              method.equals("GET")
                                                  ? Admission.Kind.READ
                                                  : path.equals("/v1/namespaces")
                                                      ? Admission.Kind.CREATE
                                                      : Admission.Kind.WRITE;
                                          admission.admit(
                                              ((InetSocketAddress) ctx.channel().remoteAddress())
                                                  .getAddress()
                                                  .getHostAddress(),
                                              kind,
                                              request.content().readableBytes());
                                          admitted = true;
                                          byte[] body = new byte[request.content().readableBytes()];
                                          request
                                              .content()
                                              .getBytes(request.content().readerIndex(), body);
                                          var result =
                                              api.route(
                                                  method,
                                                  path,
                                                  query,
                                                  body,
                                                  request
                                                      .headers()
                                                      .get(HttpHeaderNames.CONTENT_TYPE, ""),
                                                  request
                                                      .headers()
                                                      .get(HttpHeaderNames.CONTENT_ENCODING, ""));
                                          status = result.status();
                                          guard.reply(ctx, result);
                                        } catch (ApiException e) {
                                          status = e.status;
                                          guard.error(ctx, e);
                                        } catch (Exception e) {
                                          failed.incrementAndGet();
                                          status = 503;
                                          LOG.error(
                                              "backend_failure requestId={}", guard.requestId, e);
                                          guard.error(ctx, ApiException.unavailable());
                                        } finally {
                                          if (admitted) {
                                            completed.incrementAndGet();
                                            LOG.info(
                                                "request_completed requestId={} operation={}"
                                                    + " status={} durationMicros={}",
                                                guard.requestId,
                                                operation,
                                                status,
                                                (System.nanoTime() - start) / 1000);
                                          }
                                          ReferenceCountUtil.release(request);
                                          guard.release();
                                        }
                                      });
                                } catch (RejectedExecutionException e) {
                                  ReferenceCountUtil.release(request);
                                  guard.submitted = false;
                                  guard.error(
                                      ctx,
                                      new ApiException(
                                          503, "worker_saturated", "Worker queue is full"));
                                }
                              }

                              public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
                                guard.error(
                                    ctx,
                                    e instanceof TooLongFrameException
                                        ? new ApiException(
                                            413, "body_too_large", "Wire body exceeds limit")
                                        : ApiException.invalid("Malformed HTTP"));
                              }
                            });
                  }
                })
            .bind(host, port)
            .syncUninterruptibly()
            .channel();
    LOG.info("http_started port={}", port());
    return port();
  }

  public int port() {
    return ((InetSocketAddress) server.localAddress()).getPort();
  }

  public Map<String, Object> snapshot() {
    var m = new LinkedHashMap<String, Object>(admission.snapshot());
    m.put("connectionsActive", 4 - connections.availablePermits());
    m.put("workersActive", workers.getActiveCount());
    m.put("workersQueued", workers.getQueue().size());
    m.put("completed", completed.get());
    m.put("backendErrors", failed.get());
    m.put("transportRejects", rejectedTransport.get());
    m.put("heapUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    return m;
  }

  static Map<String, String> query(String s) {
    Map<String, String> result = new HashMap<>();
    if (s.isEmpty()) return result;
    for (String part : s.split("&", -1)) {
      int eq = part.indexOf('=');
      if (eq < 1 || result.putIfAbsent(part.substring(0, eq), part.substring(eq + 1)) != null)
        throw ApiException.invalid("Malformed or duplicate query field");
    }
    return result;
  }

  private final class Guard extends ChannelInboundHandlerAdapter {
    final String requestId = UUID.randomUUID().toString();
    final AtomicBoolean done = new AtomicBoolean(), released = new AtomicBoolean();
    boolean acquired, submitted;
    ScheduledFuture<?> timeout;

    public void channelActive(ChannelHandlerContext ctx) {
      acquired = connections.tryAcquire();
      if (!acquired) {
        error(ctx, new ApiException(503, "connection_capacity", "Connection capacity exhausted"));
        return;
      }
      timeout =
          ctx.executor()
              .schedule(
                  () ->
                      error(
                          ctx,
                          new ApiException(
                              503,
                              "request_deadline",
                              "Request deadline exceeded; mutation outcome may be unknown")),
                  15,
                  TimeUnit.SECONDS);
      ctx.fireChannelActive();
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (done.get()) {
        ReferenceCountUtil.release(msg);
        return;
      }
      if (msg instanceof HttpRequest r) {
        if (!r.decoderResult().isSuccess()) {
          ReferenceCountUtil.release(msg);
          error(ctx, ApiException.invalid("Malformed HTTP headers"));
          return;
        }
        if (HttpUtil.getContentLength(r, 0) > Api.MAX_WIRE) {
          ReferenceCountUtil.release(msg);
          error(ctx, new ApiException(413, "body_too_large", "Wire body exceeds limit"));
          return;
        }
        if (r.headers().contains(HttpHeaderNames.CONTENT_ENCODING)
            && !r.headers().get(HttpHeaderNames.CONTENT_ENCODING).equalsIgnoreCase("identity")) {
          ReferenceCountUtil.release(msg);
          error(
              ctx,
              new ApiException(415, "unsupported_encoding", "Compressed bodies are unsupported"));
          return;
        }
      }
      ctx.fireChannelRead(msg);
    }

    public void channelInactive(ChannelHandlerContext ctx) {
      done.set(true);
      if (timeout != null) timeout.cancel(false);
      if (!submitted) release();
      ctx.fireChannelInactive();
    }

    void release() {
      if (acquired && released.compareAndSet(false, true)) connections.release();
    }

    void error(ChannelHandlerContext ctx, ApiException e) {
      if (!done.get()) rejectedTransport.incrementAndGet();
      reply(
          ctx,
          new Api.Response(
              e.status,
              Map.of(
                  "error",
                  Map.of("code", e.code, "message", e.getMessage(), "requestId", requestId)),
              null));
    }

    void reply(ChannelHandlerContext ctx, Api.Response r) {
      if (!done.compareAndSet(false, true)) return;
      if (timeout != null) timeout.cancel(false);
      try {
        byte[] body = r.body() == null ? new byte[0] : Api.JSON.writeValueAsBytes(r.body());
        var response =
            new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(r.status()),
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response
            .headers()
            .setInt(HttpHeaderNames.CONTENT_LENGTH, body.length)
            .set(HttpHeaderNames.CONNECTION, "close");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response
            .headers()
            .set("Access-Control-Allow-Origin", "*")
            .set("Access-Control-Allow-Methods", "OPTIONS, GET, PUT, POST, DELETE")
            .set("Access-Control-Allow-Headers", "Content-Type, X-Request-ID")
            .set("Access-Control-Expose-Headers", "X-Request-ID, X-Expires-At, Retry-After")
            .set("X-Request-ID", requestId);
        if (r.expiresAt() != null)
          response.headers().set("X-Expires-At", Instant.ofEpochMilli(r.expiresAt()).toString());
        if (r.status() == 429 || r.status() == 503) response.headers().set("Retry-After", "1");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
      } catch (Exception e) {
        LOG.error("response_failure requestId={}", requestId, e);
        ctx.close();
      }
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
      error(ctx, ApiException.invalid("Malformed HTTP request"));
    }
  }

  public void close() {
    if (server != null) server.close().syncUninterruptibly();
    workers.shutdown();
    try {
      if (!workers.awaitTermination(20, TimeUnit.SECONDS)) workers.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      workers.shutdownNow();
    }
    boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    io.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
  }
}
