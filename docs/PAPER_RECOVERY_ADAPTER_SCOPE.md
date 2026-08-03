# PR #4: Paper block recovery adapter

This milestone connects the PR2 write-ahead block ledger to the Paper main thread without enabling
event enemies to modify survival terrain yet.

## Included

- SQLite schema migration 5, which persists a prepared rollback decision so a stop between the
  physical restore and its database acknowledgement can resume with the same operation UUID.
- `PaperBlockStateCodec` for canonical BlockData/type snapshots and physics-disabled BlockData
  application. Existing tile entities are rejected as mutation sources so their contents cannot be
  silently overwritten.
- `PaperBlockMutationAdapter`, which follows prepare → physical apply → verification → applied
  acknowledgement and refuses to overwrite a live state that differs from the ledger.
- Reverse-generation startup recovery that restores only expected event-owned blocks, skips already
  restored blocks, and records later player edits as durable conflicts.
- Plugin startup and shutdown recovery now run the Paper adapter before releasing the event lock.

## Deliberate boundary

The event enemy listener still cancels EntityChangeBlockEvent, and no enemy break/place behavior is
enabled. Tile-container NBT, escrow entities, hopper/container/death protection, reward delivery,
protected-region integration, and real Mob terrain pathing remain future work. If a required world
or a safe block state is unavailable during recovery, startup stops with the event lock retained for
operator inspection rather than claiming recovery succeeded.

PR5 adds the narrowly scoped action and mandatory protection policy in
`docs/ENEMY_TERRAIN_ACTION_SCOPE.md`; its production policy remains disabled until normal-end
terrain settlement and block-drop escrow are connected.

PR6 adds normal terminal settlement in `docs/TERRAIN_SETTLEMENT_SCOPE.md`: destruction rows are
kept in place, temporary rows are removed through the conflict-safe planner, and the event lock is
not released when settlement fails. Physical block-drop escrow remains the next activation gate.
