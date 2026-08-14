---
title: "Kotlin B33 Paper receipt split planner manifest"
tags: [kotlin, java, migration, paper, receipts, inventory]
status: active
created: 2026-08-14
---

# Kotlin B33 Paper receipt split planner

- Base: `ad2dded5d2c15dc328840bd1198577ca30d9e195` (B32 final).
- Verified code HEAD: `d7e1d74b1c452bf7ce61f2be703679e415747842`.
- Scope: migrate `ReceiptSplitPlanner` from Java to Kotlin and add `ReceiptSplitPlannerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/ReceiptSplitPlanner.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/ReceiptSplitPlanner.kt`.

## Preserved boundaries

- Public final utility class, private no-arg constructor, and static `canApply(List, List): boolean` remain Java-compatible.
- `Stack` and `Split` remain JVM records with the same component names, order, canonical constructor descriptors, accessors, validation messages, and equality/value behavior at the Java boundary.
- Null contents/splits checks, duplicate receipt-slot rejection, slot/key/amount validation, receipt-slot exclusion, compatible-stack filling, empty-slot placement, and no-drop capacity result are unchanged.
- `ReceiptInventoryPlanner` and all existing Java callers remain unchanged; the planner continues to be used as a pure pre-mutation capacity check.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 102 XML test suites, 333 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `C324F125515FA27C99879FA0FAAAE69365B8FE131C7A3813F7F8975B1D41205E`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin planner class and nested record classes present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
