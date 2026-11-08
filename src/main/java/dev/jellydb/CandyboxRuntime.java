package dev.jellydb;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.predatorray.candybox.client.CandyboxClient;
import me.predatorray.candybox.common.SystemClock;
import me.predatorray.candybox.common.config.*;
import me.predatorray.candybox.common.exception.BoxAlreadyExistsException;
import me.predatorray.candybox.coordination.*;
import me.predatorray.candybox.coordination.zk.ZooKeeperCoordinationService;
import me.predatorray.candybox.protocol.FrameCodec;
import me.predatorray.candybox.protocol.transport.TcpTransport;
import org.apache.curator.framework.*;
import org.apache.curator.retry.RetryNTimes;

/** Owns discovery and the native client; never opens BookKeeper from application code. */
public final class CandyboxRuntime implements AutoCloseable {
  public static final String COMMIT = "c6392d5130ec5b4ec5c3f63d161b449a9bc29fc3";
  private final CuratorFramework discovery;
  private final ZooKeeperCoordinationService coordination;
  private final DeadlineTransport transport;
  private final CandyboxClient client;
  private final CandyboxNamespaceStore store;
  private final String box;
  private final BoxDescriptor descriptor;

  public CandyboxRuntime(Map<String, String> env) throws Exception {
    box = env.getOrDefault("JELLY_BOX", "jellydb-v1");
    int partitions = Integer.parseInt(env.getOrDefault("JELLY_PARTITIONS", "8"));
    if (partitions < 1 || partitions > 64)
      throw new IllegalArgumentException("JELLY_PARTITIONS must be 1..64");
    var builder =
        CuratorFrameworkFactory.builder()
            .connectString(env.getOrDefault("JELLY_ZK_CONNECT", "127.0.0.1:2181"))
            .namespace("candybox")
            .retryPolicy(new RetryNTimes(0, 0))
            .connectionTimeoutMs(2000)
            .sessionTimeoutMs(10000);
    String scheme = env.get("JELLY_ZK_AUTH_SCHEME"),
        credentials = env.get("JELLY_ZK_AUTH_CREDENTIALS");
    if ((scheme == null) != (credentials == null))
      throw new IllegalArgumentException("Configure both ZooKeeper auth fields");
    if (scheme != null) builder.authorization(scheme, Encoding.utf8Bytes(credentials));
    discovery = builder.build();
    discovery.start();
    coordination = new ZooKeeperCoordinationService(discovery, SystemClock.INSTANCE);
    var limits =
        new SizeLimits(1 << 20, 1024, 8192, 256 << 10, Envelope.MAX_VALUE + Envelope.HEADER);
    var config = CandyboxConfig.builder().sizeLimits(limits).routerUnownedRetryMillis(0).build();
    transport =
        new DeadlineTransport(
            new TcpTransport(new FrameCodec((1 << 20) + (64 << 10)), null, true, 1000, 2000), 3000);
    client = new CandyboxClient(transport, coordination, config);
    try {
      if (!discovery.blockUntilConnected(5, TimeUnit.SECONDS))
        throw new IllegalStateException("ZooKeeper discovery connection timed out");
      var existing = coordination.get(CandyboxKeys.boxMetaKey(box));
      if (existing.isEmpty()) {
        try {
          client.createNamespaceBox(box, partitions);
        } catch (BoxAlreadyExistsException race) {
          /* verify the winning descriptor below */
        }
      }
      descriptor =
          BoxDescriptor.decode(
              coordination
                  .get(CandyboxKeys.boxMetaKey(box))
                  .orElseThrow(() -> new IllegalStateException("Managed Box descriptor is missing"))
                  .value());
      if (!descriptor.namespaceManaged() || descriptor.partitionCount() != partitions)
        throw new IllegalStateException(
            "JELLY_BOX must be a managed Box with the configured partition count; legacy Boxes"
                + " cannot be reused");
      store = new CandyboxNamespaceStore(client, box, partitions, coordination::isConnected);
      store.probe();
    } catch (Exception e) {
      client.close();
      transport.close();
      coordination.close();
      discovery.close();
      throw e;
    }
  }

  public CandyboxNamespaceStore store() {
    return store;
  }

  public void probe() {
    store.markUnavailable();
    var current =
        BoxDescriptor.decode(
            coordination
                .get(CandyboxKeys.boxMetaKey(box))
                .orElseThrow(() -> new IllegalStateException("Managed Box was removed"))
                .value());
    if (!current.equals(descriptor))
      throw new IllegalStateException(
          "Managed Box incarnation changed; drain and restart after explicit administration");
    store.probe();
  }

  public void close() {
    try {
      client.close();
    } finally {
      try {
        transport.close();
      } finally {
        coordination.close();
        discovery.close();
      }
    }
  }
}
