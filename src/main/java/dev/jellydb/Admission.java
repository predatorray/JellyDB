package dev.jellydb;

import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Advisory per-node limits. No namespace/IP metric labels and no forwarding-header trust. */
public final class Admission {
  public enum Kind {
    READ,
    WRITE,
    CREATE
  }

  private static final int MAX_SOURCES = 4096;
  private final LinkedHashMap<String, Source> sources = new LinkedHashMap<>(32, .75f, true);
  private final LongSupplier millis;
  private final Bucket bytes, writes;
  private final double[] rates;
  private boolean pressure;
  private long rejected;

  public Admission(LongSupplier millis) {
    this(millis, 20, 3.333333, 10.0 / 3600, 1 << 20, 4);
  }

  public Admission(
      LongSupplier millis,
      double read,
      double write,
      double create,
      double byteRate,
      double globalWriteRate) {
    this.millis = millis;
    rates = new double[] {read, write, create};
    long now = millis.getAsLong();
    bytes = new Bucket(byteRate, byteRate * 2, now);
    writes = new Bucket(globalWriteRate, globalWriteRate * 2, now);
  }

  public synchronized void admit(String ip, Kind kind, int wireBytes) {
    long now = millis.getAsLong();
    sources.entrySet().removeIf(e -> now - e.getValue().last > 3_600_000);
    Source source = sources.get(ip);
    if (source == null) {
      if (sources.size() >= MAX_SOURCES) {
        rejected++;
        throw new ApiException(429, "limiter_capacity", "Source limiter is full; retry later");
      }
      source = new Source(now, rates);
      sources.put(ip, source);
    }
    source.last = now;
    boolean mutation = kind != Kind.READ;
    if (pressure && mutation) {
      rejected++;
      throw new ApiException(
          503,
          "capacity_unavailable",
          "Writes paused for disk/archive pressure or unavailable telemetry");
    }
    // Rejected admissions can consume earlier tokens: conservative, never an over-admission.
    if (!source.buckets[kind.ordinal()].take(1, now)
        || (mutation && (!writes.take(1, now) || !bytes.take(wireBytes, now)))) {
      rejected++;
      throw new ApiException(
          429, "rate_limited", "Per-node request or admitted-byte budget exhausted");
    }
  }

  public synchronized void pressure(boolean value) {
    pressure = value;
  }

  public synchronized void sampleDisk(Path path) {
    try {
      var fs = Files.getFileStore(path);
      double free = (double) fs.getUsableSpace() / fs.getTotalSpace();
      if (free < .20) pressure = true;
      else if (free > .30) pressure = false;
    } catch (Exception e) {
      pressure = true;
    }
  }

  public synchronized Map<String, Object> snapshot() {
    return Map.of(
        "limiterSources", sources.size(), "rejected", rejected, "writePressure", pressure);
  }

  private static final class Source {
    long last;
    final Bucket[] buckets;

    Source(long now, double[] rates) {
      last = now;
      buckets = new Bucket[rates.length];
      for (int i = 0; i < rates.length; i++)
        buckets[i] = new Bucket(rates[i], i == 2 ? 10 : rates[i] * 2, now);
    }
  }

  private static final class Bucket {
    final double rate, burst;
    double tokens;
    long last;

    Bucket(double rate, double burst, long now) {
      if (rate <= 0 || burst <= 0) throw new IllegalArgumentException();
      this.rate = rate;
      this.burst = burst;
      tokens = burst;
      last = now;
    }

    boolean take(double amount, long now) {
      tokens = Math.min(burst, tokens + Math.max(0, now - last) * rate / 1000);
      last = Math.max(last, now);
      if (tokens < amount) return false;
      tokens -= amount;
      return true;
    }
  }
}
