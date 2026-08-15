---
title: "Kotlin B38 Paper tactical selection holder manifest"
tags: [kotlin, java, migration, paper, tactical, gui]
status: active
created: 2026-08-14
---

# Kotlin B38 Paper tactical selection holder

- Base: `3443b67d9c069eadf8c0744da32f8bee9cad2563` (B37 final).
- Verified code HEAD: `a4858108a444f11aa02992f41cd8fa3427b2a0c6`.
- Scope: migrate `TacticalBuildSelectionInventoryHolder` from Java to Kotlin and add `TacticalBuildSelectionInventoryHolderKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/TacticalBuildSelectionInventoryHolder.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TacticalBuildSelectionInventoryHolder.kt`.

## Preserved boundaries

- Public final `InventoryHolder`, six-argument constructor, identity/candidate/stage accessors, selection Optional accessors, branch/confirming methods, `attach`, and `getInventory` Java descriptors remain compatible.
- Constructor null guards and stage/candidate consistency validation remain unchanged. Selection still validates candidate build IDs, clears a previously selected branch when the build changes, and preserves the branch-required decision.
- Branch selection still requires a selected build, rejects null/blank/unavailable branches with the existing exception contracts, and confirming state remains monotonic. Unattached inventory still throws `the tactical selection inventory has not been attached`.
- The old package-private `attach` is public in Kotlin because Kotlin has no package-private declaration; this additive visibility is covered by the ABI test and recorded here. Existing tactical GUI/listener callers were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 107 XML test suites, 340 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `8EE043638C1E86E2036B55AB8E8EF3C0CE58114869C6A56D6EB802FC6960DAC6`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin holder class present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and tactical selection acceptance remain a separate real-server gate.
