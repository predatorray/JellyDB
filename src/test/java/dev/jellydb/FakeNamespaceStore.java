package dev.jellydb;

import java.util.*;
import java.util.function.LongSupplier;

/** Test contract model only: synchronized is NOT a distributed implementation. */
final class FakeNamespaceStore implements NamespaceStore {
  final Map<String, State> states = new HashMap<>();
  private final LongSupplier time;

  FakeNamespaceStore(LongSupplier time) {
    this.time = time;
  }

  static final class State {
    final long created;
    long expires;
    boolean retired;
    final TreeMap<String, byte[]> values = new TreeMap<>();

    State(long now) {
      created = now;
      expires = now + TTL_MILLIS;
    }
  }

  public boolean ready() {
    return true;
  }

  private State live(String id) {
    State state = states.get(id);
    if (state == null || state.retired) throw ApiException.missing();
    if (time.getAsLong() >= state.expires) {
      state.retired = true;
      throw ApiException.missing();
    }
    return state;
  }

  private Metadata metadata(String id, State state) {
    return new Metadata(id, state.created, state.expires);
  }

  public synchronized Metadata create(String id) {
    if (states.containsKey(id)) throw new ApiException(409, "namespace_exists", "ID already used");
    State s = new State(time.getAsLong());
    states.put(id, s);
    return metadata(id, s);
  }

  public synchronized Metadata inspect(String id) {
    return metadata(id, live(id));
  }

  public synchronized Item get(String id, String key) {
    State s = live(id);
    byte[] b = s.values.get(key);
    if (b == null) throw ApiException.missing();
    return new Item(Envelope.decode(b), metadata(id, s));
  }

  public synchronized Metadata put(String id, String key, byte[] envelope) {
    Envelope.decode(envelope);
    State s = live(id); // all validation before atomic commit
    s.values.put(key, envelope.clone());
    s.expires = Math.max(s.expires, time.getAsLong() + TTL_MILLIS);
    return metadata(id, s);
  }

  public synchronized Metadata remove(String id, String key) {
    State s = live(id);
    s.values.remove(key);
    return metadata(id, s);
  }

  public synchronized Metadata clear(String id) {
    State s = live(id);
    s.values.clear();
    return metadata(id, s);
  }

  public synchronized Page list(String id, String after, int limit) {
    State s = live(id);
    List<String> keys = new ArrayList<>();
    var tail = after == null ? s.values : s.values.tailMap(after, false);
    var it = tail.keySet().iterator();
    while (it.hasNext() && keys.size() < limit) keys.add(it.next());
    String cursor = it.hasNext() ? Encoding.cursor(id, keys.get(keys.size() - 1)) : null;
    return new Page(keys, cursor, metadata(id, s));
  }

  // Claim compares the old expiration under the same serialization domain as PUT.
  synchronized boolean claim(String id, long expectedExpires) {
    State s = states.get(id);
    if (s == null || s.expires != expectedExpires || time.getAsLong() < s.expires) return false;
    s.retired = true;
    return true;
  }

  synchronized int cleanup(String id, int limit) {
    State s = states.get(id);
    if (s == null || !s.retired) return 0;
    int n = 0;
    var it = s.values.entrySet().iterator();
    while (it.hasNext() && n < limit) {
      it.next();
      it.remove();
      n++;
    }
    return n;
  }
}
