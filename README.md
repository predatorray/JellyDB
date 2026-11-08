# JellyDB

Public, temporary, experimental browser string storage on [Candybox](https://github.com/predatorray/candybox). Anyone knowing a namespace address can read, overwrite or delete its contents. Namespace IDs are addresses, not credentials.

**Native lifecycle integration is implemented.** Candybox [PR #195](https://github.com/predatorray/candybox/pull/195) supplies atomic renewal and conditional retirement, and JellyDB now uses those managed namespace operations. The original expiry blocker is resolved; three-host sizing/recovery, physical reclamation, public HTTPS, mailbox delivery and the 24-hour soak remain launch gates. See the [resolution and exact semantics](docs/upstream-blocker.md).

Implemented: bounded Netty REST, strict JSON/UTF-8/key/cursors, versioned plain/gzip records, native managed-Box adapter, bounded sweeper and partial-clear handling, advisory admission controls, browser examples, email archive/retrieval tools and deployment templates. No production provisioning or public launch is implied.

See [the testing and deployment quickstart](docs/quickstart.md) for a copyable local demo and systemd installation steps.

## Build and run

Build prerequisites: macOS or Linux/WSL, Make, JDK 17+ (including javac), Maven 3.9+, Git and Python 3. Runtime packaging uses Python’s standard library; GNU tar is not required. The production systemd deployment targets Linux. No npm library or frontend build is required.

```sh
make build
JELLY_ZK_CONNECT=127.0.0.1:2181 make run
# Requires a private Candybox/ZooKeeper cluster at the pinned revision.
# /readyz succeeds after all managed Box partitions pass their native probes.
```

`candybox.lock` records the exact commit/build version, checked against the POM. The build checks out Candybox commit `c6392d5130ec5b4ec5c3f63d161b449a9bc29fc3`, changes POM versions only to immutable `0.2.0-jelly-c6392d5`, builds dependencies from source, runs JUnit + real ZooKeeper/three-bookie integration, and packages a runtime-only archive. Maven proxy/CA settings belong in your local Maven configuration. If Maven is installed elsewhere, use `make build MVN=/absolute/path/to/mvn`. `PYTHON` and `JAVA` can also select executables. Run `make help` for all targets; `make package` repackages existing outputs without rebuilding Java. Artifact metadata and gzip headers are normalized for reproducible packing. No unpublished Candybox artifact is assumed to exist in Maven Central.

After the upstream dependencies have been built:

```sh
make test
```

Real storage integration starts local temporary BookKeeper/ZooKeeper processes in a test JVM. It does not provision VMs or send real email. The first integration attempt exposed BookKeeper's Java module-access requirements; matching flags from upstream are included. The service has no automatic retries for ambiguous mutations.

For a local **real-storage browser fixture**, after `make build`:

```sh
make demo
```

This starts real temporary ZooKeeper/three-bookie/Candybox storage in a test JVM and the native adapter on loopback; wait for `NATIVE_BROWSER_READY`. Run `make examples` on port 8081 in another terminal and run the browser test below. This fixture is not shipped in the runtime JAR and is not a three-VM sizing model.

Local **test-only fake** contract demo (not durable):

```sh
make demo-fake
# In another terminal:
make examples
# Open http://127.0.0.1:8081
```

`make browser-test` uses installed Playwright/Chromium for actual cross-origin and opaque-origin fetch tests against a running native HTTP service; use `make browser-test BACKEND=fake` when using the fake demo. These are development-only test dependencies, not included in runtime. Public HTTPS/domain/mail configuration is deliberately left to deployment.

Runtime configuration: `JELLY_ZK_CONNECT`, `JELLY_BOX` (default `jellydb-v1`), `JELLY_PARTITIONS` (default 8, fixed at creation), `JELLY_BIND`, `JELLY_PORT`, `JELLY_DATA_DIR`. Optional protected `JELLY_ZK_AUTH_SCHEME` and `JELLY_ZK_AUTH_CREDENTIALS` configure discovery authentication. Startup creates the one shared managed Box if absent and rejects existing legacy Boxes. Upgrade owners/clients together while stopped; older Candybox cannot open managed descriptor v3.

## Documentation

* [Requirements and ordered acceptance gates](docs/requirements.md)
* [OpenAPI](docs/openapi.yaml), [future client conventions](docs/future-client.md), [browser examples](examples/storage.js)
* [Architecture and failure semantics](docs/architecture.md), [record format](docs/storage-format.md)
* [Expiry blocker and race analysis](docs/upstream-blocker.md)
* [Capacity model](docs/capacity.md), [operator runbook / launch checklist](docs/operations.md)
* [Measured verification and limitations](docs/verification.md)

`tools/archive.py --help` documents the email-only archive commands. Development tests use only a loopback SMTP sink. Actual SMTP, a mailbox drill, public deployment and paid resources require separate authorization.
