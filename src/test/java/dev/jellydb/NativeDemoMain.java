package dev.jellydb;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import me.predatorray.candybox.bookkeeper.bk.BookKeeperLedgerStore;
import me.predatorray.candybox.common.SystemClock;
import me.predatorray.candybox.common.config.CandyboxConfig;
import me.predatorray.candybox.coordination.zk.ZooKeeperCoordinationService;
import me.predatorray.candybox.protocol.FrameCodec;
import me.predatorray.candybox.protocol.transport.TcpTransportServer;
import me.predatorray.candybox.server.CandyboxNode;
import org.apache.bookkeeper.conf.*;
import org.apache.bookkeeper.util.LocalBookKeeper;

/** Local real-storage browser fixture only. Never shipped in the production JAR. */
public final class NativeDemoMain {
  public static void main(String[] args) throws Exception {
    int zkPort;
    try (var socket = new ServerSocket(0)) {
      zkPort = socket.getLocalPort();
    }
    String zk = "127.0.0.1:" + zkPort;
    var bk = new ServerConfiguration();
    bk.setMetadataServiceUri("zk://" + zk + "/ledgers");
    bk.setAllowLoopback(true);
    bk.setOpenFileLimit(64);
    bk.setProperty("numAddWorkerThreads", 1);
    bk.setProperty("numReadWorkerThreads", 1);
    bk.setProperty("numJournalCallbackThreads", 1);
    try (var bookies = LocalBookKeeper.getLocalBookies("127.0.0.1", zkPort, 3, true, bk)) {
      bookies.start();
      var clientConfig = new ClientConfiguration();
      clientConfig.setMetadataServiceUri("zk://" + zk + "/ledgers");
      clientConfig.setNumWorkerThreads(2);
      clientConfig.setNumIOThreads(1);
      var config =
          CandyboxConfig.builder()
              .routerUnownedRetryMillis(0)
              .leaseRenewIntervalMillis(3000)
              .build();
      try (var ledgers = new BookKeeperLedgerStore(clientConfig, Encoding.utf8Bytes("candybox"));
          var coord = new ZooKeeperCoordinationService(zk, SystemClock.INSTANCE);
          var node =
              new CandyboxNode(1, config, ledgers, coord, SystemClock.INSTANCE, "127.0.0.1:1");
          var tcp =
              new TcpTransportServer(
                  "127.0.0.1",
                  0,
                  node.requestHandler(),
                  new FrameCodec(),
                  null,
                  false,
                  new TcpTransportServer.Options(1, 2, 2, Duration.ofSeconds(1)))) {
        coord.unregisterMember(1);
        coord.registerMember(1, Encoding.utf8Bytes("127.0.0.1:" + tcp.port()));
        try (var runtime =
                new CandyboxRuntime(Map.of("JELLY_ZK_CONNECT", zk, "JELLY_BOX", "browser-native"));
            var http =
                new HttpService(
                    runtime.store(),
                    new Admission(
                        System::currentTimeMillis, 1000, 1000, 1000, 100_000_000, 1000))) {
          var maintenance = Executors.newSingleThreadScheduledExecutor();
          var sweeper = new NamespaceSweeper(runtime.store());
          maintenance.scheduleWithFixedDelay(
              () -> {
                try {
                  sweeper.step();
                } catch (RuntimeException e) {
                  e.printStackTrace();
                }
              },
              1,
              1,
              TimeUnit.SECONDS);
          var stop = new CountDownLatch(1);
          Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
          http.start("127.0.0.1", args.length == 0 ? 8080 : Integer.parseInt(args[0]));
          System.out.println("NATIVE_BROWSER_READY");
          try {
            stop.await();
          } finally {
            maintenance.shutdownNow();
          }
        }
      }
    }
  }
}
