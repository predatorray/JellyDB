# Native lifecycle integration verification

Candybox pin: **`c6392d5130ec5b4ec5c3f63d161b449a9bc29fc3`**, PR #195 resolving issue #194. Built locally as `0.2.0-jelly-c6392d5` with only POM version substitutions. Java compiler target 17, JDK 21.0.8, Maven 3.9.9, Linux amd64. No production provisioning, real email or public deployment.

## Executed

`mvn clean verify`: **14 application/HTTP/transport tests and two real-backend integration tests passed**, no failures/skips. The existing raw-algorithm counterexample remains on unmanaged storage. New managed integration uses real ZooKeeper, three in-process BookKeeper bookies, two fenced Candybox owners and real TCP with the production `CandyboxRuntime`/`CandyboxNamespaceStore`.

Covered natively: shared managed Box creation/readiness, fixed seven-day TTL, empty/Unicode/reserved keys and values, namespace-bound pagination, renewal invalidating a stale claim, refusal to clean an unretired namespace, fresh values surviving a real sweeper pass, concurrent different-key writes, non-renewing delete/clear/read/list, owner handover preserving value and expiry, real HTTP/CORS CRUD, an incompressible 1 MiB value, bounded partial clear and repeat, expiry equality, permanent identity rejection on recreate, cleanup and non-resurrection after a backward clock step, generic raw API rejection on managed storage, unavailable owners producing 503 and failed readiness.

The transport regression uses an actual TCP peer that trickles response bytes faster than the idle read timeout. The absolute call deadline closes it and rejects connection reuse. Backend errors observed in deliberate unavailability tests are expected evidence, not silently converted to 404.

Also executed upstream `NamespaceEngineTest` at the pinned revision: **12 tests passed**, covering lost acknowledgment/publication crashes and all-or-none recovery, paused payload writes crossing the deadline, stale-owner fencing, clock high-water/skew, conditional retirement, permanent identity retention after compaction, and the mixed-Syrup pinning regression. These are deterministic engine tests, not real-disk reclamation measurements.

Real Chromium fetch tests also passed against the local **native Candybox backend**, using the production adapter/runtime in a loopback test fixture. Checked cross-origin CRUD/preflight, empty/Unicode/reserved keys, JSON null versus missing, ciphertext strings, 400/413/415 errors with accessible request identity, and a sandboxed opaque/null-origin POST. This extends the former fake-only browser evidence; public HTTPS/proxy deployment remains unrun.

Archive regression: **nine local-SMTP tests passed** after updating the recorded upstream build identity; no real email was sent.

## Evidence boundaries

These are deterministic/embedded storage and local HTTP/browser checks, not three-VM production measurements. Test owner time is controlled independently of real coordination clocks. Upstream defines owner admission time, max(wall clock, durable HLC high-water), with synchronized-clock assumptions; a forward jump can expire data early. No claim of trusted UTC, exactly-once effects, mixed-version rolling upgrades or whole-VM write availability.

Still unrun: three-host RSS/compaction/recovery sizing, physical BookKeeper/Candybox reclamation lag, complete crash/response-loss/paused-owner/network/quorum/whole-VM matrix, public HTTPS/proxy deployment, actual mailbox delivery/export drill, ARM64, and 24-hour soak. Permanent retired-identity overhead and full scan scaling need deployment characterization. Archive reserve/pressure integration remains launch work. The old placeholder report is retained as [historical evidence](verification-initial.md), not current status.

## Packaging and runtime smoke

The exact-source dependency build and POM-only modification guard passed. Runtime packaging includes explicit Curator/ZooKeeper client libraries, native client, HTTP/value code, operator tools and configuration examples; no bookie/owner/test fixtures, compiler, Maven cache or browser tooling are included. `make package` verifies that exactly the pinned client JAR is present. Normalized tar metadata makes packing deterministic for the same built inputs. Full cluster/admin distribution remains separate.

The packaged production `Main` connected to the private local cluster, passed `/readyz`, and completed namespace creation, PUT/GET/DELETE with wildcard CORS. A value written through it was read through the independent native browser HTTP fixture, confirming shared backend state rather than a memory fallback. No public endpoint was deployed.

A targeted native-test rerun passed after strengthening the non-renewal assertions with owner-clock advancement. Its embedded BookKeeper teardown emitted an uncaught `RejectedExecutionException` from `BookieShutdownTrigger`/`SyncThread.requestFlush` after Maven reported BUILD SUCCESS. The full cause chain is preserved in the local run log; no API assertion failed and no acknowledged-write loss was observed. This cleanup diagnostic remains uninvestigated and is not represented as a fixed BookKeeper issue.

| Current measured HTTP artifact | Result |
| --- | ---: |
| Dependency JARs | 41 |
| Dependency bytes | 22,073,309 |
| Installed runtime file bytes | 22,195,888 |
| Compressed runtime archive bytes | 20,322,638 |

These exclude the JDK, owner/bookie/ZooKeeper server/admin processes. They do not replace full-stack RSS/recovery/compaction sizing.

Native open-loop harness smoke: five seconds at 10 offered requests/s, seed 20261107, 50 requests, zero errors/drops, p50/p95/p99 approximately 12.03/28.17/52.52 ms (fixed histogram upper edges). Uses the generous-limit loopback fixture and real storage, not the production per-IP budgets. This is a short harness check, not sustainable throughput, three-VM sizing or soak evidence.

Final runtime SHA-256: `fbfdddbfb7ec11a98c3bc4618f030c946b59efbf35b626915f0a70a014927b3b`. Package checksum inventory passed; test fakes/raw adapters and BookKeeper server artifacts are absent from the HTTP runtime.

## Portable build tooling follow-up

The Makefile is the supported checkout interface: `make build`, `make test`, `make package`, `make demo`, `make examples`, `make browser-test` and `make run`; see `make help`. Scripts remain implementation details and installed runtime entry points. Packaging no longer invokes system tar: Python `tarfile`/`gzip` write sorted entries with normalized ownership, mode and timestamps, including a fixed gzip header. This avoids the unsupported GNU `--sort` flag on macOS BSD tar. Production run-script boot identity also tolerates hosts without Linux `/proc`, preserving a supplied ID or using `unknown`.

Tool regressions validate payloads/long paths, executable permissions, normalized headers, deterministic output despite host mtime/mode changes, and atomic preservation of an existing archive on unsupported inputs. Tests execute on Linux; no macOS/ARM64 host run is claimed. Historical artifact sizes/checksums above predate this packer.

Executed follow-up checks: `make build` passed all 14 application/HTTP/transport tests and both real-backend integration tests; `make test-tools` passed all 13 archive/packaging tests. `make package` also passed with a deliberately failing system-tar executable first on PATH, without invoking it. Two packs of identical inputs produced identical bytes. Independent system-tar extraction verified every SHA256SUMS entry and executable launch-script permissions. Current archive: 20,337,954 bytes, SHA-256 `9cd73db5fea8d8d24bc868f4a65eab11fe009d8843b0cc8f88090afa8a0b39fc`. Actual macOS execution remains unverified.
