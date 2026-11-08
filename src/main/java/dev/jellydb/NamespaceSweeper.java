package dev.jellydb;

import java.util.ArrayDeque;
import java.util.Map;
import me.predatorray.candybox.common.namespace.NamespaceKeys;

/** One global bounded scanner; no namespace timers. Multiple HTTP hosts may safely run it. */
public final class NamespaceSweeper {
  private final CandyboxNamespaceStore store;
  private final ArrayDeque<String> pending = new ArrayDeque<>();
  private int partition;
  private String cursor;
  private long visited, retired, cleanupPages, failures, ignored;

  public NamespaceSweeper(CandyboxNamespaceStore store) {
    this.store = store;
  }

  /** At most one enumeration (four identities), one HEAD, one claim and one cleanup page. */
  public synchronized void step() {
    try {
      if (pending.isEmpty()) {
        var page = store.enumerate(partition, 4, cursor);
        String next = page.cursor();
        if (page.keys().size() > 4
            || (next != null && (!next.startsWith("l/") || next.equals(cursor))))
          throw new IllegalStateException("Invalid lifecycle enumeration page");
        for (String physical : page.keys()) {
          if (!physical.startsWith("l/")) throw new IllegalStateException("Invalid lifecycle key");
          String id = NamespaceKeys.decode(physical.substring(2));
          if (!NamespaceKeys.lifecycle(id).equals(physical))
            throw new IllegalStateException("Invalid lifecycle encoding");
          try {
            Encoding.id(id);
            pending.add(id);
          } catch (ApiException e) {
            ignored++;
          } // dedicated Box may contain operator-created identities
        }
        cursor = next;
        if (next == null) partition = (partition + 1) % store.partitions();
      }
      if (pending.isEmpty()) return;
      String id =
          pending
              .peek(); // retain current identity if transport/cleanup fails; restart also safely
                       // rescans
      var head = store.headForSweep(id);
      if (head.state() == null) throw new IllegalStateException("Missing lifecycle state");
      visited++;
      boolean claimed = head.state().retired();
      if (!claimed) {
        var claim = store.claim(id, head.state().version());
        claimed = claim.outcome().equals("RETIRED");
      }
      if (claimed) {
        retired++;
        store.cleanup(
            id, 100); // always starts at first remaining page; another visit continues work
        cleanupPages++;
      }
      pending.remove();
    } catch (RuntimeException e) {
      failures++;
      throw e;
    }
  }

  public synchronized Map<String, Object> snapshot() {
    return Map.of(
        "partition",
        partition,
        "pendingIdentities",
        pending.size(),
        "visited",
        visited,
        "retiredVisits",
        retired,
        "cleanupPages",
        cleanupPages,
        "failures",
        failures,
        "foreignIdentities",
        ignored);
  }
}
