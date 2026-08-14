---
title: "Kotlin B52 Raid Seal Listener Migration Manifest"
tags: [kotlin-migration, paper-listener, raid-seal, abi]
status: active
created: 2026-08-14
---

# Kotlin B52 Raid Seal Listener Migration Manifest

## Scope

- Base: `f8e35bf54f6ffeb4c928b46e45cf7a240a8896ed`
- Implementation/code verification HEAD: `255cafdcd67250360eed58427d26514964240594`
- Target: `RaidSealListener.java` → `RaidSealListener.kt`
- Boundary test: `RaidSealListenerKotlinBoundaryAbiTest.java`
- The final branch manifest commit is docs-only after the code verification HEAD; no
  `src/main` or `src/test` implementation changes follow the verification commit.

## Preserved contract

- `RaidSealListener` remains a public final Bukkit `Listener` with the public six-
  argument constructor and the seven-argument constructor that accepts optional
  `TacticalBuildSelectionListener` integration. The private `Optional` constructor
  remains the implementation boundary for the overloads.
- `registerRecipe` retains the ten-stage recipe registration, namespaced keys,
  recipe shape, paper ingredient, stage-material mapping, remove-before-add behavior,
  and the public static `configureRecipe(ShapedRecipe, Material)` package helper.
- Craft handling preserves valid-seal ingredient rejection, one-at-a-time recipe
  crafting, player-only creation, tagger-generated seal identity, asynchronous
  repository registration, and main-thread rollback/removal plus root-cause messaging
  when persistence fails.
- Crafter handling keeps plugin-recipe/result-template rejection and current/legacy
  seal ingredient detection through `RaidSealAutomationPolicy`. Join reconciliation
  retains owned-seal loading, available-refund loading, stale/legacy inventory repair,
  physical-item checks, technical refund delivery, and immutable reconciliation data.
- Core interaction and core-GUI start preserve right-click/hand/core guards, stage
  selection and highest-seal selection, physical-item authority, inventory closing,
  and the optional tactical-selection or direct `TowerDefenseCommand.startWithSeal`
  handoff.
- The six event-handler descriptors and their priorities/ignore-cancelled values are
  retained: craft, crafter craft, join, and core interaction use `HIGHEST` with
  `ignoreCancelled=true`; core-GUI start uses `LOWEST` with the annotation default
  `ignoreCancelled=false`.
- `hasPhysicalItem(UUID)` and `removeMatchingItems(UUID)` remain available to the
  existing Java package callers. Kotlin makes these former package-private helpers
  public as an additive visibility change. `rootMessage(Throwable)` remains private
  and preserves CompletionException unwrapping and root-cause fallback behavior.
- Existing `RaidSealRepository`, `DatabaseExecutor`, `CoreRegistry`, tagger, catalog,
  GUI, tactical-selection, command, and plugin wiring are unchanged.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 121 XML files; 354 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `E0EE1D831FAA17459DFE01BEFD439F87593E10B5B845CEFC38050AE5351413FF`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated listener JVM major: 69
- Kotlin listener classes: outer class, `Companion`, and `Reconciliation`; Java
  duplicate: absent
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean; worktree clean at the code verification HEAD
- Paper real-server raid-seal crafting, crafter protection, reconciliation, GUI/start,
  restart, win/loss, abort, and technical-recovery acceptance remains a separate
  final gate.
