# Minecraft Tower Defense

A Paper server plugin that adds cooperative tower-defense encounters to a normal survival world.

## Platform

- Minecraft Java Edition / Paper 26.2
- Paper build 87 stable
- Java 25
- Vanilla clients; no required resource pack

The version is intentionally fixed for the first release. See the official [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/) and [Java requirements](https://docs.papermc.io/paper/getting-started/).

## Current milestone

The first PR is a safe, administrator-only walking skeleton:

- one persisted core per solo test team;
- one globally active defense session, enforced by SQLite;
- countdown, preparation, active wave, intermission, victory, defeat, and recovery phases;
- a playable five-wave level-one simulation with a final boss;
- fixed participants, core HP, absence defeat, spawn throttling, and idempotent cleanup;
- startup recovery that never resumes a half-finished encounter;
- no terrain mutation, custom rewards, start-item consumption, or towers until their rollback boundaries exist.

The second milestone adds those rollback boundaries as a persistence-only foundation. It records
write-ahead block changes, protects later player edits during recovery, keeps event drops in a
virtual escrow, and makes raid-seal refunds issue a new UUID. The Paper simulation still does not
enable terrain mutation, physical escrow items, or rewards; the main-thread adapters remain the
next milestone. See [the PR #2 scope](docs/ROLLBACK_ESCROW_SCOPE.md).

The third milestone adds operation-UUID protected team membership and ownership changes, core
repair, full-health relocation, and destroyed-core rebuild persistence. These APIs enforce team
membership and reject changes while an event is active, but public team GUI, core crafting, repair
costs, protected-region checks, and physical block replacement are still disabled. See [the PR #3
scope](docs/CORE_TEAM_SCOPE.md).

The fourth milestone connects the PR2 ledger to Paper's main thread for verified block apply and
startup/shutdown recovery. It persists rollback decisions across the physical-restore boundary,
preserves later player edits as conflicts, and rejects existing tile entities as mutation sources.
Enemy break/place behavior, escrow entities, and rewards remain disabled until their protection
adapters are complete. See [the PR #4 scope](docs/PAPER_RECOVERY_ADAPTER_SCOPE.md).

The fifth milestone adds a guarded single-enemy-block action, mandatory protected-material policy,
and durable per-coordinate generation sequencing. The handler is wired into the tagged-enemy event
listener but is constructed disabled because normal-end terrain settlement, tile NBT, and escrow
drop protection are not complete. See [the PR #5 scope](docs/ENEMY_TERRAIN_ACTION_SCOPE.md).

This is deliberately not advertised as the complete game described by the product requirements.

## Build

```text
./gradlew clean test build
```

The plugin JAR is written to `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar`.

## Administrator test flow

1. Build the plugin and install the JAR on Paper 26.2 running Java 25.
2. Look at the solid block that should act as the temporary test core.
3. Run `/td admin core`.
4. Stay within the configured combat radius and run `/td admin simulate 1`.
5. Inspect the encounter with `/td admin status` or stop it safely with `/td admin abort`.

All current commands require `towerdefense.admin` (operator by default).
