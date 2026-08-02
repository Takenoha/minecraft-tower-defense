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

## Safety boundary

Until a write-ahead world-mutation journal and drop escrow exist, event enemies cannot break or place blocks and the simulation creates no custom or vanilla rewards. No temporary substitute rewards are issued. The first implementation also excludes towers, research, start-item reservation, public core crafting, and team management.

## Acceptance mapping

The first implementation targets these requirement checks directly:

- `AC-21.1-1`, `AC-21.1-2`, `AC-21.1-4`, `AC-21.1-5`
- `AC-21.2-1`, `AC-21.2-5`, `AC-21.2-6`, `AC-21.2-9`, `AC-21.2-10`
- `AC-21.3-5`
- `AC-21.7-1`, `AC-21.7-8`

The HP, enemy cleanup, stage-wave, and configuration checks are only partially satisfied where the full requirement also depends on later repair, economy, terrain, AI, or tower work.

