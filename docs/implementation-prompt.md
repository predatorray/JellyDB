# Implementation prompt: disposable browser storage on Candybox

Prepared from [Candybox](https://github.com/predatorray/candybox) commit `67fb5fdb523a87c15804923dbb3658f69ad2eb21`, inspected on 2026-11-07. The owner has confirmed the following requirements. This is a standalone implementation prompt for an independent agent; it does not depend on the preceding chat.

## Confirmed scope

| Decision | Requirement |
|---|---|
| Project location | A separate repository outside Candybox; Candybox remains an upstream dependency |
| Expiration | A dataset expires after seven days without a successful write; reads do not renew it |
| Log archive | Compressed email attachments only; no external object-storage service |
| npm client | Out of scope for this implementation; provide the REST contract and examples for a later client |
| Infrastructure budget | Approximately US$20/month total for around three VMs, each about US$4–6/month, 1 vCPU, 512 MiB–1 GiB RAM, 10–25 GiB SSD, and 500–1,000 GiB monthly transfer |
| Values | Arbitrary strings with documented JSON helper conventions for the future client |

Treat these as requirements, not options to reopen routinely. Mail sender/recipient credentials, domain name, and exact VM addresses are deployment configuration supplied later; use placeholders without requesting secrets in chat. If measured resource needs or a proven correctness limitation makes a requirement infeasible, explain the evidence and smallest necessary change rather than silently changing scope.

## Mission and priorities

Build a free, disposable remote key/value JSON service for frontend developers. A browser application must be able to use it directly, without its own backend, signup, account, API key, or manual cleanup. Allow requests from every origin. Anyone who knows a dataset address may read, overwrite, or delete its contents, regardless of who created it. Namespace identifiers are addresses, not credentials or an authorization boundary.

The service is also a real-world exercise of Candybox. The highest priority is obtaining reliable operational evidence that helps improve Candybox: failures, handovers, recovery behavior, latency, storage amplification, and unexpected interactions. User data is disposable, but successful operations must have accurately documented semantics. Never hide acknowledged-write loss as normal expiration, silently convert backend failures into missing values, or suppress reproducible Candybox failures.

Keep the implementation small enough for one maintainer. Use Java and a lightweight HTTP framework, preferably Netty because Candybox already uses it. Deploy on approximately three inexpensive VMs. Each VM hosts an HTTP service, a Candybox owner node, a BookKeeper bookie, and a ZooKeeper ensemble member. Provide Candybox's existing admin/dashboard privately; a single admin instance is sufficient initially. Do not introduce Kubernetes, Redis, Kafka, a relational database, Elasticsearch, or an authentication platform for this MVP. No query language, secondary indexes, realtime subscriptions, transactions across keys, or billing system.

“No encryption required” means no service-level end-to-end encryption or encrypted-at-rest feature is necessary. Public HTTPS is still required so modern HTTPS frontend applications can call the API. Explain that callers can encrypt/decrypt locally, and ciphertext normally compresses poorly. Do not claim HTTPS or random IDs make publicly writable data private.

## Work from actual Candybox source

Repository: https://github.com/predatorray/candybox . Inspected revision: `67fb5fdb523a87c15804923dbb3658f69ad2eb21`; Maven parent at that revision is `0.2.0-SNAPSHOT`. Pin an exact compatible artifact/build rather than floating on `latest` or an unqualified snapshot. Recheck these observations against the version actually used. Follow that checkout's AGENTS.md when changing it. Do not copy the entire storage engine into the service or assume unpublished artifacts exist in Maven Central.

Read these files, relative to the Candybox checkout:

- `candybox-client/src/main/java/me/predatorray/candybox/client/CandyboxClient.java` and `ClusterRouter.java`.
- `candybox-protocol/src/main/java/me/predatorray/candybox/protocol/transport/TcpTransport.java`.
- `candybox-common/src/main/java/me/predatorray/candybox/common/config/{CandyboxConfig,SizeLimits,QuorumConfig}.java`.
- `candybox-lsm/src/main/java/me/predatorray/candybox/lsm/syrup/{SyrupManager,SyrupReader}.java`.
- `candybox-server/src/main/java/me/predatorray/candybox/server/{GarbageCollector,PartitionOwnership}.java` and the Box-global retention implementation.
- `candybox-s3-gateway/src/main/java/me/predatorray/candybox/s3/{S3GatewayServer,CandyboxClientStore}.java` for existing integration patterns, not as a mandatory dependency.
- `candybox-dist/src/conf/logback.xml`, `docker-compose.yml`, `OPERATIONS.md`, and `DESIGN.md`.

Verified capabilities and constraints:

1. Use the native Java `CandyboxClient`, preferably its cluster-aware constructor `(Transport, CoordinationService, CandyboxConfig)`. It resolves owners through coordination and handles owner moves. Do not put the S3 gateway in the critical path without a concrete reason. Do not access BookKeeper directly from application logic.
2. Available operations include `createBox(name, partitionCount)`, `putCandy(box, key, byte[], contentType, metadata, idempotencyToken)`, `getCandy`, `headCandy`, `deleteCandy`, paginated `listCandies`, `deleteRangeByPrefix`, and `deleteBox(name, force)`. The narrower `BoxClient` interface does not expose all required operations; define a small application storage adapter and a handwritten test fake.
3. `getCandy` returns bytes; `headCandy` separately returns metadata. Separate calls are not an atomic value-plus-metadata read. Store any metadata required to interpret a value in the same versioned payload as that value.
4. The client is synchronous and buffers object bodies, including its stream overloads. `TcpTransport` serializes calls per connection. Keep client calls and compression off Netty event loops. Bound workers, queues, connections, buffers, and request deadlines; benchmark whether a small client pool is necessary. Default TCP read timeout is 120 seconds, so explicitly align transport, routing, service, and proxy timeouts.
5. Each Box has a fixed partition count; default is eight. Each partition has an engine and persistent coordination/ledger overhead. Avoid a Box per anonymous dataset unless measurements prove that cost acceptable. Listing scatters across partitions; range deletion fans out and is not a transaction across all partitions.
6. Defaults include 1 KiB Candy keys, 8 KiB user metadata, and a protocol frame limit of 16 MiB plus 64 KiB overhead. The default object-size setting itself is unlimited. Set smaller, explicit service limits before allocating bodies. Account for encoded keys and the storage envelope.
7. No general-purpose payload compression or object TTL was found in the inspected source. Multipart-upload expiration is not ordinary object expiration. Implement those service features explicitly; do not invent native Candybox capabilities.
8. Overwrites and deletes append new state. Syrup GC reclaims whole ledgers only after all segments are dead; there is no general live-segment defragmentation. The default rollover is 1 GiB. Logical expiration does not immediately return disk space. Long-lived and expired values sharing a Syrup can retain substantial garbage.
9. Default quorums `(ensemble/write/ack)` are WAL and manifest `3/3/2`, SSTable and Syrup `3/2/2`. Payload replication is therefore not uniformly three copies. Include BookKeeper entry logs, journals, indexes, Candybox WAL/LSM metadata, stale versions, and compaction workspace in disk accounting.
10. The bundled Compose file is a local example with one ZooKeeper, not a production three-host ensemble. Some defaults in the bare config builder disable maintenance or balancing; explicitly enable and verify them in production. Existing distribution logging writes stdout and suppresses BookKeeper/ZooKeeper/Curator below WARN; revise this deliberately for diagnostic coverage.
11. Do not assume general object conditional writes, compare-and-delete, cross-key transactions, exactly-once effects, or end-to-end trace propagation. Verify any capability before designing around it. Coordination CAS is not a transaction with an object write.

## Specification and delivery workflow

Produce a concise requirements document with acceptance criteria, API/OpenAPI specification, architecture decisions, capacity model, failure semantics, and ordered implementation tasks. Then implement and test the agreed scope. Keep unresolved business choices visible. Deliver runnable code, reproducible builds, deployment assets, examples, and an operations runbook, not only a design.

Keep upstream changes small and separately reviewable. If a Candybox issue blocks safe operation, produce a minimal reproducer and describe the required upstream fix. Do not quietly add a second database or claim a workaround proves the storage engine correct. No actual production provisioning, paid resource creation, email sending, or public deployment is authorized merely by this prompt.

## Public REST contract

Use a versioned API such as `/v1`. Generate at least 128 bits of random namespace identity. Do not publish a global dataset listing, and do not advertise unguessability as access control. Include a short plain-language statement that this is public, temporary, experimental storage.

Proposed endpoints, to formalize in OpenAPI:

| Operation | Endpoint | Expected behavior |
|---|---|---|
| Create namespace | `POST /v1/namespaces` | Return ID, URL, expiration policy, and applicable limits; no account or key |
| Inspect namespace | `GET /v1/namespaces/{id}` | Return lifecycle metadata without enumerating other namespaces |
| Retrieve | `GET /v1/namespaces/{id}/items/{encodedKey}` | Return the stored value; missing/expired value is 404 |
| Overwrite/create | `PUT /v1/namespaces/{id}/items/{encodedKey}` | Replace exactly one value; success only after backend acknowledgment |
| Remove | `DELETE /v1/namespaces/{id}/items/{encodedKey}` | Idempotent removal |
| List keys | `GET /v1/namespaces/{id}/keys?cursor=...&limit=...` | Bounded, paginated namespace-local listing |
| Clear | `DELETE /v1/namespaces/{id}/items` | Remove namespace contents; describe partial-failure and concurrent-write semantics |

Use an unambiguous JSON transport envelope, e.g. `{"value":"a string"}`, to distinguish a missing item, an empty string, and the string `"null"`. JSON helpers must distinguish a missing item from a stored JSON null and document JavaScript number precision; the server stores the string without interpreting its embedded JSON. Reject malformed input, unsupported encodings/media types, excessive nesting, oversized bodies, and ambiguous duplicate envelope fields. Preserve string contents; do not silently normalize or minify strings containing JSON.

Specify reversible key encoding, including empty keys, Unicode, slashes, percent signs, and reserved delimiters; account for proxy path normalization. Use a query parameter or explicit sentinel if empty keys cannot fit the chosen route. Never concatenate unchecked user input into backend prefixes. Enforce namespace boundaries in every operation and cursor. Bound key length in encoded UTF-8 bytes.

Return consistent JSON errors with code and request ID. Distinguish validation errors, missing data, throttling (429 with `Retry-After`), and backend/capacity unavailability (503 with retry guidance). Return 413 for oversized values. Never return an empty collection for a failed backend list. Use `Cache-Control: no-store` initially, including missing/error responses. Do not return internal stack traces to callers.

CORS must allow every origin, including browser contexts with an opaque/null origin, without cookies or credentials: `Access-Control-Allow-Origin: *`; support OPTIONS and GET/PUT/POST/DELETE. Allow the headers used by the browser examples and future client contract, expose request ID, expiration, and retry headers, and attach CORS headers to errors, rate limits, and proxy-generated responses. Do not use origin or referrer as authentication. Test real cross-origin browser requests.

Document per-key overwrite ordering using Candybox's actual owner/HLC behavior. Concurrent overwrites are last-writer-wins according to the backend, not client wall-clock order. A timeout can mean a mutation committed but its response was lost. Do not automatically replay ambiguous mutations as if retrying could never overwrite a newer write. If using idempotency tokens, verify their scope, persistence, and retry window first. Listing and multi-partition clear must not be described as snapshots or atomic operations without implementation evidence.

## Future TypeScript client contract — no npm implementation now

Implement the REST service only. Provide small browser `fetch` examples and document how a later client can expose asynchronous `getItem(key): Promise<string | null>`, `setItem(key, value): Promise<void>`, `removeItem(key): Promise<void>`, `clear(): Promise<void>`, `key(index): Promise<string | null>`, and `length(): Promise<number>` (or clearly named equivalent). Do not build, package, or publish an npm library in this project.

Specify string coercion for the future wrapper and JSON convenience conventions: `setJSON` serializes with `JSON.stringify`, `getJSON` parses with `JSON.parse`, and missing items must be distinguishable from stored JSON null. Show simple standalone example functions, including the behavior for serialization/parse errors; these examples are not a supported SDK. Counting/indexing may require paginated reads and is not a stable snapshot under concurrent changes.

This is familiar Storage-shaped API design, not a synchronous drop-in replacement for `window.localStorage`. Examples must use `await`. A future session-style helper can retain the namespace ID in browser `sessionStorage`; a persistent helper can use `localStorage`. Closing a tab cannot reliably delete remote state; server expiration performs cleanup. Do not promise native `storage` events, automatic origin isolation, synchronous getters, or encryption. Include a minimal static browser example, not a frontend application build pipeline, and explain how callers can store their own encrypted strings.

## Expiration, data layout, and reclamation

Implement namespace-wide expiration seven days after the latest successfully committed PUT in that namespace. Creation starts the initial seven-day clock. Any successful PUT renews the entire namespace, including other existing keys. Reads, listings, rejected/failed writes, item DELETE, and clear do not renew it. These operation details are explicit specification defaults interpreting the owner's dataset idle-expiration choice. A committed PUT whose acknowledgment is lost still counts as a write; response delivery cannot define a durable expiration timestamp. Expose authoritative expiration metadata and specify the boundary behavior for a write racing expiry. Once expired, a namespace ID must not be resurrected; create a new one. Do not add an undisclosed hard maximum lifetime or evict unexpired datasets to satisfy capacity limits.

Require a race analysis before selecting the layout. A worker that reads an old expiration and then deletes an overwritten key can destroy fresh data. A lease around only the sweeper does not prevent concurrent writers. A metadata update plus an object write is not atomic. Handle restart, partial commits, stale workers, delayed requests, and creation-versus-expiration races across three HTTP servers. Never rely on an in-process lock for distributed correctness.

Compare a small fixed set of shared Boxes against bounded time-cohort Boxes/prefixes, with immutable generation identifiers or another simple proven reclamation scheme. Cohorts can help whole-ledger reclamation but may complicate renewable expiration and routing. Do not allocate unbounded Boxes, ZooKeeper nodes, per-namespace scheduled tasks, or timer objects. Keep the renewal protocol small; avoid introducing a general transactional metadata system to implement expiration. Fixed-lifetime namespaces are not an authorized substitute for the selected renewable seven-day lifetime. If safe renewal needs a small upstream primitive, document the race and propose that precise change before making an unsupported guarantee.

Expiration must be enforced at read/list time even if physical cleanup lags. Cleanup must be resumable, paginated, bounded, observable, and safe when repeated or interrupted. Do not delete fresh values, resurrect expired generations, or allow clients to bypass expiry by fabricating IDs. Include actual physical reclamation in integration/soak tests. Evaluate a smaller Syrup rollover (for example 16–64 MiB as an experiment, not an asserted optimum), ledger overhead, and long-lived values pinning old space. Measure BookKeeper's own reclamation delay as well as Candybox GC. Never lower fencing or tombstone safety margins merely to make a disk chart look better.

## Compression and memory

Implement an application-owned versioned binary envelope containing magic/version, codec, uncompressed length, value representation, and any inseparable lifecycle metadata. Include integrity validation and reject unsupported/corrupt formats without treating them as missing. Keep older stored formats readable during supported upgrades.

Start with JDK gzip/DEFLATE to avoid a native dependency. Compress sufficiently large values only when the saved bytes exceed header overhead; leave tiny or incompressible values uncompressed. Compare a small representative corpus against Zstandard only if measured disk savings justify added artifact size, CPU, native packaging, and ARM64 complexity. Do not double-compress already compressed envelopes.

Bound input and decompressed output lengths, parser nesting, concurrent compression jobs, and peak in-flight memory. Enforce logical size limits before compression so highly compressible giant input cannot evade limits. Prefer rejecting compressed request bodies in v1 unless safely bounded decoding is implemented. HTTP response compression is separate from storage compression. Record compression ratio, CPU cost, and logical-versus-physical byte estimates without logging payloads.

## Abuse controls without onboarding friction

Start with a few generous, configurable controls; these are tuning candidates, not promises of unlimited service:

- A 1 MiB decoded value limit and 256-byte user-key limit, including an explicit wire-envelope allowance.
- Bounded page sizes, connections, header sizes, idle timeouts, worker queues, compression concurrency, and response sizes.
- Per-source-IP token buckets with separate read, write, and namespace-creation budgets; candidate baseline 60 reads/second, 10 writes/second, bursts of twice those values, and 30 namespace creations/hour. Validate against shared-NAT developers and VM capacity before setting production defaults.
- A global admitted-byte/write-rate budget and disk-pressure shedding. Rate limiting requests alone does not constrain bandwidth or stored bytes.
- Bounded limiter state with expiration; understand that rotating IPv6 addresses and distributed clients bypass per-IP limits. Trust forwarding headers only from configured proxies.

Explain that three independent in-memory limiters allow approximately three times a per-node budget, potentially more across restarts. Configure conservative per-node shares, but do not claim a strict global quota. Avoid adding Redis for exact accounting. Prefer soft per-namespace pressure controls only if they can be implemented cheaply and correctly; do not build expensive full-dataset scans into writes.

Use measured disk watermarks with hysteresis, fresh capacity signals, and a reserved diagnostic/maintenance budget. Proposed warning/stop-write/resume points might be 70%/80%/65%, but must be chosen below BookKeeper's actual read-only/disk thresholds and with enough compaction headroom. Include absolute free-byte checks. At pressure, throttle new namespaces and writes, continue safe reads, and run bounded normal cleanup. Deletes also append tombstones and consume disk; reserve room for them. Do not use request-path scans or random deletion of still-valid data. Define behavior if capacity telemetry is unavailable.

No CAPTCHA, login, mandatory email, origin allowlist, or permanent per-user quota by default. Include operator kill switches and a simple abuse-contact mechanism. Be honest that an unauthenticated free service cannot completely prevent abuse, and protecting availability takes precedence over admitting every request.

## Three-VM deployment and footprint

Produce one understandable deployment path, preferably systemd units and reproducible scripts with shared installed runtime/libraries, or a small Compose-per-host setup if measurements justify it. Do not pretend Compose automatically provides cross-host networking or discovery. Supply explicit private advertised addresses and a three-member ZooKeeper configuration with persistent identities. Keep data, config, logs, and binaries in separate directories; never run destructive cluster formatting on routine restart.

Build off-host. Ship runtime artifacts only: no Maven cache, source tree, Node toolchain, frontend build cache, duplicate full distributions, or compiler image. Build the existing dashboard once and serve its static assets through Candybox's admin API. Reuse libraries/runtime where practical and measure compressed download size and installed footprint. Evaluate jlink only if all required modules, service providers, TLS, and diagnostics remain usable; do not add native-image complexity by default. Pin dependencies and images; inspect transitive dependencies rather than describing the client as dependency-free. Test ARM64 explicitly if considering ARM-based VMs.

Start resource measurements with three 1 GiB VMs (approximately US$18/month compute at the owner’s candidate pricing); treat 512 MiB as an unproven lower-bound experiment. Include domain, mail sending, taxes, transfer overage, and any other recurring costs in the approximately US$20 budget. Do not add paid object storage, a load balancer, or an email subscription implicitly. The full multi-JVM stack on 1 GiB is a high-risk feasibility target, not an established sizing result; if it fails, report minimum measured resources and alternatives without removing required cluster members. Swap must not be presented as a fix for sustained memory pressure or GC pauses.

Public ingress is HTTPS on the service only. Provide a small TLS reverse proxy if useful, certificate renewal, and a documented way clients reach surviving HTTP instances. DNS round-robin alone is not reliable per-request failover; describe its limitations and avoid introducing a paid load balancer without budgeting it. ZooKeeper, BookKeeper, Candybox TCP, metrics, and the admin UI remain on private/firewalled interfaces. Public API anonymity does not authorize public cluster administration. Use SSH tunneling or private operator access to the dashboard.

Budget every JVM/process: heap, direct buffers, native memory, threads/stacks, page cache, and OS reserve. Measure total RSS under steady load, compaction, and recovery. Do not assert the smallest VPS plan can fit multiple Java services without evidence. Provide a capacity table for candidate VM shapes, installed bytes, peak RAM, expected physical data growth, log spool, CPU, and egress; flag any budget that cannot support the topology. Leave provider price/availability checks to deployment time rather than hard-coding free-tier promises.

With exactly three bookies and ensemble size three there is no spare. Existing ledger behavior, replacement ensembles, new ledger allocation, recovery, and rolling maintenance can have different availability constraints. Do not equate majority acknowledgments with continued operation after one VM fails. Test loss of each whole VM, not only a Candybox process. Define degraded behavior and maintenance downtime. Never silently reduce replication to fit the budget. Include BookKeeper recovery/repair configuration and the fact that repair requires suitable writable capacity.

Include clock synchronization, service ordering and restart backoff, file descriptor limits, graceful draining, readiness checks, secure archive credentials, and upgrade/rollback instructions tied to actual Candybox record compatibility. Do not promise mixed-version rolling upgrades when the pinned upstream release cannot support them.

## Operational evidence and off-site history

Retain the complete emitted baseline operational log stream from the HTTP service, each Candybox owner, each bookie, each ZooKeeper member, admin API, proxy, log shipper, and service supervisor. Include startup, shutdown, membership, lease/fencing, ownership changes, recovery, compaction, GC, maintenance failures, resource pressure, and full error cause chains. “Full history” means emitted diagnostic events, not every object body or an impossible perfect trace of every internal instruction.

Use INFO for useful lifecycle events and WARN/ERROR for failures. Audit chatty upstream logger categories rather than leaving blanket WARN suppression or enabling global DEBUG forever. Allow targeted, time-bounded debug logging. Do not sample away errors or important lifecycle events. Log one compact completed-request record per admitted request and aggregate rejected abuse bursts with counts and time windows so logging cannot amplify an attack without bound.

Include UTC time, host/service/build identity, boot ID, event sequence where available, request ID, operation, duration, result, and relevant partition/ledger identifiers. Do not log values, ciphertext bodies, credentials, cookies, or arbitrary unescaped user text. Use bounded/hashes of namespace/key identifiers where useful, and document any upstream logging that still contains raw keys. Request IDs are not automatically propagated through Candybox's wire protocol: identify the correlation gap and either add a small optional upstream change or rely explicitly on timing/operation/partition evidence. Do not claim trace continuity that does not exist.

Use compressed email attachments as the sole off-host archive transport, as explicitly selected by the owner. Do not implement object-storage uploads, cloud-drive attachment links, or a log analytics stack. Use one small durable per-host spool and sender, shared across local services. Use an existing authenticated SMTP submission service over TLS; do not operate a public mail server. Verify the selected VPS permits the submission route and that the mail account permits automated log attachments. Do not assume port 25 is usable.

Rotate local segments every five minutes or 4 MiB of raw text, whichever occurs first; this controls crash recovery and local file size, not email frequency. Batch immutable compressed segments into one mail per host at most hourly under low volume, or sooner when the total compressed attachment payload reaches a configurable 5 MiB. Bound the final MIME message size, including roughly one-third base64 expansion plus headers. Split high-volume batches safely; never truncate a stack trace silently. Choose the real limits as the minimum of sender and recipient limits. For context, Gmail documents a 25 MB personal attachment limit and can replace oversized attachments with Drive links; that behavior is unacceptable for this email-only archive. The 5 MiB candidate is a conservative starting point, not a guarantee every provider will accept it.

An hourly schedule across three hosts can generate 72 non-empty messages/day; calculate projected mail count, MIME bytes/day, mailbox growth/month, sender limits, and transfer usage from measured log rates before launch. Send nothing for empty batches. Size-triggered sends can increase that count substantially. Make batching configurable and document the tradeoff: normal host loss may lose up to the unsent batch interval (about one hour by default), plus any mail transit failure. If the provider quota cannot carry the stream, apply traffic pressure controls and report the capacity limit; do not silently sample important logs or shorten retention.

Each message must have a stable Message-ID and batch ID. Each attachment must have host/service/date/boot/sequence identity, byte count, time window, and SHA-256 checksum recorded in an attached manifest. Persist pending batches and retry state atomically; never regenerate different content under the same batch ID. Handle SMTP temporary failure, permanent rejection, uncertain submission after disconnect, restart, and duplicate delivery. Exactly-once email delivery is not assumed. A retrieval tool must deduplicate by batch identity and validate checksums.

Distinguish `pending`, `SMTP-accepted`, and `mailbox-verified` states. SMTP acceptance is not proof the recipient has stored the message or will retain it. Keep pending mail durably until accepted. Keep accepted batches in a bounded local safety window, initially 24 hours if the measured disk budget permits. Delete accepted batches after that window only with the weaker delivery guarantee explicitly recorded in the runbook. Optionally support read-only IMAP verification if the owner's provider/credentials permit it, but do not require an elaborate mailbox integration. Never label unverified mail as verified. A later bounce must trigger an alert and resend while the batch remains locally available; after local expiry, record a suspected gap.

Expose pending bytes, oldest pending batch, last SMTP acceptance, mailbox verification status where available, retry counts, permanent failures, and evicted/unrecoverable ranges. A local timer or systemd service is enough. SMTP credentials belong in protected host configuration, never logs or the public API. Keep attachment contents private to the configured recipient and sanitize headers. Preserve multiline stack traces and per-source order; rotate by rename/reopen or a proven collector mechanism, not copytruncate. Avoid duplicate full streams in container files, journald, and application files unless every copy is budgeted.

Finite disk and finite mailbox capacity cannot preserve an infinite full history. Define separate limits/reserves for pending mail, recently accepted mail, and critical events. Alert early, restrict incoming traffic before reserves are exhausted, and retain explicit gap markers if loss becomes unavoidable. Do not silently delete pending batches or keep accepting traffic until bookies hit ENOSPC. Halting public traffic reduces but does not stop infrastructure logs. A local health failure and private dashboard warning must expose mail outages because alerts sent through the failed SMTP path may also fail. Retain alerts locally for later delivery and avoid an alert email loop.

The mailbox is the off-site archive of record. The operator must configure a folder/filter without automatic deletion, monitor free mailbox space, and periodically export it to an operator-controlled local archive if needed. Do not claim this is automatic indefinite retention. Supply a retrieval command that accepts exported `.eml`/mbox messages, extracts only expected attachment formats safely, validates manifests/checksums, deduplicates, detects sequence gaps, and reconstructs a service/time window. Include an end-to-end delivery-and-retrieval drill with the actual configured mailbox before public launch. Normal development tests use a local SMTP sink; never send real email unless separately authorized.

Include a modest periodic metrics snapshot and redacted configuration/build metadata in the emailed batches alongside logs. Capture request latency/error rates, workers/queue saturation, heap/direct memory, disk/inodes, BookKeeper and ZooKeeper health, ownership/recovery, GC backlog, logical/live/stored bytes, compression savings, and log shipping health. Keep metric labels bounded; never label by arbitrary key, namespace, IP, or request ID. Inventory what upstream already exposes and flag missing metrics rather than inventing them. No permanent Prometheus/Grafana/Loki cluster is required for v1.

Provide a diagnostic bundle command that gathers the relevant archived time window, redacted config, versions, resource measurements, and operation IDs for an incident without exporting user payloads. Explain what can and cannot be reconstructed without data values. Include a procedure to turn findings into a minimal synthetic Candybox reproducer.

## Required verification

Use JUnit and handwritten fakes for the application adapter, plus real Candybox integration tests. Browser example tests must cover real fetch/CORS behavior; npm client implementation remains out of scope. Prioritize these acceptance cases:

1. Create, overwrite, retrieve, remove, clear, pagination, empty values/keys, Unicode, reserved characters, JSON null, and encrypted strings; isolation between namespace prefixes and tampered cursors.
2. All origins can use the API without credentials; preflight and every error class expose appropriate CORS headers; HTTPS browser demo works.
3. Compression round trips, mixed compressed/plain envelopes, version compatibility, corrupt/truncated records, decompression bounds, and excessive JSON depth.
4. Concurrent writers, lost responses, ambiguous retries, list/clear races, and backend unavailable-versus-not-found distinctions.
5. Expiration before/at/after boundaries, read-only activity, clock skew, renewal-versus-sweeper races, worker restart, partial cleanup, and stale workers. Prove fresh data survives cleanup.
6. Real physical disk reclamation after churn and expiration, including a tiny long-lived value sharing a Syrup with substantial dead data. Report lag and amplification rather than checking only logical counts.
7. Sustained abuse, huge/incomplete requests, slow clients, limiter-map churn, bounded queues, pressure hysteresis, and three-node rate-limit behavior.
8. Owner handover, process restart, whole-VM failure, ZooKeeper quorum loss, bookie unavailability, network interruption, and disk pressure using isolated test deployments. Verify acknowledgment semantics and collect useful logs for every failure.
9. SMTP outage/rejection, uncertain acceptance, sender restart, duplicate mail, recipient quota/bounce, checksum mismatch, full spool, host disk loss, and restoration from exported emails with multiline errors intact.
10. A repeatable seeded open-loop mixed workload (so queueing is not hidden by a request-at-a-time load generator) and 24-hour soak on a representative small profile, with shorter CI smoke tests. Record code/config versions, seed, read/write sizes, overwrite/delete rates, throughput, p50/p95/p99 latency, memory peaks, physical disk growth, recovery time, and archive volume. Do not claim tests ran if infrastructure/time was unavailable.

Use failures to improve Candybox visibility. Do not hide errors with unbounded retries, automatically weaken checks, or classify unexplained loss as acceptable because this is a disposable service.

## Deliverables and definition of done

Deliver source and build instructions; OpenAPI and future-SDK contract notes; a minimal browser example; versioned storage format documentation; tested expiration/reclamation design; three-host deployment assets; measured dependency/artifact/resource footprint; test and soak reports; log archiving and retrieval tools; and an operator runbook covering launch, pressure, archive outages, maintenance, upgrade, and incident reproduction.

Provide a simple launch checklist with explicit unresolved capacity or upstream blockers. Separate measured results from estimates. The system is ready for a public experimental launch only when the agreed memory/disk budget is demonstrated, cross-origin CRUD works, expiration cannot delete fresh writes, physical reclamation is characterized, and operational history can be retrieved from outside the Candybox cluster.

## Design-review findings and launch assumptions

The service is a public string store with namespace-wide idle expiration. Candybox is the source of truth for values and authoritative lifecycle state; local rate limits are advisory and the mailbox contains off-site operational history. The prompt is ready for implementation planning, but the deployment is not yet validated.

| Severity | Where | Concrete risk | Required resolution |
|---|---|---|---|
| Blocker until demonstrated | Three small VMs | Simultaneous Java heaps, native buffers, and recovery/compaction peaks exceed RAM, causing OOM kills or lease instability | Measure idle, load, recovery, and maintenance RSS on the exact profile; report a budget mismatch instead of claiming viability |
| Blocker until proven | Renewable namespace expiration | A delayed sweeper deletes keys after a successful write renewed the namespace; independent metadata/value writes lose renewal | Provide a crash/race-safe protocol and tests, or identify the smallest missing Candybox primitive |
| Major, accepted transport constraint | Email-only history | SMTP acceptance precedes recipient rejection or mailbox deletion; local safety copies later expire | Durable retry spool, bounded safety retention, retrieval drills, explicit delivery states/gaps, and mailbox-capacity monitoring |
| Major | Three-bookie topology | A whole-VM outage prevents new ledger creation or repair despite enough acknowledgments on some existing ledgers | Test and document read/write availability and maintenance downtime; do not promise full single-VM write availability |
| Major | Physical disk reclamation | A live segment pins a ledger containing expired data while continued writes exhaust SSD capacity | Measure churn/GC, tune rollover and layout, reserve maintenance space, and shed writes before storage thresholds |

No production throughput or latency SLA has been supplied. Establish an initial characterization workload of 10 total requests/second cluster-wide, 80% reads and 20% writes, typical 1–4 KiB values with a tail up to the 1 MiB limit, and burst tests above that rate. These are test assumptions, not user quotas or claims of supported capacity. Report client-observed percentiles and discover the sustainable envelope by increasing load. Keep the limits independently configurable. Public launch may include documented degraded/read-only periods; it must not advertise stronger availability than demonstrated.

The remaining mail provider, recipient, SMTP route, domain, VM region, and exact package versions are deployment inputs. Use configuration placeholders now and validate them at launch. No additional product decision is required to begin the specification and implementation work.

## Supporting references

These supplement the pinned source, not replace it:

- Browser storage is synchronous: https://developer.mozilla.org/en-US/docs/Web/API/Web_Storage_API
- Storage interface semantics: https://developer.mozilla.org/en-US/docs/Web/API/Storage
- BookKeeper ensemble allocation and insufficient-bookie failures: https://bookkeeper.apache.org/docs/latest/api/javadoc/org/apache/bookkeeper/client/EnsemblePlacementPolicy.html
- BookKeeper configuration reference (check the deployed version): https://bookkeeper.apache.org/docs/reference/config/
- Candidate VM prices, to recheck at deployment: https://www.digitalocean.com/pricing/droplets
- Gmail attachment-size behavior (illustrative; actual sender/recipient remain configurable): https://support.google.com/mail/answer/6584
