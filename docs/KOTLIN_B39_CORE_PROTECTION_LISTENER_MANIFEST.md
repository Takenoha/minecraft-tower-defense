---
title: "Kotlin B39 core protection listener manifest"
tags: [kotlin, java, migration, paper, protection, listener]
status: active
created: 2026-08-14
---

# Kotlin B39 core protection listener

- Base: `d2fe2ee7d540e775379bab6188fd8bb0d050272b` (B38 final).
- Verified code HEAD: `d5d98c9624c95d32ad2b17cdf50c89d3c05c9c4d`.
- Scope: migrate `CoreProtectionListener` from Java to Kotlin and add `CoreProtectionListenerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/CoreProtectionListener.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/CoreProtectionListener.kt`.

## Preserved boundaries

- Public final `Listener`, `CoreRegistry` constructor, nine public event-handler descriptors, and private `movesCore(Iterable<Block>, BlockFace): boolean` descriptor remain compatible.
- All handlers retain `EventPriority.HIGHEST` and `ignoreCancelled=true`. Core break, piston movement, liquid flow, entity block change, burn, and fade remain cancelled; core blocks remain removed from entity/block explosion lists.
- Piston checks still inspect both moved blocks and their destination relative to the event direction. Existing `TowerDefensePlugin` wiring and `CoreRegistry` caller boundaries were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 108 XML test suites, 341 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `2337AF566461AF637D2A8AC106FAF7B042DB5095BD55620981EB5572F989233C`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin listener class present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and live protection acceptance remain a separate real-server gate.
