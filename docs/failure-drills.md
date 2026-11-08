# Isolated verification procedures (not execution claims)

Do not run destructive failure drills on production disks or send real mail without separate authorization. Use three disposable test VMs/private network, the same pinned artifact/config hashes, exact candidate hardware and synchronized clocks. Keep baseline logs and resource snapshots through every drill. The lifecycle primitive is now integrated; inability to start a new ledger with only two bookies may be a correctly documented availability limitation, never a reason to weaken replication.

| Drill | Schedule / observation | Pass criterion |
| --- | --- | --- |
| Lost PUT response | Commit on owner, drop TCP/HTTP response before client receives it | Value and lifecycle renewal both recover; caller sees ambiguous failure; no blind replay |
| Writer vs expiry | Pause writer before admission, before WAL append and after commit; advance owner time before/at/after expiry | Either write+renewal wins atomically or expiry rejects write; no resurrection |
| Stale sweeper | Read lifecycle version, pause, renew, resume old claim | Old claim fails; fresh values survive; repeat after sweeper/owner restart |
| Partial cleanup | Retire namespace, delete first page, kill worker, restart with saved progress | Bounded idempotent cleanup resumes; no active namespace touched |
| Owner handover | Write acknowledged corpus with recorded checksums, release/crash each owner | Recovered values/checksums and authoritative expiry match acknowledged results |
| Whole VM loss | Stop each of three entire hosts in turn, preserving remaining disks | Characterize reads/existing-ledger writes/new ledgers/repair; no unsupported single-host write-availability claim |
| ZK quorum loss | Isolate two ensemble members | Owners fence/reject writes safely; failures distinguish unavailable from missing |
| Backend trickle | Test transport sends frame bytes below socket read-timeout pace | Hard request/RPC bounds hold or production gate remains closed; do not call per-read timeout a deadline |
| Pressure | Fill a dedicated small test volume / inode budget to hysteresis thresholds | Mutations stop before reserves; reads/metrics/log warnings remain; no random live eviction |
| Abuse | Open incomplete bodies/headers, churn IPv6 source limiter, flood large JSON and invalid envelopes | Connections/queue/memory/limiter map stay bounded; 429/503 have CORS/retry; rejected bursts aggregate |
| Physical reclamation | Seed fixed corpus, keep one tiny long-lived value, overwrite/delete/expire large corpus in same Syrups | Record actual BK entrylog/journal/index and Candybox live/dead bytes until reclamation stabilizes; report pinned space and lag |
| Archive crash | Kill collector before/after segment fsync/cursor advance; kill sender before/after DATA acknowledgment | Immutable identity; safe replay/dedup; pending retained; uncertainty and sequence gaps explicit |
| Mail outage | Loopback sink 4xx/5xx/disconnect, then restart sender | Bounded backoff; parked permanent rejection; stable Message-ID/content; local warning independent of SMTP |
| Mailbox bounce | Actual provider test only after authorization; bounce/quota after SMTP acceptance | Requeue while retained, suspected-gap marker after expiry; never label acceptance as verified |
| Host disk loss | Destroy disposable host spool after copying only exported mailbox | Reconstruct available service window and explicitly identify unrecoverable unsent range |

Physical reclamation must run beyond default safety windows (tombstone grace 24h plus actual maintenance/BookKeeper lag). Do not shorten fencing/tombstone retention to fit a short CI test. Compare Syrup rollover 16/32/64 MiB and entrylog sizes, recording ledger/coordination overhead and long-lived pinning. Read/list-time logical expiry alone is not reclamation evidence.

Start open-loop at 10 requests/s, increase to discover overload; repeat the seed with compressible and base64 random strings and 1 MiB tail. `tools/workload.py` streams bounded per-request results, mixes overwrites/deletes, records percentile upper bounds and client scheduling lag. For 24 hours invoke `--seconds 86400`, with host snapshots, GC/live-byte metrics and archive MIME/mail-volume collection in parallel. Fail CI smoke on nonzero errors/drops; missing GETs after mixed deletes are recorded separately, and a dedicated acknowledged-write verifier must track expected per-key order before interpreting any 404 as expected. The current harness alone is not that verifier.

Every failure report needs versions/config/seed, operation IDs/times, acknowledged vs ambiguous outcomes, partition/ledger/owner identifiers, recovery time, before/after disk/RSS, and a small synthetic reproducer. Record unexplained loss as a bug.
