package dev.jellydb;

/** Local contract demo only; fake cannot be packaged into the runtime artifact. */
public final class DemoMain {
  public static void main(String[] args) throws Exception {
    var server =
        new HttpService(
            new FakeNamespaceStore(System::currentTimeMillis),
            new Admission(System::currentTimeMillis, 1000, 1000, 1000, 100_000_000, 1000));
    server.start("127.0.0.1", args.length == 0 ? 8080 : Integer.parseInt(args[0]));
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    new java.util.concurrent.CountDownLatch(1).await();
  }
}
