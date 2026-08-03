# PR #2: rollback and escrow foundation

This milestone adds the durable safety boundary required before event enemies or reward code may
modify a survival world.

## Included

- SQLite schema migration 3 with:
  - write-ahead block changes and reverse-generation recovery state;
  - idempotent `PREPARED → APPLIED` resource operations;
  - virtual event-drop escrow, registered-participant claims, and normal-terminal reward queues;
  - single-use raid-seal states and one-time technical refund records.
- `BlockChangeRepository` for prepare/apply/rollback operations.
- `BlockRollbackPlanner`, which restores only when the live block still equals the event's expected
  after-state. Later player changes become durable `CONFLICT` rows and are never overwritten.
- `EscrowRepository`, which keeps drops database-owned until a normal terminal settlement and
  voids all held drops during technical recovery.
- `RaidSealRepository`, which can reserve/consume a supplied start seal and creates a new UUID for
  a technical refund. The old UUID remains `REFUNDED`.
- Normal event termination and technical recovery now settle/void escrow in the same SQLite
  transaction as the event terminal transition. Recovery refuses to release the event lock while
  unresolved block ledger rows remain.
- Boundary tests for duplicate operations, partial stop windows, participant authorization,
  reward settlement, block conflicts, recovery guards, and seal refunds.

## Deliberate boundary

The Paper runtime does not yet call these repositories for live block edits, physical item display,
pickup listeners, or inventory reconciliation. The existing administrator-only simulation still has
terrain mutation and rewards disabled. A start request created by the existing admin command has no
seal; production start flow must pass `StartRequest(..., Optional.of(sealId))` after the start
validation and inventory adapter are implemented.

PR4 now adds the main-thread Paper adapter for canonical `BlockData`/block-type snapshots, physical
apply verification, and planner-driven startup recovery. It persists a prepared rollback decision
so a stop between physical restore and database acknowledgement can resume safely. Enemy terrain
mutation and physical escrow delivery remain disabled; tagged escrow entity protection in hopper,
container, death, and cross-world paths is still future work. PR5 adds the guarded single-block
enemy action path and mandatory material policy, but leaves the production policy disabled until
normal-end terrain settlement and block-drop escrow are connected. PR6 supplies the normal terrain
settlement half; physical block-drop escrow remains outstanding.
