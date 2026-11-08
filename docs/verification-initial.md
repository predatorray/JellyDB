> Historical report for the original placeholder runtime. Superseded by [current verification](verification.md) after Candybox #195.

# Verification report — 2026-11-07 (America/Vancouver)

This is a local implementation preview, **not a public-launch report**. Source is in `/workspace/JellyDB`; upstream source remains a separate checkout. No production resources, real email or public deployment were used.

## Executed

* Built pinned Candybox revision `67fb5fdb523a87c15804923dbb3658f69ad2eb21` from source as immutable local `0.2.0-jelly-67fb5fd`. Verified upstream changes are POM version substitutions only. Java compiler target 17, build JDK 21.0.8; initial environment had runtime-only Java 21.
* JUnit application/HTTP tests: keys (empty, Unicode, reserved), cursor namespace boundaries, strict JSON/null/surrogates/depth, corrupt/compressed records and output bounds, expiry boundary/non-renewing reads/deletes/clear, stale claim vs renewal, concurrent fake writers/pagination, advisory limiter pressure/churn, actual HTTP CRUD/CORS/validation, incomplete-client connection cap and early declared-body rejection. Fake lifecycle tests are not distributed correctness evidence.
* Real Candybox IT: real TCP and cluster-aware Java client, ZooKeeper and three in-process bookies, native adapter CRUD/list/prefix isolation and deterministic unsafe renewal/sweeper counterexample. This test **demonstrates that a stale unconditional clear can erase fresh acknowledged data**, not that renewal is safe. It does not run a three-host cluster.
* First BookKeeper IT failed with `PageCacheUtil` reflective `FileDescriptor` access and process exit 5. Added the same Java module opens as upstream's integration suite and reran successfully; no storage checks/replication were weakened.
* Python archive tests use only a loopback SMTP sink: accepted submission and checksum-valid retrieval, duplicate delivery, disconnect after DATA/uncertain outcome, sender restart, permanent/temporary rejection, connection outage, bounded spool without pending deletion, accepted-unverified safety expiry, late bounce marker, malformed attachments/checksum failures and internal sequence-gap detection. No actual mailbox verification.
* Chromium `/usr/bin/chromium` with Playwright: actual HTTP browser fetch across ports 8081 -> 8080, preflight, empty/Unicode keys, overwrite/read/delete/clear, JSON null vs missing, ciphertext string, error statuses 400/413/415 with accessible request identity, actual sandboxed opaque/null-origin creation. Backend is the test-only fake. Screenshot in ignored `target/browser-demo.png`. Public HTTPS remains unrun.
* Seeded open-loop harness smoke: five seconds at 10 offered requests/s, seed 20261107, local fake, 50 requests, 0 errors/drops. Approximate upper-bin latency p50 7.66 ms, p95 16.04 ms, p99 26.75 ms. This is a harness smoke, **not Candybox throughput or capacity evidence**. Client RSS max 18,048 KiB. See ignored `target/workload-smoke.json` and streamed `.requests.jsonl`.
* YAML/XML parse, Python compilation, shell syntax, exact-upstream change guard and whitespace checks.

## Unrun / blocked

Owner-side atomic lifecycle operation, real renewal/expiry enforcement/cleanup, crash/restart/clock-skew proof, acknowledged-write tracking under faults, real physical reclamation and BookKeeper delay, three-host memory/compaction/recovery RSS, loss of each whole VM, ZK quorum/network/disk fault matrix, full runtime/dashboard/private deployment, public HTTPS/proxy validation, actual SMTP-provider/mailbox delivery/export drill, ARM64, and 24-hour soak are **not demonstrated**. No claim of production sizing, single-VM write availability, successful renewable lifecycle or indefinite archive retention.

Archive still needs enforceable separate reserves and ingress/private-dashboard pressure integration. Proxy and systemd files are templates, not a validated deployment. The local production entrypoint fails closed with 503 rather than silently serving an unsafe lifecycle. See [launch checklist](operations.md#launch-gates) and [failure drill procedures](failure-drills.md).

## Build and footprint

The runtime-only build excludes compiler, Maven cache, engine/application Java source, tests and browser tooling; it includes interpreted operator tools, config templates, examples and runbook text. Runtime measurements below are only this fail-closed HTTP artifact, not owner/bookie/ZK/admin footprint; see the capacity model for explicitly unmeasured full-stack estimates. Dependency inventory includes optional/test scopes separately from shipped runtime JARs. Full-cluster dependencies and the dashboard still require an off-host distribution build.

Final build-script run: **13 JUnit application/HTTP tests + 1 real Candybox IT passed**, 0 failures/skips; **9 archive tests passed**. Browser tests passed against local HTTP origins. The source-build and packaging script completed successfully. Operator tools/config/examples were added in a packaging-only refresh; their checksums were verified without rerunning unchanged Java tests. The development run reused an existing exact upstream checkout through an ignored local symlink; a fresh clone path is supplied but was not separately retested end to end.

| Measured artifact / process | Result | Scope |
| --- | ---: | --- |
| Runtime dependency JAR count | 18 | No tests or browser/compiler tooling |
| Dependency JAR bytes | 6,888,577 | Native client modules + Netty/Jackson/logging; optional Curator/ZK not shipped here |
| Runtime installed file bytes | 6,993,604 | Excludes JDK and full cluster distribution |
| Runtime tar.gz download bytes | 6,272,166 | `target/jellydb-runtime.tar.gz` |
| Production preview RSS after startup + two requests | 85,760 KiB / 24 threads | Single HTTP process, 128 MiB heap cap; not peak/load/full-stack measurement |
| Local fake demo RSS after short browser/load smoke | 107,208 KiB / 23 threads | Test process only; not Candybox sizing |

Packaged-runtime smoke: `/healthz` returned 200 and `POST /v1/namespaces` returned the expected 503 `lifecycle_primitive_required` with wildcard CORS, request ID, no-store and Retry-After. This confirms the safety gate, not functional production storage.

Normalized runtime tar packing was repeated twice with identical SHA-256: `0ba2acdf2b6314f303f6e0368f0341d7884b6cf46e8d3607b41818582e24b9af`. This checks archive determinism for the same built inputs, not cross-JDK byte reproducibility.
