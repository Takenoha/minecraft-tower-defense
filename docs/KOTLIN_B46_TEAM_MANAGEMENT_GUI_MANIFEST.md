---
title: "Kotlin B46 team management GUI manifest"
tags: [kotlin, java, migration, paper, gui, team]
status: active
created: 2026-08-14
---

# Kotlin B46 team management GUI

- Base: `010863b5b68c56f530d0743a209ab3e4677d1d0c` (B45 final).
- Verified code HEAD: `ec75cdc2a320b11c440e26ca6b615aa57cb29193`.
- Scope: migrate `TeamManagementGui` from Java to Kotlin and add `TeamManagementGuiKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/TeamManagementGui.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TeamManagementGui.kt`.

## Preserved boundaries

- Public final utility class, private no-arg constructor, seven public static slot/size constants, `create(CoreRecord,TeamRecord,UUID): Inventory`, and `createConfirmation(UUID,UUID,Action): Inventory` remain Java-compatible.
- Team member sorting by player name then UUID, holder attachment/member-slot mapping, owner-only invite/rename controls, leave/close/help items, member-head roles/lore/overflow handling, confirmation action text, GUI slots/materials/colors, and metadata guards remain unchanged.
- Component lore lists remain unmodifiable as in the old Java `Stream.toList()` contract. Existing `CoreManagementListener` callers and the B31 inventory-holder boundaries are unchanged.

## Verification

- Command: `gradlew clean test` at the exact verified code HEAD.
- Result: `BUILD SUCCESSFUL`; 115 XML test suites, 348 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `9A6FA597B32E8CD3C2C81E68FD248FA575DB09678EE6B77DFFB1A510700DFB44`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin GUI class present, JVM major 69; Java duplicate source/class: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper real-server team GUI/start/restart/win-loss/abort/technical-recovery acceptance remains a separate gate.
