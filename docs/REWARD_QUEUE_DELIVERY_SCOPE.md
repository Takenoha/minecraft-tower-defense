# PR #8 reward queue delivery boundary

PR #8 connects the already-persisted terminal reward queue to Paper player inventories. It does
not enable enemy terrain mutation or create new reward sources.

## Included

- Schema v8 adds the prepared/applied form of `event_reward_delivery_operations`, with one durable
  delivery operation per queue row and an operation UUID that is committed atomically with
  `PENDING -> DELIVERED`.
- The repository loads only pending rows authorized for the current player:
  - `PLAYER` rows require the exact original recipient UUID;
  - `TEAM` rows require current team membership and registered participation in that event.
- Online players are retried after a successful normal terminal transaction and on every join.
- A TEAM row is durably reserved for the first eligible player who starts its handoff. Other
  eligible players leave it pending while that reservation is unfinished, preventing concurrent
  partial inventory inserts from duplicating a shared row.
- Inventory mutation remains on the Paper main thread; database reads and writes run on the single
  database executor.
- A queue/operation PDC receipt is copied to accepted stacks before the database acknowledgement.
  A retry counts existing receipts first, so a stop after inventory insertion cannot issue a second
  copy. The receipt is removed after the idempotent database acknowledgement.
- Full inventories, invalid payloads, offline players, and transient database failures leave the
  queue row pending for a later retry.
- Technical recovery never schedules delivery; its existing atomic escrow void path remains in
  force.

## Deliberately not included

- Public reward configuration, custom item catalogs, research progression, or team GUI.
- Retention-period owner fallback for an abandoned team queue.
- Protected-region validation, role-specific terrain AI, or Paper load and TPS tests. Tile payload
  capture and protected-target synchronization are added by PR #9.
- Production activation of `TerrainMutationPolicy`; the plugin still keeps enemy block changes
  disabled.

## Verification

The persistence suite covers recipient authorization, pending-player selection, delivery operation
idempotence, and terminal queue state. The full Gradle test/build command remains the acceptance
command for this milestone.
