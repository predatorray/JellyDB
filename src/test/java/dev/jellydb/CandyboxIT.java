package dev.jellydb;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import me.predatorray.candybox.bookkeeper.bk.BookKeeperLedgerStore;
import me.predatorray.candybox.client.CandyboxClient;
import me.predatorray.candybox.common.SystemClock;
import me.predatorray.candybox.common.config.CandyboxConfig;
import me.predatorray.candybox.coordination.zk.ZooKeeperCoordinationService;
import me.predatorray.candybox.protocol.FrameCodec;
import me.predatorray.candybox.protocol.transport.*;
import me.predatorray.candybox.server.CandyboxNode;
import org.apache.bookkeeper.conf.*;
import org.apache.bookkeeper.util.LocalBookKeeper;
import org.junit.jupiter.api.*;

/**
 * Real storage smoke and synthetic reproducer. Not a three-VM test or proof of renewable expiry.
 */
class CandyboxIT {
  @Test
  void nativeAdapterAndNaiveSweeperRaceOnRealStorage() throws Exception {
    int zkPort;
    try (var socket = new ServerSocket(0)) {
      zkPort = socket.getLocalPort();
    }
    String zk = "127.0.0.1:" + zkPort;
    var bkConfig = new ServerConfiguration();
    bkConfig.setMetadataServiceUri("zk://" + zk + "/ledgers");
    bkConfig.setAllowLoopback(true);
    bkConfig.setOpenFileLimit(64);
    bkConfig.setProperty("numAddWorkerThreads", 1);
    bkConfig.setProperty("numReadWorkerThreads", 1);
    bkConfig.setProperty("numJournalCallbackThreads", 1);
    try (var cluster = LocalBookKeeper.getLocalBookies("127.0.0.1", zkPort, 3, true, bkConfig)) {
      cluster.start();
      var clientConfig = new ClientConfiguration();
      clientConfig.setMetadataServiceUri("zk://" + zk + "/ledgers");
      clientConfig.setNumWorkerThreads(2);
      clientConfig.setNumIOThreads(1);
      var config =
          CandyboxConfig.builder().routerUnownedRetryMillis(0).leaseRenewIntervalMillis(0).build();
      try (var ledger =
              new BookKeeperLedgerStore(clientConfig, "candybox".getBytes(StandardCharsets.UTF_8));
          var coordination = new ZooKeeperCoordinationService(zk, SystemClock.INSTANCE);
          var clientCoordination = new ZooKeeperCoordinationService(zk, SystemClock.INSTANCE);
          var node =
              new CandyboxNode(
                  1, config, ledger, coordination, SystemClock.INSTANCE, "127.0.0.1:1");
          var transport = new TcpTransport(new FrameCodec(), null, true, 1000, 2000)) {
        try (var server =
            new TcpTransportServer(
                "127.0.0.1",
                0,
                node.requestHandler(),
                new FrameCodec(),
                null,
                false,
                new TcpTransportServer.Options(1, 2, 2, Duration.ofSeconds(1)))) {
          // Membership must advertise the actual ephemeral TCP port.
          coordination.unregisterMember(1);
          coordination.registerMember(
              1, ("127.0.0.1:" + server.port()).getBytes(StandardCharsets.UTF_8));
          var client = new CandyboxClient(transport, clientCoordination, config);
          try (var raw = new CandyboxStorage(client, "jelly-it")) {
            raw.initialize(2);
            String id = Encoding.newId(), other = Encoding.newId();
            raw.put(id, "", Envelope.encode(""));
            raw.put(id, "😀/%", Envelope.encode("null"));
            raw.put(other, "protected", Envelope.encode("other"));
            assertEquals("", Envelope.decode(raw.get(id, "").orElseThrow()));
            assertEquals("null", Envelope.decode(raw.get(id, "😀/%").orElseThrow()));
            assertEquals(2, raw.list(id, null, 100).entries().size());
            raw.remove(id, "");
            assertTrue(raw.get(id, "").isEmpty());
            // Deterministic unsafe schedule: sweeper observed E=1 before writer renewal.
            String lifecycleId = Encoding.newId();
            raw.put(lifecycleId, "expiry", Envelope.encode("1"));
            String staleExpiry = Envelope.decode(raw.get(lifecycleId, "expiry").orElseThrow());
            assertEquals("1", staleExpiry);
            raw.put(id, "fresh", Envelope.encode("acknowledged fresh value"));
            raw.put(lifecycleId, "expiry", Envelope.encode("9999999999999"));
            raw.clear(id); // stale worker can issue this unconditionally, after both ACKs
            assertTrue(
                raw.get(id, "fresh").isEmpty(),
                "demonstrates the unsafe schedule, NOT acceptable service behavior");
            assertEquals(
                "9999999999999", Envelope.decode(raw.get(lifecycleId, "expiry").orElseThrow()));
            assertEquals("other", Envelope.decode(raw.get(other, "protected").orElseThrow()));
          }
        }
      }
    }
  }
}
