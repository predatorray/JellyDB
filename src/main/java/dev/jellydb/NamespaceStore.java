package dev.jellydb;

import java.util.List;

/** Application contract backed by Candybox managed namespace operations. */
public interface NamespaceStore extends AutoCloseable {
  long TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

  record Metadata(String id, long createdAt, long expiresAt) {}

  record Item(String value, Metadata metadata) {}

  record Page(List<String> keys, String nextCursor, Metadata metadata) {}

  boolean ready();

  Metadata create(String id);

  Metadata inspect(String id);

  Item get(String id, String key);

  Metadata put(String id, String key, byte[] envelope);

  Metadata remove(String id, String key);

  Metadata clear(String id);

  Page list(String id, String after, int limit);

  @Override
  default void close() {}
}
