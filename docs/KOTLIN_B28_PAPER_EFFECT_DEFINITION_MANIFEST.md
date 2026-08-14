---
title: "Kotlin B28 Paper effect definition manifest"
tags: [kotlin, java, migration, paper, particles, tower]
status: active
created: 2026-08-14
---

# Kotlin B28 Paper effect definition

- Base: `946d123d7411e878ba93282e743ced85a802ec64` (B27 final).
- Verified code HEAD: `0ac8fe15277920b9534dc44dafe4c9a1dfe68a74`.
- Scope: migrate `TowerEffectDefinition` from a Java record to a Kotlin `@JvmRecord` and add `TowerEffectDefinitionKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/TowerEffectDefinition.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TowerEffectDefinition.kt`.
- `TowerAttackEffects` remains unchanged in this slice and continues to consume the same Java-facing record shape.

## Preserved boundaries

- Record component names/order remain `type`, `trail`, `hit`, `buff`, `trailCount`, `hitCount`, `buffCount`.
- The public canonical constructor and all record accessors retain their Java descriptors.
- Null validation for enum/particle components and the `particle counts must be positive` validation remain in place.
- No particle definitions, count values, Paper calls, gameplay logic, SQL, schema, or plugin wiring were changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 97 XML test suites, 325 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `0D1E6A794FEB0777D53EA7AEA81801C4682D04F755B34276EA01F77B9A64FFD2`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- `TowerEffectDefinition.class`: Kotlin output only, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
