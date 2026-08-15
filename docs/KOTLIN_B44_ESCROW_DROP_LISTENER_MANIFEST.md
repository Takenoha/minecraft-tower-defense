---
title: "Kotlin B44 escrow drop listener manifest"
tags: [kotlin, java, migration, paper, escrow, listener]
status: active
created: 2026-08-14
---

# Kotlin B44 escrow drop listener

- Base: `3b5bab03df27207ada0a39bcd0c3b454b42b2923` (B43 final).
- Verified code HEAD: `6a02329699c95232a61ee6cd05ea8a55f566d0e6`.
- Scope: migrate `EscrowDropListener` from Java to Kotlin and add `EscrowDropListenerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/EscrowDropListener.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/EscrowDropListener.kt`.

## Preserved boundaries

- Public final `Listener` class, public `PaperEscrowDropManager` constructor, all 19 event-handler descriptors, and their runtime priorities/`ignoreCancelled` values remain Java-compatible. `onChunkLoad` remains the MONITOR handler; the transfer and protection handlers remain HIGHEST and ignore cancelled events.
- Pickup delegation, stale-display cleanup, inventory pickup/move/click/drag, player drop/death, craft, dispense/place/interact/consume, item-frame interaction, merge/despawn, damage, portal, and teleport paths preserve the old tagger checks and cancellation/removal behavior.
- Existing `TowerDefensePlugin` wiring and `PaperEscrowDropManager`/`EscrowDropTagger` boundaries are unchanged. Craft matrix null handling is explicitly retained, including nullable ItemStack checks.

## Verification

- Command: `gradlew clean test` at the exact verified code HEAD.
- Result: `BUILD SUCCESSFUL`; 113 XML test suites, 346 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `070AB3210FC18D6ED0B49DBCFEEA254BF3A9239F5468E5C6440588EDCAEFFCF4`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin listener class present, JVM major 69; Java duplicate source/class: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper real-server escrow-display and GUI/start/restart/win-loss/abort/technical-recovery acceptance remain separate gates.
