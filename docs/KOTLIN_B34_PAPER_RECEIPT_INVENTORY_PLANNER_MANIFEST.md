---
title: "Kotlin B34 Paper receipt inventory planner manifest"
tags: [kotlin, java, migration, paper, receipts, inventory]
status: active
created: 2026-08-14
---

# Kotlin B34 Paper receipt inventory planner

- Base: `5ae023eddce99ae0e304487cf32cde2f8b41c658` (B33 final).
- Verified code HEAD: `dc44bde79845fb2cf89c8f08bacfefa3ef355146`.
- Scope: migrate `ReceiptInventoryPlanner` from Java to Kotlin and add `ReceiptInventoryPlannerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/ReceiptInventoryPlanner.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/ReceiptInventoryPlanner.kt`.

## Preserved boundaries

- Public final utility class, private no-arg constructor, `plan(ItemStack[], Predicate, long, String): Optional<List<Extraction>>`, and `canApply(ItemStack[], List): boolean` remain Java-compatible.
- `Extraction` remains a JVM record with the same component names/order, canonical constructor descriptor, accessors, validation, and original-stack reference behavior.
- Plan quantity guards, source filtering, storage-slot order, partial extraction amounts, clone-before-plan snapshots, unmodifiable successful result lists, and empty/insufficient results are unchanged.
- `canApply` continues to normalize item snapshots, delegate to the Kotlin B33 `ReceiptSplitPlanner`, and refuse any split that would require a ground drop. `ReceiptInventoryPlanner` and `TowerManager`/`CoreManagementListener` callers were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 103 XML test suites, 336 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `903EBDAEC64C3D4E7895DC075337BA10CCA892B1891A4C675D6651AB08BB8613`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin planner class and nested record class present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
