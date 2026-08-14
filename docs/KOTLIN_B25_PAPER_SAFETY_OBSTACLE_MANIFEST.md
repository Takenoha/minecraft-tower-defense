---
title: "Kotlin B25 Paper safety and obstacle manifest"
tags: [kotlin, java, migration, paper, main-thread, safety, obstacle]
status: active
created: 2026-08-14
---

# Kotlin B25 Paper safety and obstacle manifest

## Scope

- Base: `feat/kotlin-b24-paper-block-codecs-abo` at `3062efb`
- Implementation commit: `0f8a6f9361796a4f9ad4de72e7f16b3568fe22a1`
- Migrated boundaries: `ThirdPartyRegionProtectionAdapter`, `PaperCombatAreaSafetyValidator`, and `PaperEnemyObstacleClassifier`
- Java sources for the three migrated boundaries removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperSafetyObstacleKotlinBoundaryAbiTest.java`
- WorldGuard reflection implementation, runtime session orchestration, terrain mutation, schema, and repository code were not changed.

## Boundary and invariants

- `ThirdPartyRegionProtectionAdapter` remains a Java-compatible functional interface with `none()` and fail-closed `unavailable(String)` static factories.
- `PaperCombatAreaSafetyValidator` preserves both overloads, the nullable unloaded-world result, world-border snapshot construction, region-probe delegation, and immutable violation results.
- `PaperEnemyObstacleClassifier` preserves the main-thread guard, candidate/support reads, core/inventory/tile protection checks, combat-area authorization, and delegation to the Paper-independent classifier.
- Existing Java lambda implementations and callers continue to use the same descriptors and static factories without adapters.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `0f8a6f9361796a4f9ad4de72e7f16b3568fe22a1`:

- `BUILD SUCCESSFUL`
- 94 XML test reports / 321 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- Kotlin safety/obstacle classes present; Java duplicate classes absent
- packaged JAR SHA-256: `893FF9C5112BFCB8F17BF6634C7D1971279AA1DA73C40B673E88FB2F25F594A8`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
