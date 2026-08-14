---
title: "Kotlin B42 tactical selection GUI manifest"
tags: [kotlin, java, migration, paper, gui, tactical]
status: active
created: 2026-08-14
---

# Kotlin B42 tactical selection GUI

- Base: `b6d70838ac6d1a2863536541d2e0b5bfcc5ced28` (B41 final).
- Verified code HEAD: `ec27b6bff2e3fef997a523f8cfa73686c5c40a28`.
- Scope: migrate `TacticalBuildSelectionGui` from Java to Kotlin and add `TacticalBuildSelectionGuiKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/TacticalBuildSelectionGui.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TacticalBuildSelectionGui.kt`.

## Preserved boundaries

- Public final utility class, private no-arg constructor, six public static size/slot constants, public candidate/branch slot arrays, `create(TacticalBuildSelectionInventoryHolder): Inventory`, both `refresh` overloads, and both slot-index helpers remain Java-compatible.
- The GUI title, holder attachment, candidate and branch slot placement, selected-state prefixes, candidate/branch material and color choices, branch Tier lore ordering, material fallback, confirm/close items, and existing caller boundary remain unchanged.
- `selection item metadata` failure remains explicit; lore component lists remain unmodifiable as in the old Java `Stream.toList()` contract. Candidate/branch arrays retain the old public static mutable-array shape.

## Verification

- Commands: `gradlew clean test`; then `git rev-parse HEAD` followed by `gradlew test` at the exact verified code HEAD.
- Exact tested HEAD: `ec27b6bff2e3fef997a523f8cfa73686c5c40a28`.
- Result: `BUILD SUCCESSFUL`; 111 XML test suites, 344 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `6D1ACD15663B93BAE7F2DB8BD0BC0D0C3F87FCEE172D4AB2BD01739A9B3590E9`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin GUI class present, JVM major 69; Java duplicate source/class: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and live tactical-selection acceptance remain a separate real-server gate.
