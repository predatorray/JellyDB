package dev.jellydb;

/** Handwritten unavailable-backend fake for HTTP/error tests. */
public final class UnavailableNamespaceStore implements NamespaceStore {
  private ApiException blocked() {
    return new ApiException(503, "backend_unavailable", "Storage is unavailable");
  }

  public boolean ready() {
    return false;
  }

  public Metadata create(String id) {
    throw blocked();
  }

  public Metadata inspect(String id) {
    throw blocked();
  }

  public Item get(String id, String key) {
    throw blocked();
  }

  public Metadata put(String id, String key, byte[] envelope) {
    throw blocked();
  }

  public Metadata remove(String id, String key) {
    throw blocked();
  }

  public Metadata clear(String id) {
    throw blocked();
  }

  public Page list(String id, String after, int limit) {
    throw blocked();
  }
}
