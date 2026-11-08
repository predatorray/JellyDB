# Architecture and failure semantics

JellyDB is a separate Java 17+ Maven project. Netty HTTP -> bounded workers -> `CandyboxNamespaceStore` -> cluster-aware native `CandyboxClient` -> managed Box owners. The exact upstream pin is `c6392d5130ec5b4ec5c3f63d161b449a9bc29fc3` (PR #195). Values and authoritative lifecycle state both live in Candybox; the application never opens BookKeeper directly. Handwritten fakes and the old raw adapter exist only in test sources.

## Layout and lifecycle

One fixed managed Box (`jellydb-v1`, eight partitions by default) contains random 128-bit namespace addresses. Physical keys are upstream `l/hex(address)` and `i/hex(address)/hex(key)`. A 22-byte REST address and maximum 256-byte key occupy 559 physical UTF-8 bytes, below upstream's 1024-byte key limit. Routing always uses the lifecycle key, including item operations; common prefixes alone are not assumed to co-locate ordinary Candybox keys. Public paths remain tilde + canonical base64url UTF-8, with `~` for empty keys.

Creation establishes seven-day expiry. Each committed PUT atomically stores item locator plus renewal. Reads/list/DELETE/clear never renew. Native reads return lifecycle metadata and bytes from the same owner view. Expired namespace IDs are terminal; backend failures are never converted into expiry or empty pages. See [upstream resolution](upstream-blocker.md) for the precise owner-admission time/clock assumptions. Retired lifecycle locators persist permanently, so identity history consumes disk even after value cleanup.

One shared sweeper per HTTP process runs on a single maintenance executor, with no per-namespace tasks. Each step fetches at most four lifecycle identities and processes at most one: HEAD, conditional claim if active, and one cleanup page if retired. Claims use owner time and version; STALE/NOT_DUE never authorize cleanup. Cursor state is bounded and local; restart resumes by rescanning. Cleanup itself always starts at the first remaining page, so retries/interruption are safe. Multiple hosts may duplicate work safely. Permanent identity growth increases full-scan time; no cleanup-latency SLA is claimed.

## Resource bounds and timeouts

HTTP/1 closes after one response: four active connections, two workers, queue two, one boss and one I/O thread. Headers <=8 KiB, initial line <=2 KiB, wire body <=6 MiB + 1 KiB, decoded value <=1 MiB, key <=256 UTF-8 bytes, list <=100 keys. Four wire buffers plus copies/parsing can exceed 50 MiB; aggregate RSS still needs full-stack measurement. Compression runs on bounded workers. The 15-second HTTP deadline includes incomplete bodies and queue delay. Queued mutations are skipped after that deadline; already-dispatched mutations may still commit.

TCP connect=1s, idle read=2s, absolute transport-call deadline=3s (close connection even if response bytes trickle). At most four transport calls/timers are admitted; cancelled timers are removed. A timeout may affect another request sharing that serialized owner connection and is conservatively ambiguous. No automatic mutation replay is added. Router MOVED handling remains bounded; owner-gap retry=0. Discovery uses explicit Curator connection/session bounds, no retry policy, and a five-second startup wait. DNS/coordination and multi-RPC routing have their own costs; do not describe the TCP deadline as a complete end-to-end backend operation guarantee.

Clear runs at most 32 pages of 100 keys and checks an eight-second budget between calls. It returns 503 `clear_incomplete` if work remains; partial deletion persists. A current RPC can outlast the between-call budget. Repeating clear makes progress but may also delete concurrent newer keys according to owner order. No unbounded HTTP loop or whole-namespace transaction is promised.

## Runtime and readiness

`CandyboxRuntime` owns one Curator discovery client, one native Candybox client, and its deadline transport. Startup creates one managed Box if absent, verifies type/partition count, and probes every partition. No formatting occurs. A legacy Box is rejected. Discovery authentication is optional protected host configuration; public browsers never receive those credentials. Native cluster ports/admin remain private.

`/healthz` is process liveness. `/readyz` uses discovery plus managed-owner health, refreshed every 15 seconds; backend-call/probe failures mark it unavailable until a successful full probe. Box incarnation changes fail readiness. Native failures do not trigger a fallback store. Successful probing is not a guarantee that the next operation will succeed. Archive/inode pressure integration into private readiness remains launch work.

## Admission and pressure

Advisory per-node budgets: 20 reads/s, 3.33 writes/s, 10 creations/hour with bursts; three nodes sum to roughly 60 reads/s, 10 writes/s, 30 creations/hour, potentially more after restarts. Mutation-byte budget 1 MiB/s and mutation rate 4/s per node are not strict distributed quotas or egress limits. Limiter state <=4096 sources, one-hour inactivity removal; a full map rejects new sources with 429. Socket peer IP only; forwarding headers are ignored. Proxy source-IP limiting must account for shared NAT/proxy addresses.

Disk hysteresis pauses mutations below 20% usable space and resumes above 30%; unavailable disk telemetry pauses mutations. This does not yet integrate archive/inode/upstream pressure. Reserve room for recovery, compaction, journals and critical logs; never evict unexpired data to admit writes. Stop public ingress as the operator kill switch. Configure an abuse-contact address before launch.

## Public consistency and errors

404 means authoritative namespace/item missing or expired. Missing item DELETE within a live namespace succeeds. 409 means an already-used creation address. 503 covers backend/capacity/corruption/readiness/partial-clear failure; a timeout may mean a mutation committed and renewed. No durable retry token/exactly-once claim. Candybox owner HLC order determines overwrites, not caller wall clocks. List is non-snapshot; clear is non-atomic across batches. Cursors bind namespace and last key, not credentials. All application/error responses use no-store and wildcard credential-free CORS.

## Observability and remaining evidence

Completed admitted requests log UTC identity, request ID, method/result/duration, never URI/key/value bodies. Rejections aggregate in limiter/transport counters; dedicated window/reason summaries remain work. Backend cause chains are private; audit upstream raw-key logging before sharing. Request IDs do not propagate through Candybox wire messages; correlation uses timing/operation/partition evidence.

Minute snapshots include HTTP/heap/worker/limiter state plus sweeper partition/pending/visit/cleanup/failure counters. Upstream exposes bounded-label renewal/rejected-claim/retirement/cleanup/failure counters. Full native/direct memory, disk/inode/GC/backlog and archive-pressure integration remain launch gates. Missing metrics must not be presented as zeros. No physical reclamation or three-host SLA follows from logical expiry correctness.
