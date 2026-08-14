---
title: "Kotlin B7 resource repository manifest"
tags: [kotlin, java, migration, persistence, resource, repository]
status: active
created: 2026-08-14
---

# Kotlin B7 resource repository

This slice moves the team resource-wallet repository implementation to production Kotlin after
the B6 raid-seal repository boundary. The database, schema migration, repository callers, escrow
and voucher repositories, Paper wiring, and plugin entry point remain Java and unchanged.

## Baseline

- Baseline: B6 final commit `df6b8cbe72bd17c519810cea4493509463a1c3e0`
- Branch: `feat/kotlin-b7-resource-repository-abo`
- Implementation commit: `5872c5366e5e4e7b3893f072167f8b56304ddce3`
- Kotlin/JVM plugin: `2.4.10`; Gradle wrapper: `9.6.0`; Java/JVM target: `25`

## Production changes

- Replace `ResourceRepository.java` with `ResourceRepository.kt`.
- Preserve the public `ResourceRepository(Database)` constructor and the five public instance
  methods for loading settlements/feedback and crediting/debiting resource wallets.
- Preserve the Java static connection hooks used by `EscrowRepository`, `ResourceVoucherRepository`,
  `TowerRepository`, and `DefenseRepository`: `loadPickupFeedback`, `settleForTerminal`,
  `settleForRecovery`, `debitInTransaction`, `creditInTransaction`, `isWalletResource`, `balance`,
  `loadEventTeam`, and `requireTeamMember`.
- The formerly package-private static hooks are intentionally emitted as additive `public static`
  methods because Kotlin/JVM has no package-private member visibility. The ABI test fixes the
  descriptors, return types, and checked `SQLException` declarations for the SQL hooks, and also
  verifies the nullable `isWalletResource(null)` behavior.
- Keep the existing wallet-row repair, legacy reward-queue migration, SQL strings, bind order,
  `BEGIN IMMEDIATE` transaction boundaries, operation UUID idempotency, balance underflow/overflow
  guards, terminal settlement amounts, and recovery zero-credit behavior.
- Add `ResourceRepositoryKotlinBoundaryAbiTest` for Java constructor, instance method, static hook,
  null, return, and checked-exception signatures.

No `Database`, `SchemaMigrator`, SQL schema, codec, caller, Paper listener, or unrelated repository
implementation was changed.

## Verification

Command:

```text
./gradlew.bat clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
```

Result at implementation commit `5872c5366e5e4e7b3893f072167f8b56304ddce3`:

```text
BUILD SUCCESSFUL
76 test XML files, 292 tests, 0 failures, 0 errors, 0 skipped
```

The verification command and the HEAD check ran in the same shell; both before and after the build
reported `5872c5366e5e4e7b3893f072167f8b56304ddce3`.

Generated ABI checks:

- `ResourceRepository` is emitted under `build/classes/kotlin/main` with JVM major version `69`;
  no `ResourceRepository.class` is emitted under `build/classes/java/main`.
- The public constructor and five public instance methods retain their Java parameter and return
  types.
- The nine Java static hooks retain their parameter/return descriptors. The eight SQL hooks declare
  `SQLException`; `isWalletResource(String)` remains a non-throwing boolean probe.
- Existing Java callers compile and execute unchanged, including `DefenseRepository`,
  `EscrowRepository`, `ResourceVoucherRepository`, `TowerRepository`, and
  `ResourceRepositoryTest`.

Artifact checks:

- Fat JAR SHA-256: `3B8E931D03A50CE7B8956FC5E4F20E74C62F5436E1CAD44A0075E44EC3FC8C5A`.
- Processed `plugin.yml` SHA-256:
  `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Fat JAR contains `1045` Kotlin runtime entries and no `kotlin-reflect` artifact entries
  (`kotlin/reflect/jvm/internal` or `kotlin-reflect`-named entries).
- `git diff --check` is clean at the implementation commit.

Independent review and Paper GUI/start, restart, outcome, abort, and technical-recovery acceptance
remain separate follow-ups; this manifest does not claim those gates.
