---
title: "Kotlin B26 Paper enemy path manifest"
tags: [kotlin, java, migration, paper, path, terrain, main-thread]
status: active
created: 2026-08-14
---

# Kotlin B26 Paper enemy path manifest

## Scope

- Base: `feat/kotlin-b25-paper-safety-obstacle-abo` at `468e4c3`
- Implementation commit: `1d541c778ded5df03cfd1f16e286c03dc119caac`
- Migrated boundaries: `PaperEnemyPathController` and `PaperEnemyPathIntegrationBoundary`
- Java sources for the two migrated boundaries removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperEnemyPathKotlinBoundaryAbiTest.java`
- Terrain mutation adapter/action, runtime session lifecycle, schema, and repository code were not changed.

## Boundary and invariants

- Path inspection, destroyer break planning, builder bridge planning, main-thread guards, world matching, target/support selection, obstacle admission, observed-before snapshots, and no-world mutation behavior remain unchanged.
- Nested `BridgeCandidate` and `BreakCandidate` remain actual Java records with the same component names/order, constructors, and accessors.
- `PaperEnemyPathIntegrationBoundary` retains constructor/inspect descriptors, runtime-failure-to-unavailable conversion, failure metrics, and elapsed-time metrics.
- Existing Java terrain callers continue to use the static controller methods, nested records, and integration instance without adapters.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `1d541c778ded5df03cfd1f16e286c03dc119caac`:

- `BUILD SUCCESSFUL`
- 95 XML test reports / 322 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- Kotlin path classes present; Java duplicate classes absent
- packaged JAR SHA-256: `9C6F219F6D5763D2DCA3AB71361DD4E6CF9956B9FD323F2773A45F4781D731AB`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
