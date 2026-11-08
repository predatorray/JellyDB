package dev.jellydb;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CoreTest {
  @Test
  void reversibleKeysAndCursorBoundaries() {
    String id = Encoding.newId(), other = Encoding.newId();
    for (String k : List.of("", "/", "%", "~", ":", "a/b%? #", "こんにちは", "😀")) {
      assertEquals(k, Encoding.key(Encoding.keyToken(k)));
      assertEquals(k, Encoding.cursorKey(id, Encoding.cursor(id, k)));
    }
    assertThrows(ApiException.class, () -> Encoding.cursorKey(other, Encoding.cursor(id, "x")));
    for (String k : List.of("abc", "~%", "~a", "~_w", "~YQ==", "~YR"))
      assertThrows(ApiException.class, () -> Encoding.key(k));
    assertThrows(ApiException.class, () -> Encoding.key(Encoding.keyToken("a".repeat(257))));
    assertThrows(ApiException.class, () -> Encoding.utf8Bytes("\ud800"));
    assertThrows(ApiException.class, () -> Encoding.id("../../foo"));
  }

  @Test
  void envelopesRoundTripAndRejectCorruption() {
    for (String s :
        List.of("", "null", "{}", "a".repeat(10000), Encoding.b64(new byte[1000]), "😀")) {
      byte[] b = Envelope.encode(s);
      assertEquals(s, Envelope.decode(b));
      assertThrows(ApiException.class, () -> Envelope.decode(Arrays.copyOf(b, b.length - 1)));
      b[5] = 99;
      assertEquals(
          "corrupt_record", assertThrows(ApiException.class, () -> Envelope.decode(b)).code);
    }
    byte[] b = Envelope.encode("a".repeat(10000));
    assertTrue(b.length < 1000);
    b[9] = 1;
    assertThrows(ApiException.class, () -> Envelope.decode(b));
    byte[] bad = Envelope.encode("abc");
    bad[bad.length - 1] ^= 1;
    assertThrows(ApiException.class, () -> Envelope.decode(bad));
    assertThrows(ApiException.class, () -> Envelope.encode("x".repeat(Envelope.MAX_VALUE + 1)));
    assertEquals(
        "x".repeat(Envelope.MAX_VALUE),
        Envelope.decode(Envelope.encode("x".repeat(Envelope.MAX_VALUE))));
  }

  @Test
  void jsonIsStrictAndStringsArePreserved() {
    assertEquals(" null \n", parse("{\"value\":\" null \\n\"}"));
    for (String s :
        List.of(
            "",
            "null",
            "{}",
            "{\"value\":null}",
            "{\"value\":1}",
            "{\"value\":\"a\",\"value\":\"b\"}",
            "{\"value\":\"a\",\"x\":1}",
            "{\"value\":\"x\"} {}",
            "{\"value\":\"\\ud800\"}",
            "[".repeat(30) + "]".repeat(30))) assertThrows(ApiException.class, () -> parse(s));
    assertThrows(
        ApiException.class, () -> Api.parseValue(new byte[] {(byte) 0xff}, "application/json"));
  }

  private String parse(String s) {
    return Api.parseValue(s.getBytes(StandardCharsets.UTF_8), "application/json");
  }

  @Test
  void expiryRenewalClaimAndCleanupContract() {
    AtomicLong clock = new AtomicLong(1000);
    var store = new FakeNamespaceStore(clock::get);
    String id = Encoding.newId();
    long initial = store.create(id).expiresAt();
    store.put(id, "old", Envelope.encode("value"));
    clock.set(initial - 1);
    store.get(id, "old");
    store.list(id, null, 100);
    assertEquals(initial, store.inspect(id).expiresAt());
    store.put(id, "fresh", Envelope.encode("new"));
    long renewed = store.inspect(id).expiresAt();
    clock.set(initial + 1);
    assertFalse(store.claim(id, initial));
    assertEquals(0, store.cleanup(id, 100));
    assertEquals("new", store.get(id, "fresh").value());
    store.remove(id, "old");
    store.clear(id);
    assertEquals(renewed, store.inspect(id).expiresAt());
    store.put(id, "another", Envelope.encode("value"));
    renewed = store.inspect(id).expiresAt();
    clock.set(renewed);
    assertThrows(ApiException.class, () -> store.put(id, "late", Envelope.encode("bad")));
    clock.set(0);
    assertThrows(
        ApiException.class,
        () -> store.inspect(id)); // observed expiry never resurrects on clock regression
    assertEquals(1, store.cleanup(id, 1));
    assertEquals(0, store.cleanup(id, 1));
    assertThrows(ApiException.class, () -> store.create(id));
  }

  @Test
  void concurrentFakeWritersAndPagination() throws Exception {
    var store = new FakeNamespaceStore(() -> 1L);
    String id = Encoding.newId();
    store.create(id);
    var pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        int n = i;
        futures.add(pool.submit(() -> store.put(id, "key" + n, Envelope.encode("v" + n))));
      }
      for (var f : futures) f.get();
    } finally {
      pool.shutdownNow();
    }
    var first = store.list(id, null, 10);
    assertEquals(10, first.keys().size());
    var second = store.list(id, Encoding.cursorKey(id, first.nextCursor()), 100);
    assertEquals(20, second.keys().size());
    assertEquals(
        30,
        new HashSet<String>() {
          {
            addAll(first.keys());
            addAll(second.keys());
          }
        }.size());
  }

  @Test
  void failuresNeverRenewAndUnavailableIsNotMissing() {
    var store = new FakeNamespaceStore(() -> 1L);
    String id = Encoding.newId();
    store.create(id);
    long expiration = store.inspect(id).expiresAt();
    assertThrows(ApiException.class, () -> store.put(id, "bad", new byte[1]));
    assertEquals(expiration, store.inspect(id).expiresAt());
    var blocked = new UnavailableNamespaceStore();
    assertFalse(blocked.ready());
    assertEquals(503, assertThrows(ApiException.class, () -> blocked.list(id, null, 10)).status);
  }

  @Test
  void limiterIsBoundedAndPressureFailsClosed() {
    AtomicLong clock = new AtomicLong();
    var limiter = new Admission(clock::get, 1, 1, 1, 100, 1);
    limiter.admit("a", Admission.Kind.READ, 0);
    limiter.admit("a", Admission.Kind.READ, 0);
    assertEquals(
        429,
        assertThrows(ApiException.class, () -> limiter.admit("a", Admission.Kind.READ, 0)).status);
    clock.set(1000);
    limiter.admit("a", Admission.Kind.READ, 0);
    limiter.pressure(true);
    assertEquals(
        503,
        assertThrows(ApiException.class, () -> limiter.admit("a", Admission.Kind.WRITE, 1)).status);
    limiter.pressure(false);
    limiter.sampleDisk(java.nio.file.Path.of("/nonexistent-jelly-path"));
    assertEquals(
        503,
        assertThrows(ApiException.class, () -> limiter.admit("b", Admission.Kind.WRITE, 1)).status);
    var churn = new Admission(clock::get, 10, 10, 10, 100, 100);
    for (int i = 0; i < 4096; i++) churn.admit("source" + i, Admission.Kind.READ, 0);
    assertThrows(ApiException.class, () -> churn.admit("overflow", Admission.Kind.READ, 0));
    assertEquals(4096, churn.snapshot().get("limiterSources"));
  }
}
