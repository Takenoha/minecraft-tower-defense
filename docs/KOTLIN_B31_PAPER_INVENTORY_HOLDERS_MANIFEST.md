---
title: "Kotlin B31 Paper inventory holders manifest"
tags: [kotlin, java, migration, paper, gui, inventory]
status: active
created: 2026-08-14
---

# Kotlin B31 Paper inventory holders

- Base: `c622ebf15df0f29879a25d65beeddc91db6fe4df` (B30 final after the list-immutability fix).
- Implementation commit: `b824bde`.
- Verified HEAD: `f2ae7203f92ee6d0e3d1b81e594be72b3983782f` (includes the propagated B30 compatibility fix and manifest).
- Scope: migrate six low-dependency `InventoryHolder` classes to Kotlin and add `PaperInventoryHoldersKotlinBoundaryAbiTest`.
- Removed sources: `CoreManagementInventoryHolder.java`, `TowerManagementInventoryHolder.java`, `ResourceVaultInventoryHolder.java`, `TeamManagementInventoryHolder.java`, `TeamManagementConfirmationHolder.java`, `TowerResearchInventoryHolder.java`.
- Added corresponding Kotlin sources under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`.

## Preserved boundaries

- Public final holder classes, constructor descriptors, identity accessors, `InventoryHolder.getInventory(): Inventory`, confirmation `Action` enum values, member lookup, and attached-inventory failure message remain compatible.
- `memberSlots` starts empty, is copied on attach, and `memberAt` returns `Optional.empty()` for absent slots.
- GUI layout, event handling, database operations, Paper wiring, and caller source were not changed.

## Known additive interop surface

- Old package-private `attach` and `attachMemberSlots` methods are public in generated Kotlin so existing Java GUI callers can invoke them; this is additive visibility only.
- Kotlin null checks, final methods, and generated metadata are additive implementation details; ordinary callers retain the same non-null contract.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 100 XML test suites, 330 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `26580A503A1783488F7377C6369ACFA74C851611702BEC2E112B93CCF4D3DEBA`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Six Kotlin holder classes present, JVM major 69; Java duplicates: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
