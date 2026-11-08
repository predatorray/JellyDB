package dev.jellydb;

import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.predatorray.candybox.protocol.Frame;
import me.predatorray.candybox.protocol.ProtocolException;
import me.predatorray.candybox.protocol.transport.*;

/** Closes a native TCP connection at an absolute call deadline, including trickled responses. */
public final class DeadlineTransport implements Transport {
  private final Transport delegate;
  private final long timeoutMillis;
  private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
  private final Semaphore calls = new Semaphore(4);
  private final Set<TimedConnection> connections = ConcurrentHashMap.newKeySet();

  public DeadlineTransport(Transport delegate, long timeoutMillis) {
    if (timeoutMillis < 1) throw new IllegalArgumentException("Invalid RPC deadline");
    this.delegate = delegate;
    this.timeoutMillis = timeoutMillis;
    timer.setRemoveOnCancelPolicy(true);
  }

  public Connection connect(String host, int port) {
    var connection = new TimedConnection(delegate.connect(host, port));
    connections.add(connection);
    return connection;
  }

  private final class TimedConnection implements Connection {
    private final Connection connection;
    private final AtomicBoolean closed = new AtomicBoolean();

    TimedConnection(Connection connection) {
      this.connection = connection;
    }

    public Frame call(Frame request) {
      if (closed.get()) throw new ProtocolException("Connection is closed");
      if (!calls.tryAcquire()) throw new ProtocolException("RPC capacity exhausted");
      var expired = new AtomicBoolean();
      ScheduledFuture<?> deadline = null;
      try {
        deadline =
            timer.schedule(
                () -> {
                  expired.set(true);
                  try {
                    close();
                  } catch (RuntimeException ignored) {
                    /* caller surfaces unknown outcome */
                  }
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS);
        Frame result = connection.call(request);
        if (expired.get())
          throw new ProtocolException(
              "RPC deadline; mutation outcome may be unknown", new SocketTimeoutException());
        return result;
      } finally {
        if (deadline != null) deadline.cancel(false);
        calls.release();
      }
    }

    public void close() {
      if (closed.compareAndSet(false, true)) {
        try {
          connection.close();
        } finally {
          connections.remove(this);
        }
      }
    }
  }

  public void close() {
    try {
      for (var connection : connections) connection.close();
    } finally {
      timer.shutdownNow();
      delegate.close();
    }
  }
}
