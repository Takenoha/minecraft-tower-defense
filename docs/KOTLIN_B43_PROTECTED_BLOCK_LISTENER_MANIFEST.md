---
title: "Kotlin B43 protected block listener manifest"
tags: [kotlin, java, migration, paper, protection, listener]
status: active
created: 2026-08-14
---

# Kotlin B43 protected block listener

- Base: `18e4a87394334c545daa419c88c0d4d1960a7c58` (B42 final).
- Verified code HEAD: `fcc8ae827d0a27b456fff4530587a6efc4493214`.
- Scope: migrate `ProtectedBlockListener` from Java to Kotlin and add `ProtectedBlockListenerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/ProtectedBlockListener.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/ProtectedBlockListener.kt`.

## Preserved boundaries

- Public final `Listener` class, public `(CoreRegistry,EnemyAccessPolicy)` constructor, all 13 public event-handler descriptors, and the private piston helper boundary remain Java-compatible.
- Every handler retains `EventHandler(priority = HIGHEST, ignoreCancelled = true)`. Break/place, piston movement, explosion filtering, liquid, grow/fade, burn/ignite, physics, and entity block-change protection keep the old protected-core and combat-area/required-material decisions and cancellation order.
- Existing `TowerDefensePlugin` registration and Java callers are unchanged. Explicit null guards and protected-target tile-state handling remain at the same boundaries.

## Verification

- Command: `gradlew clean test` at the exact verified code HEAD.
- Result: `BUILD SUCCESSFUL`; 112 XML test suites, 345 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `DFBD19A1E189D48374764CDF8168DC8115973DD582D0923D1B027236509EF42B`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin listener class present, JVM major 69; Java duplicate source/class: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper real-server protection and GUI/start/restart/win-loss/abort/technical-recovery acceptance remain separate gates.
