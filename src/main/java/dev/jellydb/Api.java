package dev.jellydb;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

public final class Api {
  // Worst-case JSON six-byte escape representation of a 1 MiB ASCII string, plus a small envelope
  // allowance.
  public static final int MAX_WIRE = 6 * Envelope.MAX_VALUE + 1024;
  public static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder()
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(16)
                      .maxStringLength(MAX_WIRE)
                      .build())
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .build());

  public record Response(int status, Object body, Long expiresAt) {}

  private final NamespaceStore store;

  public Api(NamespaceStore store) {
    this.store = store;
  }

  public Response route(
      String method,
      String path,
      Map<String, String> query,
      byte[] body,
      String contentType,
      String encoding) {
    if (path.equals("/healthz") && method.equals("GET"))
      return new Response(200, Map.of("live", true), null);
    if (path.equals("/readyz") && method.equals("GET")) {
      if (!store.ready())
        throw new ApiException(503, "backend_unavailable", "Native namespace storage is not ready");
      return new Response(200, Map.of("ready", true), null);
    }
    if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity"))
      throw new ApiException(
          415, "unsupported_encoding", "Compressed request bodies are unsupported");
    if (body.length > MAX_WIRE)
      throw new ApiException(413, "body_too_large", "Wire envelope exceeds limit");
    if (path.equals("/v1/namespaces")) {
      if (!method.equals("POST")) throw new ApiException(405, "method_not_allowed", "Use POST");
      if (body.length != 0) throw ApiException.invalid("Creation takes an empty body");
      var metadata = store.create(Encoding.newId());
      return new Response(201, metadata(metadata), metadata.expiresAt());
    }
    String[] parts = path.split("/", -1);
    if (parts.length < 4 || !parts[1].equals("v1") || !parts[2].equals("namespaces"))
      throw ApiException.missing();
    String id = Encoding.id(parts[3]);
    if (parts.length == 4 && method.equals("GET")) {
      var m = store.inspect(id);
      return new Response(200, metadata(m), m.expiresAt());
    }
    if (parts.length == 5 && parts[4].equals("keys") && method.equals("GET")) {
      if (!Set.of("cursor", "limit").containsAll(query.keySet()))
        throw ApiException.invalid("Unknown query field");
      int limit = 100;
      try {
        if (query.containsKey("limit")) limit = Integer.parseInt(query.get("limit"));
      } catch (NumberFormatException e) {
        throw ApiException.invalid("Invalid page limit");
      }
      if (limit < 1 || limit > 100) throw ApiException.invalid("Page limit must be 1..100");
      String after =
          query.containsKey("cursor") ? Encoding.cursorKey(id, query.get("cursor")) : null;
      var p = store.list(id, after, limit);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("keys", p.keys());
      result.put("nextCursor", p.nextCursor());
      return new Response(200, result, p.metadata().expiresAt());
    }
    if (parts.length >= 5 && parts[4].equals("items")) {
      if (parts.length == 5 && method.equals("DELETE")) {
        var m = store.clear(id);
        return new Response(204, null, m.expiresAt());
      }
      if (parts.length == 6) {
        String key = Encoding.key(parts[5]);
        return switch (method) {
          case "GET" -> {
            var item = store.get(id, key);
            yield new Response(200, Map.of("value", item.value()), item.metadata().expiresAt());
          }
          case "PUT" -> {
            var m = store.put(id, key, Envelope.encode(parseValue(body, contentType)));
            yield new Response(204, null, m.expiresAt());
          }
          case "DELETE" -> {
            var m = store.remove(id, key);
            yield new Response(204, null, m.expiresAt());
          }
          default -> throw new ApiException(405, "method_not_allowed", "Unsupported method");
        };
      }
    }
    throw ApiException.missing();
  }

  static String parseValue(byte[] body, String contentType) {
    if (!contentType.matches("(?i)application/json(?:;\\s*charset=utf-8)?"))
      throw new ApiException(415, "unsupported_media_type", "Use application/json UTF-8");
    Encoding.utf8(body); // Jackson otherwise replaces some malformed UTF-8 sequences.
    try (JsonParser parser = JSON.createParser(body)) {
      JsonNode node = JSON.readTree(parser);
      if (node == null
          || !node.isObject()
          || node.size() != 1
          || !node.has("value")
          || !node.get("value").isTextual()
          || parser.nextToken() != null)
        throw ApiException.invalid("Expected exactly one string field: value");
      String s = node.get("value").textValue();
      if (Encoding.utf8Bytes(s).length > Envelope.MAX_VALUE)
        throw new ApiException(413, "value_too_large", "Value exceeds 1 MiB UTF-8");
      return s;
    } catch (IOException e) {
      throw ApiException.invalid("Malformed, duplicate, or excessive JSON");
    }
  }

  private static Map<String, Object> metadata(NamespaceStore.Metadata m) {
    return Map.of(
        "id",
        m.id(),
        "url",
        "/v1/namespaces/" + m.id(),
        "createdAt",
        m.createdAt(),
        "expiresAt",
        m.expiresAt(),
        "idleTtlSeconds",
        604800,
        "limits",
        Map.of(
            "valueBytes",
            Envelope.MAX_VALUE,
            "keyBytes",
            256,
            "wireBytes",
            MAX_WIRE,
            "pageSize",
            100),
        "notice",
        "Public, temporary, experimental storage. Anyone knowing this address can change it.");
  }
}
