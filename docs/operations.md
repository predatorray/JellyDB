# Operator runbook and launch checklist

**Not yet validated for public launch.** The atomic expiry blocker is resolved by Candybox `c6392d5130ec5b4ec5c3f63d161b449a9bc29fc3` (PR #195), and the native managed adapter/readiness/sweeper are implemented. No production resources, public deployment or real mail have been created/sent. Placeholder addresses/credentials remain deployment inputs; infrastructure and operational launch gates still apply.

## Build / local operation

Use JDK 17+ and Maven 3.9+. `MVN=/path/to/mvn scripts/build.sh` clones exactly the pinned upstream under ignored `.upstream`, verifies only POM version substitutions, installs source-built immutable-version artifacts, verifies this project and assembles runtime JARs/checksums. Configure Maven proxy/CA via local settings; never disable TLS verification. Upstream engine code remains unchanged. `target/runtime` contains no source/compiler/Maven cache. The native client brings Candybox/Netty modules; Curator/ZooKeeper implementations are optional upstream dependencies and BookKeeper is needed by the owner, not the raw client. Inspect `target/dependencies.txt` and measured footprint; do not call the client dependency-free.

No jlink/native image is used; TLS, service providers and diagnostics must remain available. No ARM64 test or support claim. Upstream images may be amd64-only. Dashboard must be built once off-host using pinned upstream's frontend profile, then served by one private admin API. This flow is documented but not executed here; the HTTP runtime archive includes Curator/ZooKeeper client dependencies and operator/config assets, but is not a complete owner/bookie/admin distribution.

## Three-host path (templates; not yet deployment-tested)

1. Prepare private static addresses in `ops/hosts.example`; distribute matching `/etc/hosts`/DNS. Allow public 443 (and 80 for ACME), SSH from operator networks only. Firewall TCP 2181/2888/3888, 3181, 9709, 9710, 9713 and metrics/admin to private hosts/operator network. Candybox health/admin bind behavior must be checked; firewall remains mandatory. The three-member ensemble config is not upstream's one-ZK Compose example.
2. Install a shared JDK runtime, one exact Candybox distribution/library set (including BookKeeper/ZK/admin), Caddy 2.9.1 and Python 3. Verify release checksums before installation. Layout: `/opt/jellydb/releases`, `current` symlink, `/etc/jellydb`, `/var/lib/jellydb/{bookkeeper,zookeeper,archive}`, journal under `/var/log/journal`. Do not ship duplicate full distributions per service.
3. Create unprivileged `jellydb`, journal-read membership, protected 0600 config/env files. Write persistent ZooKeeper `data/myid` 1,2,3 exactly once on respective hosts, never regenerate at restart. Configure `clientPortAddress`, advertised bookie address/interface and Candybox node/address per host.
4. Start `cluster@zookeeper` on all three and verify quorum. Initialize BookKeeper `/ledgers` **once**, explicitly via pinned BookKeeper `shell initnewcluster`; record instance ID and never format existing journals/metadata automatically. Start three bookies, verify writable registration; then three Candybox owners. Service dependencies alone are not readiness: poll actual private health/ledger probes before each phase. Units intentionally contain no formatting.
5. Deploy units/scripts under names `cluster@.service`, `jellydb.service`, `jellydb-archive.service/timer`, `jellydb-archive-send.service/timer`. Create empty readable `/etc/jellydb/{zookeeper,bookkeeper,candybox,admin}.env` or explicit per-process config. Set restart/backoff and FD caps, time synchronization (chrony/systemd-timesyncd), read-only filesystem protections and writable data dirs. Admin only on host 1, with SSH tunnel `ssh -L 9713:127.0.0.1:9713 host` and no public ingress.
6. Replace blanket upstream WARN suppression with `ops/logback.xml` INFO lifecycle coverage; audit noisy categories with evidence. Configure `ops/journald.conf` and the complete unit list, including proxy/archive/supervisor. Test cursor persistence/restart and journal vacuum gaps. Do not use copytruncate. Collector rotates after 4 MiB raw or each <=5-minute invocation; local 5-minute polling can overshoot size while offline. Missing cursor is an explicit failure. journald may vacuum before replay if the spool stops; expose and record that gap.
7. TLS through `ops/Caddyfile`, ACME DNS/ports/certificate renewal; test Caddy validation against the installed pinned binary. DNS round-robin alone cannot provide per-request failover. Publish explicit healthy instance endpoints and have callers select another for **connection failures before dispatch**. Do not replay ambiguous mutations automatically. No free/paid load-balancer service is implied. Proxy-generated errors need JSON, request identity and wildcard CORS; validate 413, timeout and upstream failure in the HTTPS drill.
8. Keep public ingress stopped until every gate below passes. Production lifecycle integration is tested locally; a verified complete cluster/dashboard installer and deployment evidence are pending. Templates are not a launch authorization.

Heaps/direct caps in `cluster-component` are experiments, not validated fit. `ServerConfig` at this revision does not parse a max-Candy-size property: service/client validation is not a storage-node-wide size limit. Candidate custom bootstrap/upstream config addition must expose SizeLimits before launch. Do not silently assume ignored properties work.

## Readiness, failure and maintenance

`/healthz` is process liveness; `/readyz` checks native managed namespace readiness. Startup creates the one shared managed Box if absent, rejects a legacy Box or mismatched partition count, and probes every owner partition; probes repeat every 15 seconds. Discovery/call/probe failures mark readiness unavailable until a complete successful probe. A changed Box incarnation also fails readiness. Never route public writes based only on liveness. Archive pressure, permanent SMTP rejection, missing telemetry, disk/inode pressure and sustained memory pressure must fail private readiness and pause public mutation admission. Current archive status/markers are local and not yet wired into Candybox/admin readiness; do not advertise that integration.

Exactly three bookies with E=3 have no spare. Existing-ledger ACK quorum does not prove new-ledger creation, replacement ensembles, compaction, handover or repair will work with a whole host lost. Auto-recovery is enabled in the template, but repair requires suitable writable capacity. Plan maintenance downtime/read-only periods; never reduce replication silently. Lose each whole VM in an isolated environment and record which existing reads/writes/new ledgers/recovery paths remain usable. Test ZK quorum loss, network interruption and ENOSPC simulation without risking production disks. Preserve failure logs and explain unexplained loss.

To stop: remove ingress, wait for workers to drain (20s service wait; ongoing backend mutation may still finish), stop HTTP/admin, owners, bookies, then ZK. Start in reverse prerequisite order with readiness checks. Restore a lost host only after verifying disk/cluster identity and repair status; never reformat a surviving directory as a restart fix.

## Namespace maintenance and clear

`JELLY_ZK_CONNECT`, `JELLY_BOX` and `JELLY_PARTITIONS` configure private discovery and the fixed managed Box. Protect optional `JELLY_ZK_AUTH_SCHEME`/`JELLY_ZK_AUTH_CREDENTIALS` in the same host env file; never give them to browser clients. Startup needs managed-Box creation/probe permissions; sweeping needs native ADMIN permission. No per-dataset Box or formatting is performed.

One sweeper runs per HTTP process. Each tick enumerates at most four identities, processes one identity with authoritative HEAD/conditional claim, and issues at most one 100-item cleanup page. It never deletes from a stale claim. Multiple sweepers can duplicate safe work. Local bounded scan cursors are not an atomic global snapshot; restart scans from the beginning and retired identities stay in the scan. An uncertain namespace WAL/publication failure poisons that owner view; drain/recover the affected partition using upstream procedures before resuming writes, rather than resetting it or replaying an ambiguous PUT. Monitor visit/failure/cleanup counters; scan cost grows with all retained identities.

REST clear runs at most 32 pages of 100 keys with an eight-second budget between calls. 503 `clear_incomplete` means some deletion may have completed; repeat clear to remove the rest. A current RPC may outlast that budget. A timeout does not roll back completed batches. Reads/deletes/clear do not renew; committed PUT does, even if its acknowledgment is lost. HTTP and backend owners use separate clocks; only owner lifecycle responses are authoritative.

## Email archive

`tools/archive.py` uses TLS SMTP submission (587 candidate), not port 25/public mail server. Sending is explicit, credentials in protected host environment. Before launch confirm route/provider automated-attachment policies, sender/recipient limits, MIME expansion, daily quotas and mailbox retention. Attachments only; no Drive links. No real email was sent in development.

Collector commits immutable gzip segments then journal cursor. Batch publishes immutable `.eml`, manifest and pending state atomically before deleting source segments. A crash may replay journal events; retrieval deduplicates journal cursor. Sequence reservation before file creation can leave an explicit gap. A crash leaving an incomplete hidden batch directory retains source segments and consumes disk; inspect/delete only that unsent staging directory after confirming source checksums, never pending published messages.

Sender persists attempts/retry timing before connecting. Stable batch/Message-ID and message bytes survive retries. Temporary/unknown failure retains pending data; disconnect after DATA can mean duplicate delivery. Permanent 5xx rejection parks the batch for operator action. `status` exposes pending/unbatched bytes, oldest pending, last acceptance, retries/permanent/uncertain counts and pressure/gap markers. No IMAP integration: mailbox verification is **unavailable**, never verified. SMTP acceptance is weaker than delivery.

Run `archive.py bounce BATCH_ID` after a later bounce: while retained it requeues the same immutable content and marks local pressure; after local expiry it records suspected gap. Correct the account/quota before retry. Do not create an alert-email loop. Monitor status locally and through private operator access because SMTP failure can also break email alerts.

Accepted batches expire after 24h with `accepted_unverified_safety_window_expired` evidence in retention.jsonl. Pending mail is never silently pruned. At 256 MiB total spool cap collection/batching refuse growth and write a pressure marker. Current implementation lacks separate enforceable accepted/pending/critical reserves and automatic ingress pressure wiring: launch work. Retain early local alerts, stop traffic before journal/spool/bookies run out of space; infrastructure keeps emitting while public traffic is stopped. Host disk loss destroys unsent/unverified local data; log it as an unrecoverable range, not normal namespace expiration.

Retrieval from operator-exported mail:

```
python3 tools/archive.py retrieve exports/*.eml --output restored --service jellydb.service
python3 tools/archive.py retrieve exports/logs.mbox --output restored --start-micros 0 --end-micros 9999999999999999
scripts/diagnostic-bundle.sh restored incident.tar.gz
```

Tool accepts expected gzip+JSON attachments only, validates SHA-256/length/version/identity, deduplicates batches/cursors, rejects path-like names, checks bounded decompression and reports internal sequence gaps. Leading/trailing missing batches need an expected inventory; no exported mailbox alone can prove it is complete. Keep a service window as JSONL with multiline messages intact. Local archive is operator-controlled, not another automatic off-host service. Rotate export chunks; current retrieval holds selected events in memory and mbox chunks are capped at 512 MiB. Do not feed unlimited exports.

## Upgrade / incidents

Managed Box descriptor v3/new namespace opcodes require stopped-cluster owner/client upgrade; old servers must not open managed Boxes. The permanent retired lifecycle locators preserve address history and consume capacity. Forward owner-clock jumps may expire data early; synchronize clocks and preserve recovery skew checks.

Before upgrade record build/config hashes and compatible Candybox record versions, archive pending state and prepare exact prior artifacts. Do not promise mixed-version rolling upgrades. Drain and stop writers for incompatible changes; roll back binaries only when old readers can decode all new records. No data-rewinding "rollback" that silently loses acknowledged writes. Keep v1 envelopes readable.

For incidents retrieve a narrow UTC window, validate manifests, capture versions/RSS/disk/inodes and explicitly redacted config. Upstream raw-key logging needs review before sharing. Request IDs do not traverse Candybox wire; use partition/ledger/timing evidence without claiming perfect traces. Without payloads, logs cannot reconstruct user values or prove their contents. Reproduce with synthetic keys/strings, fixed seed and deterministic pauses at the observed failure boundary; include owner/ledger IDs, acknowledged operations, expected/actual outcomes and minimal upstream test. Never classify unexplained write loss as disposable expiration.

## Launch gates

- [x] Upstream atomic lifecycle primitive merged (#195), exact pin updated, production adapter integrated; deterministic upstream lifecycle tests and downstream real-storage renewal/handover regressions executed. Whole-VM/crash matrix remains below.
- [x] Native lifecycle-aware paginated cleanup and bounded scanner integrated; real stale-claim/cleanup refusal, repeated bounded clear, retirement and non-resurrection tested. Process-kill cleanup interruptions remain part of the fault matrix.
- [ ] Full three-host runtime/admin/dashboard build/install exercised with private bindings/firewall and readiness.
- [ ] 1 GiB RAM idle/load/compaction/recovery total RSS and disk/CPU/egress budget demonstrated; 512 MiB remains optional unproven experiment.
- [ ] Physical Candybox and BookKeeper reclamation measured, including a tiny long-lived value pinning old Syrup data.
- [ ] Whole-VM outage on each host, ZK quorum loss, network faults, disk/inode pressure, maintenance and recovery characterized.
- [ ] Real HTTPS browser CORS CRUD, null origin and every proxy/error class verified.
- [ ] Complete baseline log inventory, pressure/rejection aggregation, archive reserves and private failure warning integrated.
- [ ] Provider quota/MIME/mailbox capacity validated; actual mailbox delivery/export/retrieval drill separately authorized and completed.
- [ ] Seeded mixed open-loop load and 24-hour soak with streaming bounded harness results and all host metrics recorded.
