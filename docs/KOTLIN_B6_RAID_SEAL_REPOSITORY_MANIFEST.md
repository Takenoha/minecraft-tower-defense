---
title: "Kotlin B6 raid seal repository manifest"
tags: [kotlin, java, migration, persistence, raid-seal, repository]
status: active
created: 2026-08-14
---

# Kotlin B6 raid seal repository

This slice moves the raid-seal repository implementation to production Kotlin after the B5
persistence type boundary migration. The database, schema migration, repository callers, Paper
wiring, and plugin entry point remain Java and unchanged.

## Baseline

- Baseline: B5 merge commit `41832b445f6a1e83c9d1524c437009e6184e6922`
- Branch: `feat/kotlin-b6-raid-seal-repository-abo`
- Implementation commit: `8c7c8ba359beecad4c624faed9931c69612f444f`
- Kotlin/JVM plugin: `2.4.10`; Gradle wrapper: `9.6.0`; Java/JVM target: `25`

## Production changes

- Replace `RaidSealRepository.java` with `RaidSealRepository.kt`.
- Preserve the public `RaidSealRepository(Database)` constructor, registration, reservation,
  consumption, refund, lookup, owner-list, and refundable-list method signatures.
- Preserve the Java static transaction hook descriptors used by `DefenseRepository`:
  `consumeForStart`, `reserveForStart`, `consumeReservedForStart`, `refund`, and
  `refundIfPresent`. The hooks retain their `SQLException` declarations through `@Throws` and
  are emitted as Java static methods via `@JvmStatic`. Kotlin/JVM has no package-private member
  visibility, so these formerly package-private hooks are intentionally emitted as additive
  `public static final` methods; the ABI test fixes that visibility decision.
- Keep the existing SQL table/column selections, bind order, `BEGIN IMMEDIATE` transaction
  boundary, operation UUID idempotency, active/terminal event checks, and technical-refund
  behavior. The original seal UUID remains `REFUNDED`; recovery creates a deterministic fresh
  available UUID.
- Add `RaidSealRepositoryKotlinBoundaryAbiTest` for Java constructor, method, static-hook, return,
  and checked-exception signatures.

No `Database`, `SchemaMigrator`, SQL schema, codec, Paper listener, plugin wiring, or unrelated
repository implementation was changed.

## Verification

Command:

```text
./gradlew.bat clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
```

Result at implementation commit `8c7c8ba359beecad4c624faed9931c69612f444f`:

```text
BUILD SUCCESSFUL
75 test XML files, 289 tests, 0 failures, 0 errors, 0 skipped
```

Generated ABI checks:

- `RaidSealRepository` is emitted under `build/classes/kotlin/main` with JVM major version `69`;
  no `RaidSealRepository.class` is emitted under `build/classes/java/main`.
- The public constructor and seven public instance methods retain their Java parameter and return
  types, including `Optional<RaidSeal>` and `List<RaidSeal>`.
- The five Java static transaction hooks retain their parameter/return types and declare
  `SQLException`; their intentional additive `public static` visibility is asserted by the ABI
  test.
- Existing Java callers compile and execute unchanged, including `DefenseRepository`,
  `TowerDefensePlugin`, `RaidSealListener`, `RaidSealStartPersistenceTest`, and
  `RollbackEscrowPersistenceTest`.

Artifact checks:

- Fat JAR SHA-256: `E49E793594128A6EE85DED00428D6EE0E0CE2B9949E36CE5529397EF37CCA58E`.
- Processed `plugin.yml` SHA-256:
  `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Fat JAR contains `1045` Kotlin runtime entries and no `kotlin-reflect` artifact entries
  (`kotlin/reflect/jvm/internal` or `kotlin-reflect`-named entries).
- `git diff --check` is clean at the implementation commit.

Vbk's independent read-only review of implementation commit `8c7c8ba359beecad4c624faed9931c69612f444f`
found no blocker and confirmed the SQL, transaction, idempotency, refund, Java caller, ABI, and
artifact boundaries. The package-private-to-public hook visibility is the documented non-blocking
follow-up. Paper GUI/start, restart, outcome, abort, and technical-recovery acceptance remain
outside this Kotlin conversion and are not claimed by the Gradle result.
