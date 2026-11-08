package dev.jellydb;

import java.nio.file.Path;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

public final class Main {
  public static void main(String[] args) throws Exception {
    var runtime = new CandyboxRuntime(System.getenv());
    var store = runtime.store();
    var sweeper = new NamespaceSweeper(store);
    var maintenance = Executors.newSingleThreadScheduledExecutor();
    var admission = new Admission(System::currentTimeMillis);
    var server = new HttpService(store, admission);
    var sampler = Executors.newSingleThreadScheduledExecutor();
    Path data = Path.of(System.getenv().getOrDefault("JELLY_DATA_DIR", "."));
    admission.sampleDisk(data);
    server.start(
        System.getenv().getOrDefault("JELLY_BIND", "127.0.0.1"),
        Integer.parseInt(System.getenv().getOrDefault("JELLY_PORT", "8080")));
    sampler.scheduleAtFixedRate(
        () -> {
          admission.sampleDisk(data);
          LoggerFactory.getLogger(Main.class)
              .info(
                  "metrics_snapshot http={} namespaces={}", server.snapshot(), sweeper.snapshot());
        },
        60,
        60,
        TimeUnit.SECONDS);
    maintenance.scheduleWithFixedDelay(
        () -> {
          try {
            sweeper.step();
          } catch (RuntimeException e) {
            LoggerFactory.getLogger(Main.class).warn("namespace_sweep_failed", e);
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
    maintenance.scheduleWithFixedDelay(
        () -> {
          try {
            runtime.probe();
          } catch (RuntimeException e) {
            LoggerFactory.getLogger(Main.class).warn("namespace_probe_failed", e);
          }
        },
        15,
        15,
        TimeUnit.SECONDS);
    LoggerFactory.getLogger(Main.class)
        .info("native_namespace_storage_started upstream={}", CandyboxRuntime.COMMIT);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  sampler.shutdownNow();
                  maintenance.shutdownNow();
                  server.close();
                  runtime.close();
                }));
    new CountDownLatch(1).await();
  }
}
