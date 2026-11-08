package dev.jellydb;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.SecureRandom;
import java.util.Base64;

public final class Encoding {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Encoding() {}

  public static String newId() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    return b64(b);
  }

  public static String b64(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String id(String id) {
    try {
      if (id.length() != 22
          || Base64.getUrlDecoder().decode(id).length != 16
          || !b64(Base64.getUrlDecoder().decode(id)).equals(id))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("Invalid namespace ID");
    }
    return id;
  }

  public static String key(String token) {
    if (!token.startsWith("~") || token.length() > 343)
      throw ApiException.invalid("Key must be ~ plus canonical base64url UTF-8");
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(token.substring(1));
      if (bytes.length > 256 || !keyToken(bytes).equals(token))
        throw new IllegalArgumentException();
      return utf8(bytes);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("Invalid or excessive key encoding");
    }
  }

  public static String keyToken(String key) {
    return keyToken(utf8Bytes(key));
  }

  private static String keyToken(byte[] bytes) {
    return "~" + b64(bytes);
  }

  public static byte[] utf8Bytes(String s) {
    try {
      var b =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(s));
      byte[] out = new byte[b.remaining()];
      b.get(out);
      return out;
    } catch (CharacterCodingException e) {
      throw ApiException.invalid("Unpaired Unicode surrogate");
    }
  }

  public static String utf8(byte[] b) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(b))
          .toString();
    } catch (CharacterCodingException e) {
      throw ApiException.invalid("Invalid UTF-8");
    }
  }

  public static String cursor(String id, String after) {
    return b64(utf8Bytes(id + ":" + Encoding.keyToken(after)));
  }

  public static String cursorKey(String id, String cursor) {
    if (cursor.length() > 512) throw ApiException.invalid("Invalid cursor");
    try {
      byte[] b = Base64.getUrlDecoder().decode(cursor);
      if (!b64(b).equals(cursor)) throw new IllegalArgumentException();
      String s = utf8(b);
      if (!s.startsWith(id + ":")) throw new IllegalArgumentException();
      return key(s.substring(23));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("Cursor belongs to another namespace or is malformed");
    }
  }
}
