---
title: "Kotlin B9 resource voucher repository manifest"
tags: [kotlin, java, migration, persistence, voucher, repository]
status: active
created: 2026-08-14
---

# Kotlin B9 resource voucher repository

This slice moves the optional team-bound resource voucher repository to production Kotlin after
the B8 block-change repository boundary. Database, schema migration, voucher records, wallet
repository, Paper adapters, recovery callers, and the plugin entry point remain unchanged.

## Baseline

- Baseline: B8 final commit `ac4c827f20932acba0343484610423b75490a5a7`
- Branch: `feat/kotlin-b9-resource-voucher-repository-abo`
- Implementation commit: `6600ae15d75a1ecc8021569960598d1db70304b6`
- Kotlin/JVM plugin: `2.4.10`; Gradle wrapper: `9.6.0`; Java/JVM target: `25`

## Production changes

- Replace `ResourceVoucherRepository.java` with `ResourceVoucherRepository.kt`.
- Preserve the public `ResourceVoucherRepository(Database)` constructor and fifteen public
  instance methods for withdrawal, delivery preparation/application/rollback, redeem recovery,
  redeem preparation/application/rollback, lookup, and live-voucher checks.
- Preserve the package transaction hook `hasLiveVouchers(Connection, UUID)`. Kotlin emits it as an
  additive public static method through `@JvmStatic`; the ABI test fixes its descriptor and checked
  `SQLException` declaration.
- Keep wallet debit/credit and voucher creation in one transaction, recipient binding, active-event
  and prepared-core-placement guards, delivery/redeem state transitions, SQL text, bind order,
  `BEGIN IMMEDIATE` boundaries, operation UUID idempotency, and payload-fingerprint conflict checks
  unchanged.
- Preserve the rule that delivery rollback does not credit the wallet, redeem rollback only
  releases a reserved voucher before physical receipt application, and a redeemed voucher credits
  the wallet exactly once.
- Add `ResourceVoucherRepositoryKotlinBoundaryAbiTest` for constructor, public methods, static
  hook visibility, return types, and checked-exception declarations.

No `Database`, `SchemaMigrator`, SQL schema, voucher record, wallet repository, Paper adapter,
listener, recovery policy, or plugin entry-point implementation was changed.

## Verification

Command:

```text
./gradlew.bat clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
```

Result at implementation commit `6600ae15d75a1ecc8021569960598d1db70304b6`:

```text
BUILD SUCCESSFUL
78 test XML files, 296 tests, 0 failures, 0 errors, 0 skipped
```

The verification command and the HEAD check ran in the same shell; HEAD was
`6600ae15d75a1ecc8021569960598d1db70304b6` throughout the build. `git diff --check` was clean.

Generated ABI and artifact checks:

- `ResourceVoucherRepository` is emitted under `build/classes/kotlin/main` with JVM major version
  `69`; no `ResourceVoucherRepository.class` is emitted under `build/classes/java/main`.
- The public constructor, fifteen public instance methods, and their Java parameter/return types
  are fixed by the Java ABI test.
- The static `hasLiveVouchers(Connection, UUID)` hook is public, static, returns `boolean`, and
  declares `SQLException`; its former package-private visibility is intentionally widened by the
  Kotlin `@JvmStatic` boundary, as with earlier repository slices.
- The main plugin JAR SHA-256 is
  `D50FCDC1BB801DDBEAB7AF522048F2695EC048D4A3E8485CC7436C03A65D3165`.
- The processed `plugin.yml` SHA-256 is
  `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- The plugin JAR contains `1045` Kotlin runtime entries and no `kotlin-reflect` artifact entries.

Independent review and Paper GUI/start, restart, outcome, abort, and technical-recovery
acceptance remain separate follow-ups; this manifest does not claim those gates.
