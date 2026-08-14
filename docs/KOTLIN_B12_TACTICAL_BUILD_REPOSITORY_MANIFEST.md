---
title: "Kotlin B12 tactical build repository manifest"
tags: [kotlin, java, migration, persistence, tactical, repository]
status: active
created: 2026-08-14
---

# Kotlin B12 tactical build repository manifest

## Scope

- Base: `feat/kotlin-b11-tower-repository-abo` at `886414c6f4fa6c9f39680c7427088feface3c042`
- Implementation commit: `a901b1ec35389d1bdebec905d2eb9627599a9551`
- Migrated boundary: `TacticalBuildRepository`
- Java source removed: `src/main/java/io/github/takenoha/towerdefense/persistence/TacticalBuildRepository.java`
- Kotlin source added: `src/main/kotlin/io/github/takenoha/towerdefense/persistence/TacticalBuildRepository.kt`
- Java ABI test added: `TacticalBuildRepositoryKotlinBoundaryAbiTest.java`
- Schema, Paper wiring, and tactical domain records were not changed.

## Boundary and invariants

- `TacticalBuildRepository(Database)` retains the Java constructor descriptor.
- The public Java boundary retains the 12 method descriptors, including both `selectBuild` overloads.
- Candidate generation preserves deterministic snapshots, start-operation idempotency, team existence checks, and payload fingerprints.
- Selection preserves owner authorization, selected-branch validation, snapshot persistence, operation UUID idempotency, and the generated-to-selected compare-and-set.
- Binding and cancellation preserve defense/team matching, state guards, operation UUID idempotency, and the selected/generated state transitions.
- Unlock progression preserves active-session guards, terminal/cancelled no-op behavior, tier thresholds, selected-branch filtering, prerequisite closure, and compare-and-set tier updates.
- Terminal marking preserves active/recovery-hold guards, terminal-result idempotency, and the terminal state transition.
- Writes remain inside `Database.inImmediateTransaction`; read paths continue to use an opened connection.

## Static migration checks

Against the B11 base Java implementation, the Kotlin implementation has matching call-site counts for `prepareStatement` (21), `executeQuery` (12), `executeUpdate` (9), `setString` (48), `setInt` (8), and `setLong` (1). The six write transaction call sites and one read connection boundary are retained. `git diff --check` is clean.

## Build and artifact verification

The clean verification build completed successfully:

- `BUILD SUCCESSFUL`
- 81 XML test reports / 300 tests
- failures 0 / errors 0 / skipped 0
- Kotlin production class present; Java duplicate class absent
- JVM class major 69
- packaged JAR SHA-256: `508161DA9A48EC450E710742D3B92EC571B491EA5B6104BC47824546B436A300`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin entries: 1045
- embedded `kotlin-reflect` artifact entries: 0

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
