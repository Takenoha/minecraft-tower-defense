---
title: "Kotlin B11 tower repository manifest"
tags: [kotlin, java, migration, persistence, tower, repository]
status: active
created: 2026-08-14
---

# Kotlin B11 TowerRepository migration

## Scope

- Base: `59408b52a1f3524be3eb1e84db2b22557bbad379` (B10 final)
- Branch: `feat/kotlin-b11-tower-repository-abo`
- Implementation commit: `7cedb8306aa56e955e59d03746e070ff605bd392`
- Production boundary migrated: `TowerRepository.java` to `TowerRepository.kt`
- Java ABI coverage: `TowerRepositoryKotlinBoundaryAbiTest.java`
- Toolchain: Kotlin/JVM, Gradle 9.6, Java 25, plugin version `0.1.0-SNAPSHOT`

## Boundary preserved

The public Java-facing constructor and 28 instance methods remain available with the same argument and return descriptors. The ABI test covers tower loading/research, upgrade and receipt handoff, target priority, placement, removal, rollback, and reconciliation methods.

The migration does not change schema definitions, migrations, Paper wiring, plugin entry points, or the persistence transaction helper.

## Persistence invariants checked

- `Database.inImmediateTransaction` remains the write transaction boundary; read methods continue to use an opened read connection.
- Research purchases retain team membership, active-event exclusion, positive-cost and level-overflow guards, wallet decrement, operation UUID fingerprint idempotency, and atomic progress/research updates.
- Tower upgrades retain placement-window and membership guards, level compare-and-set, legacy material receipt states, point-wallet debit operations, payment-mode separation, rollback, and terminal receipt reconciliation.
- Tower placement and removal retain capacity/research checks, event-window guards, physical identity and coordinate uniqueness checks, operation idempotency, compare-and-set updates, and rollback/applied recovery states.
- Target-priority updates retain membership validation and no-op idempotency; row decoders preserve persisted UUID, enum, timestamp, hit-point, and nullable terminal fields.

## Fixed implementation HEAD verification

The clean verification was run at implementation commit `7cedb8306aa56e955e59d03746e070ff605bd392`:

- `clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`: `BUILD SUCCESSFUL`
- Test reports: 80 XML files, 299 tests, 0 failures, 0 errors, 0 skipped
- Generated production class: Kotlin class present; duplicate Java class absent
- JVM class major: 69
- Packaged JAR SHA-256: `0F3F15A490482B17F085BE0FFFA158BEBEAC449D2D07877D913B8C7E664D80CD`
- Packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Embedded Kotlin runtime entries: 1045
- Kotlin-reflect artifact entries (`kotlin/reflect/jvm/internal/*`): 0
- `git diff --check`: clean

Independent fixed-HEAD review and Paper GUI/start, restart, outcome, abort, and technical-recovery acceptance remain separate follow-ups; this manifest does not claim those gates.
