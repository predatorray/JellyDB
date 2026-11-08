# Renewable expiration: upstream blocker resolved

Candybox [issue #194](https://github.com/predatorray/candybox/issues/194) is implemented by [PR #195](https://github.com/predatorray/candybox/pull/195), merged as **`c6392d5130ec5b4ec5c3f63d161b449a9bc29fc3`**. JellyDB pins this exact source revision, builds it as `0.2.0-jelly-c6392d5`, and uses its native managed namespace operations. Upstream source is unchanged apart from build POM version substitution.

## What changed

One shared managed Box holds many namespaces. Every operation for a namespace routes using its lifecycle key to one fenced owner partition. `putNamespaceItem` commits the item locator and seven-day renewal in one replay-atomic WAL batch, publishing both under the owner lock. Separate GET/HEAD interpretation is unnecessary: native reads return bytes and lifecycle state from the same locked view.

Expiry claims compare lifecycle versions under that same lock. If a PUT renewed first, the old claim returns STALE and cannot authorize cleanup. If retirement won first, a delayed PUT returns EXPIRED. Reads observing expiry durably retire the identity before reporting EXPIRED; a backend failure is an error, never missing. Cleanup is bounded, permitted only after retirement, and restarts from the first remaining page.

Retired identities remain live payload-free lifecycle locators permanently, rather than tombstones that compaction can discard. Delayed creation cannot reuse them. This costs O(all identities ever created) disk space. Deleting/recreating the managed Box is explicit destructive administration outside these guarantees; JellyDB detects a changed incarnation on its readiness probe.

## Actual time semantics

Candybox uses max(nonnegative owner wall time, recovered/local HLC physical high-water). Renewal time is owner admission, not HTTP response delivery or trusted UTC. At deadline equality, operations observe expiry and persist retirement. Backward clocks cannot undo durable retirement. Unobserved wall time passing is not itself a committed expiry event. A forward clock jump can irreversibly expire data early, and excessive skew during recovery fails closed. Keep clocks synchronized; seven real days relies on clock accuracy. Do not claim HLC alone proves real-time expiration.

## JellyDB integration

`CandyboxNamespaceStore` maps OK, missing/expired, conflicts and backend errors explicitly. PUT always uses the owner primitive; generic `putCandy`/range-delete is not exposed in production. REST key cursors are converted to validated namespace-scoped native cursors. Clear runs at most 32 pages (100 keys/page) and an eight-second between-call budget; a larger/slow clear returns 503 `clear_incomplete` after possible partial progress. Repeating clear removes remaining items, and concurrent writes remain subject to backend ordering.

One shared `NamespaceSweeper` per HTTP process scans partitions, enumerating at most four identities and processing one identity per tick. It checks/claims using backend time, never the HTTP clock, and runs at most one cleanup page per visit. A stale claim never calls cleanup. Restart rescans safely; multiple hosts may duplicate work safely. No per-namespace timers, Boxes, ZooKeeper nodes or heap maps are allocated.

Startup verifies that `JELLY_BOX` is managed and has the configured fixed partition count. Existing legacy Boxes are rejected, never silently migrated. `/readyz` reflects periodic owner probes, discovery and failures. Missing backend support is now an actionable startup/probe failure rather than a permanent placeholder store.

## Historical counterexample and evidence limits

At the former pin `67fb5fdb523a87c15804923dbb3658f69ad2eb21`, a sweeper could read old expiry, pause, then delete a fresh acknowledged value after renewal using an unconditional prefix delete. The raw counterexample remains in integration tests to show why that application algorithm is unsafe; unconditional raw deletes still have their ordinary meaning on unmanaged Boxes. This was a missing capability, not unexplained Candybox durability loss.

The managed adapter integration tests cover renewal versus stale claim, refused unretired cleanup, read/delete/clear not renewing, expiry at equality, terminal retirement after clock regression, bounded repeatable clear, real TCP owner handover and HTTP error/CORS behavior. See [current verification](verification.md) for executed results.

Physical reclamation lag, complete crash/fault matrices, three-VM memory/availability, public HTTPS, mail-provider delivery and a 24-hour soak remain separate launch gates. The upstream fix resolves the atomic-lifecycle blocker; it does not establish those deployment properties.

## Upgrade constraint

Managed Box descriptor v3 and namespace opcodes require upgraded owners and clients. Follow upstream's stopped-cluster upgrade guidance; do not run older owners against a managed Box or promise mixed-version rolling upgrades. Retain the existing v1 application value envelope. Rollback to an older Candybox requires export and a forward-compatible migration, not opening new managed state with old readers.
