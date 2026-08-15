---
title: "Kotlin B41 resource vault GUI manifest"
tags: [kotlin, java, migration, paper, gui, resources]
status: active
created: 2026-08-14
---

# Kotlin B41 resource vault GUI

- Base: `b5b913bdba7255306176b8812d02eb7de740bbf1` (B40 final).
- Verified code HEAD: `b081452d2ced7afcb0e3b28298538153e0fafbe6`.
- Scope: migrate `ResourceVaultGui` from Java to Kotlin and add `ResourceVaultGuiKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/ResourceVaultGui.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/ResourceVaultGui.kt`.

## Preserved boundaries

- Public final utility class, private no-arg constructor, ten public static slot/size constants, and both `create(UUID,TeamResourceSnapshot)` and `create(UUID,TeamResourceSnapshot,boolean,boolean)` Inventory-returning descriptors remain Java-compatible.
- The GUI title, holder attachment, defense/enhancement balance and provisional-claim lore, slot placement, material/name/color choices, owner/canWithdraw guards, balance thresholds, all-withdraw behavior, disabled dye/action lore, and close item remain unchanged.
- GUI metadata null failure remains `GUI item metadata`; lore component lists remain unmodifiable as in the old Java stream terminal operation. Existing `CoreManagementListener` and `ResourceVoucherListener` callers were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 110 XML test suites, 343 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `D254321638545576B31A1B1941EBDB3FE9EF41560040FB3E4C749035BB75B334`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin GUI class and Companion present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and live resource-vault acceptance remain a separate real-server gate.
