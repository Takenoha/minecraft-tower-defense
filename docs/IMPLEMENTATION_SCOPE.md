# First implementation scope

This repository starts with an administrator-only walking skeleton. It proves the lifecycle and recovery boundaries before any event is allowed to modify a survival world.

## Included

- Exact Paper 26.2 / build 87 / Java 25 build target.
- Validated combat, core, and enemy settings.
- Persisted solo test teams and one core per team.
- Minimum core separation and core HP persistence.
- A database-backed, server-global active-event lock.
- Deterministic `COUNTDOWN`, `PREPARATION`, `WAVE_ACTIVE`, `INTERMISSION`, `VICTORY`, `DEFEAT`, `ABORTED`, and `RECOVERY` transitions.
- Fixed registered participants and a separate effective-participant set.
- Five playable level-one waves, including a final boss.
- Logical enemy IDs, an alive cap, a pending spawn queue, and idempotent death accounting.
- Victory, core-destruction defeat, and registered-participant absence defeat.
- Startup cleanup and recovery instead of attempting to resume a partial battle.

## PR #2 persistence boundary

PR #2 adds the durable prerequisite for world mutation and rewards without enabling either in the
Paper simulation:

- write-ahead block-change records with generation, expected-after comparison, reverse recovery,
  and conflict preservation;
- virtual event-drop escrow and registered-participant claim records;
- normal-terminal reward queue issuance and technical-recovery invalidation;
- single-use raid-seal reservation/consumption and new-UUID technical refunds;
- terminal/recovery transactions that settle or invalidate these records atomically.

Live Bukkit/Paper block listeners, escrow entity listeners, inventory reconciliation, and physical
queue delivery remain disabled until the next adapter milestone. See
`docs/ROLLBACK_ESCROW_SCOPE.md` for the exact API boundary and remaining integration work.

## PR #3 core and team persistence boundary

PR #3 adds durable ownership and core lifecycle mutations without enabling public crafting or GUI
flows:

- operation-UUID protected team member add/remove, ownership transfer, leave, and disband;
- actor membership checks and server-global event-lock checks on core/team mutations;
- full-health core relocation, repair, destroyed-core rebuild, and distance conflict checks;
- a core registry replacement path that stops protecting a destroyed core after a main-thread caller
  refreshes it.

The administrator test command remains the only enabled placement path. Physical block replacement,
world-border and protected-region validation, repair costs, public team management, and GUI
confirmation remain future Paper adapter work. See `docs/CORE_TEAM_SCOPE.md`.

## PR #4 Paper recovery adapter boundary

PR #4 connects the PR2 ledger to the Paper main thread without enabling enemy terrain mutation:

- schema v5 persists a prepared rollback decision for the physical-restore/database-acknowledgement
  stop window;
- Paper captures canonical BlockData and block type, refuses existing tile-entity mutation sources,
  applies with physics disabled, verifies the result, and only then acknowledges the ledger row;
- startup and shutdown recovery run reverse-generation planning before technical event recovery,
  preserving later player edits as durable conflicts.

Entity-enemy break/place actions, tile-container NBT, escrow entities, hopper/container/death
protection, protected-region checks, and reward delivery remain disabled. See
`docs/PAPER_RECOVERY_ADAPTER_SCOPE.md`.

## PR #5 guarded enemy terrain action boundary

PR #5 adds the first single-block enemy action path and keeps it disabled in production:

- `TerrainMutationPolicy` code-owns mandatory protection for cores, inventory-like blocks, beds,
  redstone machinery, portals, administrative blocks, and dangerous materials;
- the Paper event handler validates the tagged enemy and combat area, cancels the vanilla event, and
  routes an allowed action through the PR4 WAL adapter with a durable coordinate generation;
- destruction and placement are recorded as `EVENT_BLOCK` and `TEMPORARY_BLOCK` respectively so
  later normal-end settlement can apply different cleanup rules.

Normal-end settlement is not yet wired, so the production listener continues to cancel all tagged
enemy block changes. Tile NBT, block-drop escrow, hopper/container/death protection,
protected-region validation, and role-specific AI remain future work. See
`docs/ENEMY_TERRAIN_ACTION_SCOPE.md`.

## PR #6 normal terminal terrain settlement boundary

PR #6 adds the normal terminal terrain lifecycle without enabling enemy mutation in production:

- schema v6 records `SETTLED` event-destruction rows and migrates the v5 ledger;
- `EVENT_BLOCK` rows remain destroyed after normal victory, defeat, or abort, and are settled only
  after their physical apply acknowledgement;
- `TEMPORARY_BLOCK` rows are removed in reverse generation order through the existing conflict-safe
  rollback planner;
- Paper terminal handling completes terrain settlement before asynchronous event-lock release and
  retries while retaining the lock if settlement fails.

Technical recovery still rolls back every unresolved row. PR7 supplies the physical block-drop
escrow and display/pickup/container/death protection boundary; see
`docs/BLOCK_DROP_ESCROW_SCOPE.md`.

## PR #7 physical block-drop escrow boundary

PR #7 adds the Paper-side physical representation of ordinary event-block drops while keeping
the production terrain policy disabled:

- no-tool block drops are serialized into the existing held escrow rows before the block apply;
- PDC-tagged Item entities are spawned only after the WAL apply acknowledgement and cannot be
  picked up as ordinary inventory items;
- registered participant claims cross the asynchronous persistence boundary, while pickup,
  inventory, crafting, placement, dispensing, item-frame, merge, despawn, damage, portal, and
  death paths are blocked;
- normal terminal settlement clears the physical display and technical recovery removes it while
  voiding held escrow rows.

Reward queue delivery, Tile NBT, protected-region validation, and role-specific terrain AI remain
future work. See `docs/BLOCK_DROP_ESCROW_SCOPE.md`.

## Safety boundary

Until the remaining activation gates exist, event enemies cannot break or place blocks and the
simulation creates no custom or vanilla rewards. PR #7 supplies only database-owned block-drop
capture and a non-usable physical display; no temporary substitute rewards are issued.
Towers, research, start-item reservation, public core crafting, physical core replacement, and GUI
team management remain disabled. PR6 has the normal terrain settlement path, but PR5's production
policy remains disabled.

## Acceptance mapping

The first implementation targets these requirement checks directly:

- `AC-21.1-1`, `AC-21.1-2`, `AC-21.1-4`, `AC-21.1-5`
- `AC-21.2-1`, `AC-21.2-5`, `AC-21.2-6`, `AC-21.2-9`, `AC-21.2-10`
- `AC-21.3-5`
- `AC-21.7-1`, `AC-21.7-8`

The HP, enemy cleanup, stage-wave, and configuration checks are only partially satisfied where the full requirement also depends on later repair, economy, terrain, AI, or tower work.
