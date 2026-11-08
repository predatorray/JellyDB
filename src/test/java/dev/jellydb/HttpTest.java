package dev.jellydb;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;

class HttpTest {
  private HttpService server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String base;

  @BeforeEach
  void start() {
    server =
        new HttpService(
            new FakeNamespaceStore(System::currentTimeMillis),
            new Admission(System::currentTimeMillis, 1000, 1000, 1000, 100_000_000, 1000));
    base = "http://127.0.0.1:" + server.start("127.0.0.1", 0);
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpResponse<String> call(String method, String path, String body, String contentType)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create(base + path)).header("Origin", "null");
    if (contentType != null) b.header("Content-Type", contentType);
    var response =
        client.send(
            b.method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertTrue(response.headers().firstValue("X-Request-ID").isPresent());
    return response;
  }

  @Test
  void crudPreflightPaginationAndIsolation() throws Exception {
    assertEquals(204, call("OPTIONS", "/v1/namespaces", null, null).statusCode());
    var create = call("POST", "/v1/namespaces", null, null);
    assertEquals(201, create.statusCode());
    String id = Api.JSON.readTree(create.body()).get("id").asText();
    String root = "/v1/namespaces/" + id;
    for (String key : List.of("", "😀/percent%", "other")) {
      String path = root + "/items/" + Encoding.keyToken(key);
      assertEquals(204, call("PUT", path, "{\"value\":\"null\"}", "application/json").statusCode());
      assertEquals(
          "null", Api.JSON.readTree(call("GET", path, null, null).body()).get("value").asText());
    }
    var listing = Api.JSON.readTree(call("GET", root + "/keys?limit=1", null, null).body());
    assertEquals(1, listing.get("keys").size());
    assertTrue(listing.get("nextCursor").isTextual());
    String other = Encoding.newId();
    assertEquals(
        400,
        call(
                "GET",
                "/v1/namespaces/" + other + "/keys?cursor=" + listing.get("nextCursor").asText(),
                null,
                null)
            .statusCode());
    assertEquals(204, call("DELETE", root + "/items/~", null, null).statusCode());
    assertEquals(204, call("DELETE", root + "/items/~", null, null).statusCode());
    assertEquals(404, call("GET", root + "/items/~", null, null).statusCode());
    assertEquals(204, call("DELETE", root + "/items", null, null).statusCode());
    assertEquals(
        0, Api.JSON.readTree(call("GET", root + "/keys", null, null).body()).get("keys").size());
  }

  @Test
  void errorClassesCarryCorsAndRequestIdentity() throws Exception {
    String root =
        "/v1/namespaces/"
            + Api.JSON
                .readTree(call("POST", "/v1/namespaces", null, null).body())
                .get("id")
                .asText()
            + "/items/~";
    assertEquals(400, call("PUT", root, "{\"value\":null}", "application/json").statusCode());
    assertEquals(415, call("PUT", root, "x", "text/plain").statusCode());
    assertEquals(
        413,
        call(
                "PUT",
                root,
                "{\"value\":\"" + "x".repeat(Envelope.MAX_VALUE + 1) + "\"}",
                "application/json")
            .statusCode());
    assertEquals(404, call("GET", "/missing", null, null).statusCode());
    var req =
        HttpRequest.newBuilder(URI.create(base + root))
            .header("Content-Encoding", "gzip")
            .PUT(HttpRequest.BodyPublishers.ofString("x"))
            .build();
    var res = client.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals(415, res.statusCode());
    assertEquals("*", res.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
  }

  @Test
  void blockedBackendIs503WithCors() throws Exception {
    try (var blocked =
        new HttpService(
            new UnavailableNamespaceStore(), new Admission(System::currentTimeMillis))) {
      int port = blocked.start("127.0.0.1", 0);
      var res =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/namespaces"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(503, res.statusCode());
      assertEquals("*", res.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals("1", res.headers().firstValue("Retry-After").orElseThrow());
      assertTrue(res.body().contains("backend_unavailable"));
    }
  }

  @Test
  void incompleteClientsAreBoundedAndExcessConnectionsHaveCors() throws Exception {
    var sockets = new java.util.ArrayList<java.net.Socket>();
    try {
      for (int i = 0; i < 4; i++) {
        var socket = new java.net.Socket("127.0.0.1", server.port());
        sockets.add(socket);
        socket
            .getOutputStream()
            .write(
                ("PUT /v1/namespaces/"
                        + Encoding.newId()
                        + "/items/~ HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Content-Length: 100\r\n"
                        + "Content-Type: application/json\r\n\r\n"
                        + "{")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
      }
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (!server.snapshot().get("connectionsActive").equals(4) && System.nanoTime() < deadline)
        Thread.sleep(10);
      assertEquals(4, server.snapshot().get("connectionsActive"));
      var response = call("GET", "/healthz", null, null);
      assertEquals(503, response.statusCode());
      assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
    } finally {
      for (var socket : sockets) socket.close();
    }
  }

  @Test
  void oversizedDeclaredBodyIsRejectedBeforeUpload() throws Exception {
    try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              ("PUT / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 999999999\r\n\r\n")
                  .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String response =
          new String(
              socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(response.startsWith("HTTP/1.1 413"));
      assertTrue(
          response.toLowerCase(java.util.Locale.ROOT).contains("access-control-allow-origin: *"));
    }
  }

  @Test
  void throttlingAlsoExposesRetryAndCors() throws Exception {
    try (var limited =
        new HttpService(
            new FakeNamespaceStore(System::currentTimeMillis),
            new Admission(System::currentTimeMillis, 0.001, 1, 1, 1000, 1))) {
      int port = limited.start("127.0.0.1", 0);
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(429, response.statusCode());
      assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
    }
  }
}
