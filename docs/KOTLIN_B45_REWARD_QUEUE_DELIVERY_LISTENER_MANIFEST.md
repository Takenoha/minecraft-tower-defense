---
title: "Kotlin B45 reward queue delivery listener manifest"
tags: [kotlin, java, migration, paper, rewards, listener]
status: active
created: 2026-08-14
---

# Kotlin B45 reward queue delivery listener

- Base: `b98699e00f00c87beff572da28de6a8ca8222d28` (B44 final).
- Verified code HEAD: `3d50a948c843239d2e87bed81f6dc25c2765acbc`.
- Scope: migrate `RewardQueueDeliveryListener` from Java to Kotlin and add `RewardQueueDeliveryListenerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/RewardQueueDeliveryListener.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/RewardQueueDeliveryListener.kt`.

## Preserved boundaries

- Public final `Listener` class, public `RewardQueueDeliveryManager` constructor, default player join/quit handler annotations, and all 18 HIGHEST/ignore-cancelled receipt-protection handler descriptors remain Java-compatible.
- Join/quit delegation and receipt protection for inventory click/drag/move/pickup, entity pickup/drop, craft, place/dispense/interact/consume, item-frame interaction, death, merge/despawn, damage, portal, and teleport preserve the old tagger checks and cancellation/removal behavior.
- Existing `TowerDefensePlugin` wiring and `RewardQueueDeliveryManager`/`RewardQueueReceiptTagger` boundaries are unchanged. Craft matrix null handling is explicitly retained, including nullable ItemStack checks.

## Verification

- Command: `gradlew clean test` at the exact verified code HEAD.
- Result: `BUILD SUCCESSFUL`; 114 XML test suites, 347 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `ED20203E34F691243C762544178606FC44655736765B5787535A652A06E2AF22`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin listener class present, JVM major 69; Java duplicate source/class: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper real-server reward delivery and GUI/start/restart/win-loss/abort/technical-recovery acceptance remain separate gates.
