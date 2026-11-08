package dev.jellydb;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import me.predatorray.candybox.bookkeeper.bk.BookKeeperLedgerStore;
import me.predatorray.candybox.client.CandyboxClient;
import me.predatorray.candybox.common.*;
import me.predatorray.candybox.common.config.CandyboxConfig;
import me.predatorray.candybox.coordination.zk.ZooKeeperCoordinationService;
import me.predatorray.candybox.protocol.*;
import me.predatorray.candybox.protocol.transport.*;
import me.predatorray.candybox.server.CandyboxNode;
import org.apache.bookkeeper.conf.*;
import org.apache.bookkeeper.util.LocalBookKeeper;
import org.junit.jupiter.api.Test;

/**
 * Actual production adapter/runtime over real BK/ZK/TCP; manual owner time, real coordination time.
 */
class ManagedNamespacesIT {
  @Test
  void productionAdapterRenewalSweepingHttpAndHandover() throws Exception {
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
    AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    var config =
        CandyboxConfig.builder().routerUnownedRetryMillis(0).leaseRenewIntervalMillis(0).build();
    try (var bookies = LocalBookKeeper.getLocalBookies("127.0.0.1", zkPort, 3, true, bk)) {
      bookies.start();
      var clientConfig = new ClientConfiguration();
      clientConfig.setMetadataServiceUri("zk://" + zk + "/ledgers");
      clientConfig.setNumWorkerThreads(2);
      clientConfig.setNumIOThreads(1);
      try (var ledgers = new BookKeeperLedgerStore(clientConfig, Encoding.utf8Bytes("candybox"));
          var coord = new ZooKeeperCoordinationService(zk, SystemClock.INSTANCE);
          var nextCoord = new ZooKeeperCoordinationService(zk, SystemClock.INSTANCE);
          var clientCoord = new ZooKeeperCoordinationService(zk, SystemClock.INSTANCE);
          var node = new CandyboxNode(1, config, ledgers, coord, clock::get, "127.0.0.1:1");
          var next = new CandyboxNode(2, config, ledgers, nextCoord, clock::get, "127.0.0.1:2");
          var server = server(node);
          var nextServer = server(next);
          var transport = new TcpTransport(new FrameCodec(), null, true, 1000, 2000)) {
        coord.unregisterMember(1);
        coord.registerMember(1, Encoding.utf8Bytes("127.0.0.1:" + server.port()));
        nextCoord.unregisterMember(2);
        nextCoord.registerMember(2, Encoding.utf8Bytes("127.0.0.1:" + nextServer.port()));
        try (var nativeClient = new CandyboxClient(transport, clientCoord, config);
            var runtime =
                new CandyboxRuntime(Map.of("JELLY_ZK_CONNECT", zk, "JELLY_BOX", "managed-it"))) {
          var store = runtime.store();
          assertTrue(store.ready());
          String id = Encoding.newId(), other = Encoding.newId();
          var created = store.create(id);
          store.create(other);
          assertEquals(NamespaceStore.TTL_MILLIS, created.expiresAt() - created.createdAt());
          store.put(id, "", Envelope.encode(""));
          store.put(id, "😀/%", Envelope.encode("null"));
          store.put(other, "protected", Envelope.encode("ciphertext:SGVsbG8="));
          assertEquals("", store.get(id, "").value());
          assertEquals("null", store.get(id, "😀/%").value());
          var page = store.list(id, null, 1);
          assertEquals(1, page.keys().size());
          assertNotNull(page.nextCursor());
          assertEquals(
              1, store.list(id, Encoding.cursorKey(id, page.nextCursor()), 1).keys().size());
          assertThrows(ApiException.class, () -> Encoding.cursorKey(other, page.nextCursor()));
          var before = nativeClient.headNamespace("managed-it", id).state();
          clock.set(before.expiresAtMillis() - 1);
          var renewed = store.put(id, "fresh", Envelope.encode("acknowledged fresh data"));
          clock.set(before.expiresAtMillis());
          assertEquals(
              "STALE",
              nativeClient.claimNamespaceExpiry("managed-it", id, before.version()).outcome());
          assertEquals(
              "NOT_RETIRED", nativeClient.cleanupNamespace("managed-it", id, 100).outcome());
          assertEquals("acknowledged fresh data", store.get(id, "fresh").value());
          var sweeper = new NamespaceSweeper(store);
          for (int n = 0; n < 16; n++) sweeper.step();
          assertEquals("acknowledged fresh data", store.get(id, "fresh").value());
          assertEquals(renewed.expiresAt(), store.inspect(id).expiresAt());

          // Concurrent different-key writes are retained by the owner; no client-side
          // read/modify/write.
          var pool = Executors.newFixedThreadPool(2);
          try {
            var writes = new ArrayList<Future<?>>();
            for (int n = 0; n < 12; n++) {
              final int k = n;
              writes.add(
                  pool.submit(() -> store.put(id, "concurrent" + k, Envelope.encode("v" + k))));
            }
            for (var write : writes) write.get();
          } finally {
            pool.shutdownNow();
          }
          for (int n = 0; n < 12; n++)
            assertEquals("v" + n, store.get(id, "concurrent" + n).value());
          long deadline = store.inspect(id).expiresAt();
          clock.addAndGet(100); // make accidental renewal by reads/deletes/clear observable
          store.remove(id, "absent");
          store.remove(id, "😀/%");
          assertEquals(deadline, store.inspect(id).expiresAt());

          node.releaseBox(BoxName.of("managed-it"));
          next.openBox(BoxName.of("managed-it"));
          assertEquals("acknowledged fresh data", store.get(id, "fresh").value());
          assertEquals(deadline, store.inspect(id).expiresAt());

          // Production HTTP wiring, including real 1 MiB incompressible data and browser CORS
          // headers.
          try (var http =
              new HttpService(
                  store,
                  new Admission(System::currentTimeMillis, 1000, 1000, 1000, 100_000_000, 1000))) {
            int port = http.start("127.0.0.1", 0);
            var browser = HttpClient.newHttpClient();
            String root = "http://127.0.0.1:" + port + "/v1/namespaces/" + id;
            assertEquals(200, send(browser, "GET", root, null).statusCode());
            byte[] random = new byte[Envelope.MAX_VALUE * 3 / 4];
            new Random(195).nextBytes(random);
            String large = Base64.getEncoder().encodeToString(random);
            assertEquals(Envelope.MAX_VALUE, large.length());
            assertEquals(
                204,
                send(
                        browser,
                        "PUT",
                        root + "/items/~bGFyZ2U",
                        Api.JSON.writeValueAsString(Map.of("value", large)))
                    .statusCode());
            assertEquals(
                large,
                Api.JSON
                    .readTree(send(browser, "GET", root + "/items/~bGFyZ2U", null).body())
                    .get("value")
                    .asText());
            long afterPutDeadline = store.inspect(id).expiresAt();
            assertTrue(afterPutDeadline > deadline);
            clock.addAndGet(100);
            assertEquals(204, send(browser, "DELETE", root + "/items", null).statusCode());
            assertTrue(store.list(id, null, 100).keys().isEmpty());
            assertEquals(afterPutDeadline, store.inspect(id).expiresAt());
          }

          // Bounded clear is explicitly partial, then repeatable; no unbounded loop under churn.
          for (int n = 0; n < 101; n++) store.put(id, "bulk" + n, Envelope.encode("v"));
          var bounded = new CandyboxNamespaceStore(nativeClient, "managed-it", 8, () -> true, 1);
          assertEquals(
              "clear_incomplete", assertThrows(ApiException.class, () -> bounded.clear(id)).code);
          assertEquals(1, store.list(id, null, 100).keys().size());
          bounded.clear(id);
          assertTrue(store.list(id, null, 100).keys().isEmpty());

          store.put(id, "expire", Envelope.encode("old"));
          long expires = store.inspect(id).expiresAt();
          clock.set(expires);
          assertEquals(404, assertThrows(ApiException.class, () -> store.inspect(id)).status);
          assertEquals(
              404,
              assertThrows(ApiException.class, () -> store.put(id, "late", Envelope.encode("bad")))
                  .status);
          assertEquals(409, assertThrows(ApiException.class, () -> store.create(id)).status);
          for (int n = 0; n < 24; n++) sweeper.step();
          assertTrue(sweeper.snapshot().get("cleanupPages") instanceof Long);
          assertEquals("OK", nativeClient.cleanupNamespace("managed-it", id, 100).outcome());
          assertNull(nativeClient.cleanupNamespace("managed-it", id, 100).cursor());
          clock.set(expires - 1000);
          assertEquals(404, assertThrows(ApiException.class, () -> store.get(id, "expire")).status);
          assertEquals(
              404, assertThrows(ApiException.class, () -> store.inspect(Encoding.newId())).status);

          // The old generic object APIs cannot bypass managed-Box lifecycle rules.
          assertThrows(
              RuntimeException.class, () -> nativeClient.deleteRangeByPrefix("managed-it", ""));
          server.close();
          nextServer.close();
          assertThrows(RuntimeException.class, runtime::probe);
          assertFalse(store.ready());
          try (var http = new HttpService(store, new Admission(System::currentTimeMillis))) {
            int port = http.start("127.0.0.1", 0);
            assertEquals(
                503,
                send(
                        HttpClient.newHttpClient(),
                        "GET",
                        "http://127.0.0.1:" + port + "/v1/namespaces/" + id,
                        null)
                    .statusCode());
          }
        }
      }
    }
  }

  private static HttpResponse<String> send(
      HttpClient client, String method, String url, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Origin", "null")
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    return response;
  }

  private static TcpTransportServer server(CandyboxNode node) {
    return new TcpTransportServer(
        "127.0.0.1",
        0,
        node.requestHandler(),
        new FrameCodec(),
        null,
        false,
        new TcpTransportServer.Options(1, 2, 2, Duration.ofSeconds(1)));
  }
}
