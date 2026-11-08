# Requirements and implementation sequence

Source: [the supplied implementation prompt](implementation-prompt.md). This document is a project specification, not authorization to provision, publish, or send mail.

## Acceptance criteria

* Anonymous public string store, wildcard credential-free CORS on every response, versioned JSON REST; exact reversible UTF-8 keys including empty keys.
* 128-bit random namespace addresses, no global public listing; anyone with an address can modify data. HTTPS does not make data private.
* Namespace expiration is seven days after creation/latest committed PUT. GET, list, delete and clear never renew. Expired IDs never resurrect.
* Value and authoritative renewal must commit atomically under owner fencing. Cleanup must not delete a renewed namespace, even after worker pause/restart. Backend failures must never become 404 or empty lists.
* 1 MiB values, 256 UTF-8 byte keys; bounded wire/parser/decompression/connection/worker/queue/page/limiter/byte budgets. No cross-key transaction or snapshot promises.
* Versioned checksummed plain/gzip envelopes; no payload logging. No npm client, external archive storage, login or paid services.
* Durable email spool, stable immutable batches, explicit pending/SMTP-accepted state, checksum-validating retrieval; no claim of mailbox verification without evidence.
* Three-host private cluster, public TLS only; exact upstream revision and reproducible off-host builds. No destructive formatting at restart.
* Public launch requires measured RAM/recovery/reclamation, real HTTPS browser CRUD, retrieval drill, whole-VM fault tests and 24-hour soak.

## Ordered tasks and gates

1. Audit pinned Candybox, state the missing atomic lifecycle primitive and reproduce naive renewal loss.
2. Specify REST, storage format, race semantics, capacity and upstream change.
3. Implement and test HTTP/parser/codec/admission and an atomic lifecycle contract with a handwritten fake.
4. Integrate native Candybox adapter and real-storage tests. Use the managed namespace primitive merged in Candybox #195; fail readiness on missing/legacy support. A fake is not evidence of distributed correctness.
5. Implement archive collector/spool/sender/retrieval and fault tests; supply private deployment assets and browser examples.
6. Run available checks and record exact results. Supply repeatable load/soak/failure procedures; distinguish unrun launch gates.
7. Pin the merged upstream primitive, integrate native lifecycle operations and bounded cleanup, and run all launch gates. Native integration is implemented; public launch still requires deployment/soak evidence.

No unresolved business choice blocks development. SMTP provider, credentials, DNS and VM addresses remain deployment inputs, not chat secrets. Upstream atomic lifecycle support is resolved by #195; three-VM resource feasibility and the remaining operational launch gates are unproven.
