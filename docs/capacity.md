# Capacity and budget model

No VM size, SLA or public-launch capacity is demonstrated. The exact three-host profile, full stack, whole-VM failures and 24-hour soak have not been run here. 512 MiB is an unproven experiment; start with three 1 GiB/1 vCPU hosts. The native lifecycle adapter is integrated and locally tested; this does not establish a deployed production capacity envelope.

## Per-host planning estimates (MiB, not measured RSS)

| Component | Heap cap | Direct cap | Other native/stacks estimate |
| --- | ---: | ---: | ---: |
| ZooKeeper 3.9.2 | 96 | uncharacterized | 40–80 |
| BookKeeper 4.17.1 | 160 | 64 | 60–120 |
| Candybox owner | 160 | 48 | 50–100 |
| HTTP service | 128 | 32 | 40–80 |
| Admin (host 1 only) | 96 | 24 | 40–80 |
| TLS proxy, Python shipper | — | — | 30–80 |
| OS/page cache reserve | — | — | 150–250 |

Worst-case estimate on admin host exceeds 1 GiB before recovery/compaction headroom. Heap caps are experimental knobs, not evidence of feasibility. Native-memory caps do not cover every library allocation. Do not fit the stack by lowering replication, removing members or relying on sustained swap. Record minimum measured RAM and reconsider VM size/budget when tests establish it. The sample units are configuration templates, not a tested complete deployment installer.

| Candidate shape per VM | Status | Disk candidate | CPU/egress |
| --- | --- | --- | --- |
| 512 MiB / 1 vCPU | Unproven; likely too small | 10–25 GiB SSD | Measure saturation; 500–1000 GiB transfer candidate |
| 1 GiB / 1 vCPU | High-risk initial measurement profile | 10–25 GiB SSD | Initial 10 requests/s cluster test, then increase |
| 2 GiB / 1 vCPU | Alternative if measured peaks fail | Provider dependent | Likely exceeds requested compute budget; reprice |

At candidate $6/VM, compute is ~$18/month. A $2 remainder must cover domain amortization, SMTP service/account capacity, taxes and overage. No paid LB/mail/object service is assumed. Check actual provider offerings, architecture and submission route at deployment time.

## Storage accounting

Approximate cluster physical growth = 2 * compressed live Syrup payload + 3 * WAL/manifest + 2 * SSTables + stale versions + BookKeeper journals/entrylogs/indexes + compaction/recovery workspace + logs/spool. E/Qw/Qa are WAL/manifest 3/3/2, SSTable/Syrup 3/2/2: three bookies does NOT mean three payload copies. Measure each term and placement imbalance. Overwrites append, logical expiry is not reclamation, whole-ledger Syrup GC can retain a ledger for one tiny live segment. BookKeeper entry-log GC adds independent delay. A 32 MiB Syrup rollover is a measurement candidate, not an optimum; compare 16/32/64 MiB without reducing safety margins.

Plan 20% free stop / 30% resume hysteresis plus an absolute recovery reserve calibrated by experiments. Reserve separate pending, accepted and critical log space; current shipper has a 256 MiB total cap and refuses growth instead of deleting pending mail. Separate reserve partitions and pressure integration remain launch work. The journal adds another 128 MiB persistent reserve; transient duplicate copies while batching count against the spool cap. Journal and spool are not duplicate indefinite archives, but their overlap must be budgeted.

## Mail model

Let R be measured raw bytes/second/host, C compressed/raw ratio. Daily compressed attachments = 86400*R*C. MIME wire approximately 1.37*attachments + message/manifest headers. Low-volume default <=24 non-empty mails/day/host, 72 cluster-wide; 5 MiB trigger can raise this. For R=100 bytes/s and C=.25 (illustrative, **not measured**): 2.16 MB/day/host attachments, ~2.96 MB MIME/day/host plus headers, ~266 MB/month cluster mailbox growth. R=1000 multiplies this by ten. One busy-hour 5 MiB batches may have a much higher send count; provider daily message/byte quotas must be checked.

Keep accepted messages locally 24 hours if disk permits; acceptance is not mailbox verification. Normal host loss can lose unsent one-hour batch interval plus collection lag and mail failures. Mailbox is archive of record: configure no-auto-delete folder/filter, monitor free space, and export periodically to operator-owned local storage. This is not automatic infinite retention.

## Measurement protocol

Capture code SHA, upstream commit, config checksum, machine/architecture, heap/direct flags and clocks. Start at 10 total requests/s, 80% reads, 20% writes, typical 1–4 KiB and 1% 1 MiB tail, fixed seed. Use `tools/workload.py` open-loop scheduling, including overwrite/delete and repeated/random base64 corpus cases. Record offered/accepted throughput, client p50/p95/p99 including schedule lag, errors/drops, all-process RSS, direct/native memory, threads/FDs, CPU, disk/inodes, live/logical/physical bytes, GC delay, recovery time and archive MIME bytes/mail counts. Queue-full harness drops are failures, not successful throughput. The harness streams per-request JSONL, keeps at most 64 outstanding requests and a fixed ~1% latency histogram; run `--seconds 86400` only after validating the isolated deployment and budgeting output disk.

## Managed identity history

Candybox retains retired lifecycle locators permanently so delayed creation cannot resurrect old identities. Disk overhead is O(all addresses ever created), independent of active value TTL. Capacity-plan creation history, WAL/SSTable replication and enumeration cost; namespace expiry does not return these metadata bytes. The actual long-term overhead has not been measured on the target three-host profile. Native discovery now ships explicit Curator/ZooKeeper libraries in the HTTP artifact, so the initial placeholder-runtime footprint is obsolete; consult current verification.
