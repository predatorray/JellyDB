package dev.jellydb;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.*;
import me.predatorray.candybox.protocol.*;
import me.predatorray.candybox.protocol.transport.TcpTransport;
import org.junit.jupiter.api.Test;

class DeadlineTransportTest {
  @Test
  void closesAResponseThatTricklesFasterThanTheSocketReadTimeout() throws Exception {
    try (var listener = new ServerSocket(0);
        var transport =
            new DeadlineTransport(
                new TcpTransport(new FrameCodec(), null, true, 1000, 1000), 150)) {
      var peer = Executors.newSingleThreadExecutor();
      var future =
          peer.submit(
              () -> {
                try (var socket = listener.accept()) {
                  new FrameCodec().read(socket.getInputStream());
                  byte[] response =
                      new FrameCodec().encode(new Frame(Opcode.RESPONSE_OK, new byte[100]));
                  for (byte b : response) {
                    socket.getOutputStream().write(b);
                    socket.getOutputStream().flush();
                    Thread.sleep(25);
                  }
                } catch (Exception expectedDisconnect) {
                  /* client closes at its absolute deadline */
                }
              });
      try (var connection = transport.connect("127.0.0.1", listener.getLocalPort())) {
        assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () ->
                assertThrows(
                    ProtocolException.class,
                    () -> connection.call(new Frame(Opcode.HEAD_BOX, new byte[0]))));
        assertThrows(
            ProtocolException.class,
            () -> connection.call(new Frame(Opcode.HEAD_BOX, new byte[0])));
      } finally {
        future.get(3, TimeUnit.SECONDS);
        peer.shutdownNow();
      }
    }
  }
}
