---
title: "Kotlin B30 Paper recipe catalogs manifest"
tags: [kotlin, java, migration, paper, recipes, gui]
status: active
created: 2026-08-14
---

# Kotlin B30 Paper recipe catalogs

- Base: `bdee07e47dd16c0b2a59cbae95bc2267bb2c3c46` (B29 final).
- Verified code HEAD: `e45c2f2a1f5fca13360378457dd4fb2e6b9558d9`.
- Scope: migrate `TowerRecipeCatalog` and `RaidSealCatalog` from Java to Kotlin and add `PaperRecipeCatalogsKotlinBoundaryAbiTest`.
- Removed sources: `src/main/java/io/github/takenoha/towerdefense/paper/TowerRecipeCatalog.java`, `src/main/java/io/github/takenoha/towerdefense/paper/RaidSealCatalog.java`.
- Added sources: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TowerRecipeCatalog.kt`, `src/main/kotlin/io/github/takenoha/towerdefense/paper/RaidSealCatalog.kt`.

## Preserved boundaries

- Both utility classes retain public final class shape and private no-argument constructors.
- Tower recipe suffix/key generation, `TowerType.values()` order, player discovery count, and plugin null validation remain unchanged.
- Raid seal recipe stages, ten material names, stage validation, GUI slot mapping, `OptionalLong` empty cases, and out-of-range messages remain unchanged.
- Existing `TowerManager`, `CoreItemListener`, `RaidSealListener`, `CoreManagementGui`, and `TowerDefenseCommand` callers continue using the same static descriptors.

## Known additive interop surface

- `TowerRecipeCatalog.recipeKeySuffix(TowerType)` was package-private in Java and is public in the Kotlin-generated static boundary so the existing same-package Java test/caller remains directly callable. This is additive visibility only.
- Kotlin companion metadata and public static method final modifiers are additive generated artifacts.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 99 XML test suites, 328 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `9006B0293D1483BF9AB2FCC27203773E55C6A4CF063E846158EFF99CB7D0D961`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin catalog classes present, JVM major 69; Java duplicates: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
