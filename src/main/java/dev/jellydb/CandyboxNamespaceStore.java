package dev.jellydb;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.predatorray.candybox.client.CandyboxClient;
import me.predatorray.candybox.common.namespace.NamespaceKeys;
import me.predatorray.candybox.protocol.Message.NamespaceResponse;

/** Managed-Box adapter. Every value/lifecycle decision is made by the fenced Candybox owner. */
public final class CandyboxNamespaceStore implements NamespaceStore {
  private final CandyboxClient client;
  private final String box;
  private final int partitions;
  private final BooleanSupplier connected;
  private final int clearPages;
  private volatile boolean healthy;

  public CandyboxNamespaceStore(
      CandyboxClient client, String box, int partitions, BooleanSupplier connected) {
    this(client, box, partitions, connected, 32);
  }

  CandyboxNamespaceStore(
      CandyboxClient client,
      String box,
      int partitions,
      BooleanSupplier connected,
      int clearPages) {
    if (partitions < 1 || clearPages < 1) throw new IllegalArgumentException("Invalid work bounds");
    this.client = Objects.requireNonNull(client);
    this.box = box;
    this.partitions = partitions;
    this.connected = connected;
    this.clearPages = clearPages;
  }

  @Override
  public boolean ready() {
    return healthy && connected.getAsBoolean();
  }

  /** Checks owner reachability, managed-Box support and ADMIN access without creating test data. */
  void markUnavailable() {
    healthy = false;
  }

  public void probe() {
    healthy = false;
    for (int partition = 0; partition < partitions; partition++) enumerate(partition, 1, null);
    healthy = connected.getAsBoolean();
  }

  @Override
  public Metadata create(String id) {
    Encoding.id(id);
    return metadata(id, ok(invoke(() -> client.createNamespace(box, id, TTL_MILLIS))));
  }

  @Override
  public Metadata inspect(String id) {
    Encoding.id(id);
    return metadata(id, ok(invoke(() -> client.headNamespace(box, id))));
  }

  @Override
  public Item get(String id, String key) {
    validate(id, key);
    var response = ok(invoke(() -> client.getNamespaceItem(box, id, key)));
    return new Item(Envelope.decode(response.data()), metadata(id, response));
  }

  @Override
  public Metadata put(String id, String key, byte[] envelope) {
    validate(id, key);
    Envelope.decode(
        envelope); // reject unsupported/corrupt/oversized internal input before mutation
    return metadata(id, ok(invoke(() -> client.putNamespaceItem(box, id, key, envelope))));
  }

  @Override
  public Metadata remove(String id, String key) {
    validate(id, key);
    return metadata(id, ok(invoke(() -> client.deleteNamespaceItem(box, id, key))));
  }

  @Override
  public Page list(String id, String after, int limit) {
    Encoding.id(id);
    if (limit < 1 || limit > 100) throw ApiException.invalid("Page limit must be 1..100");
    String cursor = after == null ? null : physicalKey(id, after);
    var response = ok(invoke(() -> client.listNamespaceItems(box, id, limit, cursor)));
    if (response.keys().size() > limit) throw contractFailure();
    for (String key : response.keys()) validate(id, key);
    String next =
        response.cursor() == null ? null : Encoding.cursor(id, cursorKey(id, response.cursor()));
    return new Page(List.copyOf(response.keys()), next, metadata(id, response));
  }

  @Override
  public Metadata clear(String id) {
    Encoding.id(id);
    String cursor = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    for (int page = 0; page < clearPages && System.nanoTime() < deadline; page++) {
      String current = cursor;
      var response = ok(invoke(() -> client.clearNamespace(box, id, 100, current)));
      var metadata = metadata(id, response);
      if (response.cursor() == null) return metadata;
      cursorKey(id, response.cursor()); // validate backend scope before sending any continuation
      if (response.cursor().equals(cursor)) throw contractFailure();
      cursor = response.cursor();
    }
    throw new ApiException(
        503,
        "clear_incomplete",
        "Clear reached its bounded work budget and may be partial; repeating clear removes"
            + " remaining items");
  }

  NamespaceResponse enumerate(int partition, int limit, String cursor) {
    if (partition < 0 || partition >= partitions)
      throw new IllegalArgumentException("Invalid partition");
    return ok(invoke(() -> client.enumerateNamespaces(box, partition, limit, cursor)));
  }

  NamespaceResponse claim(String id, long version) {
    Encoding.id(id);
    var response = invoke(() -> client.claimNamespaceExpiry(box, id, version));
    if (!List.of("RETIRED", "STALE", "NOT_DUE").contains(response.outcome())) return ok(response);
    if (response.state() == null) throw contractFailure();
    return response;
  }

  NamespaceResponse cleanup(String id, int limit) {
    Encoding.id(id);
    var response = ok(invoke(() -> client.cleanupNamespace(box, id, limit)));
    if (response.state() == null || !response.state().retired()) throw contractFailure();
    if (response.cursor() != null) cursorKey(id, response.cursor());
    return response;
  }

  NamespaceResponse headForSweep(String id) {
    Encoding.id(id);
    var response = invoke(() -> client.headNamespace(box, id));
    if (response.outcome().equals("EXPIRED")
        && response.state() != null
        && response.state().retired()) return response;
    return ok(response);
  }

  int partitions() {
    return partitions;
  }

  private NamespaceResponse invoke(Supplier<NamespaceResponse> operation) {
    try {
      return operation.get();
    } catch (RuntimeException e) {
      healthy = false;
      throw e;
    }
  }

  private NamespaceResponse ok(NamespaceResponse response) {
    return switch (response.outcome()) {
      case "OK" -> response;
      case "MISSING", "ITEM_MISSING", "EXPIRED" -> throw ApiException.missing();
      case "EXISTS" ->
          throw new ApiException(
              409, "namespace_exists", "Namespace address has already been used");
      default -> throw contractFailure();
    };
  }

  private Metadata metadata(String id, NamespaceResponse response) {
    var state = response.state();
    if (state == null || state.retired() || state.idleTtlMillis() != TTL_MILLIS)
      throw contractFailure();
    return new Metadata(id, state.createdAtMillis(), state.expiresAtMillis());
  }

  private ApiException contractFailure() {
    healthy = false;
    return new ApiException(
        503, "backend_contract_violation", "Backend namespace response failed validation");
  }

  private static void validate(String id, String key) {
    Encoding.id(id);
    Encoding.key(Encoding.keyToken(key));
  }

  private static String physicalKey(String id, String key) {
    validate(id, key);
    return NamespaceKeys.item(id, key);
  }

  private String cursorKey(String id, String cursor) {
    String prefix = NamespaceKeys.prefix(id);
    try {
      if (!cursor.startsWith(prefix)) throw new IllegalArgumentException();
      String key = NamespaceKeys.decode(cursor.substring(prefix.length()));
      if (!physicalKey(id, key).equals(cursor)) throw new IllegalArgumentException();
      return key;
    } catch (IllegalArgumentException | ApiException e) {
      throw contractFailure();
    }
  }
}
