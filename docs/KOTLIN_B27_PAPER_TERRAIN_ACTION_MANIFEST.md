---
title: "Kotlin B27 Paper terrain action manifest"
tags: [kotlin, java, migration, paper, terrain, escrow, block]
status: active
created: 2026-08-14
---

# Kotlin B27 Paper terrain action

- Base: `ca8424f941a1e111d6cdcce6ffd04db7e92b6ead` (B26 final).
- Verified code HEAD: `b3076b5605df207afaaafde3ac2eae2a5c535571`.
- Scope: migrate `PaperEnemyTerrainAction` from Java to Kotlin and add the Java-facing ABI test `PaperEnemyTerrainActionKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/PaperEnemyTerrainAction.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/PaperEnemyTerrainAction.kt`.
- Intentionally unchanged: `PaperBlockMutationAdapter` remains Java because its package-private secondary constructor is an existing ABI boundary; B27 does not widen or otherwise alter that constructor.

## Preserved boundaries

- Public constructor `(TerrainMutationPolicy, PaperBlockMutationAdapter, PaperEscrowDropManager, CoreRegistry, EnemyAccessPolicy)` and the three public boolean action methods retain their Java descriptors and final/public shape.
- Main-thread, policy-enabled, live enemy identity, combat-area, obstacle classification, protected-material, inventory/core/tile, and temporary-block-cap guards remain fail-closed.
- `EntityChangeBlockEvent` cancellation occurs at the same mutation boundary.
- Event and path actions retain deterministic change, prepare, and apply operation UUID derivation.
- Escrow prepare/discard/spawn ordering and block WAL adapter calls remain unchanged.
- Path break/bridge candidates re-check their observed-before snapshot immediately before mutation, and bridge target material is checked against the pure planner result.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 96 XML test suites, 323 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `1FFB7648B74319889AB5B57396E357CEE223733332F71F572825308DF28E3835`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- `PaperEnemyTerrainAction.class`: present in the Kotlin output, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
