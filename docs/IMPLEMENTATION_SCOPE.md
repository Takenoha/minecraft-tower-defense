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

## Safety boundary

Until a write-ahead world-mutation journal and drop escrow exist, event enemies cannot break or place blocks and the simulation creates no custom or vanilla rewards. No temporary substitute rewards are issued. Towers, research, start-item reservation, public core crafting, physical core replacement, and GUI team management remain disabled.

## Acceptance mapping

The first implementation targets these requirement checks directly:

- `AC-21.1-1`, `AC-21.1-2`, `AC-21.1-4`, `AC-21.1-5`
- `AC-21.2-1`, `AC-21.2-5`, `AC-21.2-6`, `AC-21.2-9`, `AC-21.2-10`
- `AC-21.3-5`
- `AC-21.7-1`, `AC-21.7-8`

The HP, enemy cleanup, stage-wave, and configuration checks are only partially satisfied where the full requirement also depends on later repair, economy, terrain, AI, or tower work.
