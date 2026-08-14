---
title: "Kotlin B5 persistence types manifest"
tags: [kotlin, java, migration, persistence, records, enums]
status: active
created: 2026-08-14
---

# Kotlin B5 persistence types

This slice moves low-dependency persistence outcomes, ledger-state enums, result records, and the
prepared rollback record to production Kotlin after the B4 runtime/compiler boundary migration.
SQL, schema migration, repository implementations, codec, Paper integration, and the plugin entry
point remain unchanged.

## Baseline

- Baseline: B4 merge commit `814745bf2dbd9a0d7bd9e8dc45ad0c29c893d43d`
- Branch: `feat/kotlin-b5-persistence-types-abo`
- Fixed implementation HEAD: `033fa289a8961b5a2ce42e3e2ce1793a928ff7c6`
- Kotlin/JVM plugin: `2.4.10`; Gradle wrapper: `9.6.0`; Java/JVM target: `25`

## Production changes

- Replace the Java `OperationOutcome`, `ManagementOutcome`, and `StartOutcome` enums with Kotlin
  enums while preserving constant order and names.
- Replace the Java `BlockChangeKind`, `BlockChangeStatus`, `DropSourceKind`, `PaymentMode`,
  `RewardQueueScope`, and `RewardQueueStatus` enums with Kotlin enums.
- Replace `CorePlacementResult`, `CoreMutationResult`, `TeamInvitationMutationResult`,
  `TeamMutationResult`, `ResourceMutationResult`, `BattleFundsMutationResult`,
  `VoucherWithdrawalResult`, `RaidSealRefundResult`, and `PreparedRollback` with Kotlin
  `@JvmRecord` data classes.
- Add `PersistenceKotlinBoundaryAbiTest` for enum values, record components/accessors, and
  required-component null rejection.

## Compatibility boundaries

- All migrated records remain JVM records with their original public component names, canonical
  constructor parameter types, and Java record accessors.
- Existing Java repository, runtime, Paper, and test callers compile and execute against the same
  fully-qualified type names. No SQL strings, bind order, schema version, transaction, or
  restart-recovery path was edited.
- The Kotlin `@JvmRecord` data classes expose additional Kotlin `componentN`/`copy` methods and
  use Kotlin's generated record `toString()` form, as already accepted for the earlier B2 record
  slice. A source search found no result-record `toString()` contract in this repository.
- Kotlin-generated enum classes retain Java `values()`, `valueOf(String)`, and public constants;
  the Kotlin `entries` accessor is an additive method.

## Verification

Command:

```text
./gradlew.bat clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
```

Result at fixed HEAD `033fa289a8961b5a2ce42e3e2ce1793a928ff7c6`:

```text
BUILD SUCCESSFUL
74 test XML files, 287 tests, 0 failures, 0 errors, 0 skipped
```

Generated ABI checks:

- All 17 migrated classes are JVM major version `69`.
- The nine enums expose the expected Java constants and `values()`/`valueOf(String)` methods.
- The eight result/rollback records expose the expected record component names/accessors and
  reject null required components from Java.
- No migrated class was emitted under `build/classes/java/main`; all 17 are emitted under
  `build/classes/kotlin/main`.

Artifact checks:

- Fat JAR SHA-256: `2EA60D57600C72A64C0C5B7F40AE879A075E0EFB82116A0A88ED4107FCAE5E91`.
- Processed `plugin.yml` SHA-256:
  `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Fat JAR contains `1045` Kotlin runtime entries and `0` `kotlin-reflect` entries.
- `git diff --check` is clean.

Paper GUI/start, restart, outcome, abort, and technical-recovery acceptance remain outside this
Kotlin conversion. The next persistence slice is a small repository boundary; `Database` and
`SchemaMigrator` remain Java stability boundaries for that work.
