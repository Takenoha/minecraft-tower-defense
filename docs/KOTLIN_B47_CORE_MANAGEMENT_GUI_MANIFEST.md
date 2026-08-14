---
title: "Kotlin B47 core management GUI manifest"
tags: [kotlin, java, migration, paper, gui, core]
status: active
created: 2026-08-14
---

# Kotlin B47 core management GUI

- Base: `5ec7cc131af5babfa53eab850705855cd93420e3` (B46 final).
- Verified code HEAD: `a479cdd9f4c5ac349e3967babcd0bc9c77fba51d`.
- Scope: migrate `CoreManagementGui` from Java to Kotlin and add `CoreManagementGuiKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/CoreManagementGui.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/CoreManagementGui.kt`.

## Preserved boundaries

- Public final utility class, private no-arg constructor, ten public static slot/size constants, three `create` overloads, and `stageLevelAt(int): OptionalLong` remain Java-compatible.
- Core status, team/member and owner display, resource-vault/research-deposit/tower-research controls, repair and legacy-payment branches, start/relocate/close items, raid-seal stage buttons, slot/material/color choices, and existing `CoreManagementListener`/`RaidSealListener` routing remain unchanged.
- `repairMaterialName` retains the old nullable Java string-concatenation behavior; GUI metadata guards remain explicit, and lore component lists remain unmodifiable as in the old Java `Stream.toList()` contract.

## Verification

- Command: `gradlew clean test` at the exact verified code HEAD.
- Result: `BUILD SUCCESSFUL`; 116 XML test suites, 349 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `96A757F88D08413D203E99F6E264243657B755E6FB970DDEA2A3867AE2D62F1A`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin GUI class present, JVM major 69; Java duplicate source/class: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper real-server core GUI/start/restart/win-loss/abort/technical-recovery acceptance remains a separate gate.
