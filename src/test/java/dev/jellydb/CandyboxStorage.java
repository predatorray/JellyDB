package dev.jellydb;

import java.util.*;
import me.predatorray.candybox.client.CandyboxClient;
import me.predatorray.candybox.common.exception.CandyNotFoundException;

/** Raw adapter used only by the historical unsafe-algorithm integration counterexample. */
public final class CandyboxStorage implements AutoCloseable {
  private final CandyboxClient client;
  private final String box;

  public CandyboxStorage(CandyboxClient client, String box) {
    this.client = client;
    this.box = box;
  }

  public void initialize(int partitions) {
    if (!client.headBox(box)) client.createBox(box, partitions);
  }

  public void put(String id, String key, byte[] envelope) {
    client.putCandy(box, path(id, key), envelope, "application/vnd.jellydb.v1", Map.of(), null);
  }

  public Optional<byte[]> get(String id, String key) {
    try {
      return Optional.of(client.getCandy(box, path(id, key)));
    } catch (CandyNotFoundException e) {
      return Optional.empty();
    }
  }

  public void remove(String id, String key) {
    client.deleteCandy(box, path(id, key));
  }

  public CandyboxClient.Listing list(String id, String after, int limit) {
    return client.listCandies(box, prefix(id), after, limit);
  }

  public void clear(String id) {
    client.deleteRangeByPrefix(box, prefix(id));
  }

  private static String prefix(String id) {
    return "n/" + Encoding.id(id) + "/";
  }

  public static String path(String id, String key) {
    String token = Encoding.keyToken(key);
    Encoding.key(token);
    return prefix(id) + token;
  }

  public void close() {
    client.close();
  }
}
