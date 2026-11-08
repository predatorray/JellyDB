package dev.jellydb;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.*;

/** Application format v1: JELY, version, codec, raw length, CRC32C, payload. */
public final class Envelope {
  public static final int MAX_VALUE = 1 << 20, HEADER = 14;
  private static final int MAGIC = 0x4a454c59;

  private Envelope() {}

  public static byte[] encode(String value) {
    byte[] plain = Encoding.utf8Bytes(value);
    if (plain.length > MAX_VALUE)
      throw new ApiException(413, "value_too_large", "Value exceeds 1 MiB UTF-8");
    byte[] body = plain;
    int codec = 0;
    if (plain.length >= 512) {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
          gzip.write(plain);
        }
        byte[] compressed = out.toByteArray();
        if (compressed.length + HEADER < plain.length) {
          body = compressed;
          codec = 1;
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    CRC32C crc = new CRC32C();
    crc.update(plain);
    return ByteBuffer.allocate(HEADER + body.length)
        .putInt(MAGIC)
        .put((byte) 1)
        .put((byte) codec)
        .putInt(plain.length)
        .putInt((int) crc.getValue())
        .put(body)
        .array();
  }

  public static String decode(byte[] bytes) {
    try {
      if (bytes.length < HEADER || bytes.length > MAX_VALUE + HEADER)
        throw new IOException("Envelope length");
      ByteBuffer b = ByteBuffer.wrap(bytes);
      if (b.getInt() != MAGIC || b.get() != 1) throw new IOException("Unsupported format");
      int codec = Byte.toUnsignedInt(b.get()), len = b.getInt(), checksum = b.getInt();
      if (len < 0 || len > MAX_VALUE) throw new IOException("Uncompressed bound");
      byte[] body = Arrays.copyOfRange(bytes, HEADER, bytes.length), plain;
      if (codec == 0) plain = body;
      else if (codec == 1) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
          plain = in.readNBytes(len + 1);
          if (in.read() != -1) throw new IOException("Decompression bound");
        }
      } else throw new IOException("Unsupported codec");
      CRC32C crc = new CRC32C();
      crc.update(plain);
      if (plain.length != len || (int) crc.getValue() != checksum)
        throw new IOException("Integrity mismatch");
      return Encoding.utf8(plain);
    } catch (IOException | IllegalArgumentException | ApiException e) {
      throw new ApiException(503, "corrupt_record", "Stored record failed integrity validation");
    }
  }
}
