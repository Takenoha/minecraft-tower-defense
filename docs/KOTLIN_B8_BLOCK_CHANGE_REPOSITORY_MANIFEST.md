---
title: "Kotlin B8 block change repository manifest"
tags: [kotlin, java, migration, persistence, block-change, repository]
status: active
created: 2026-08-14
---

# Kotlin B8 block change repository

This slice moves the event-owned block-change write-ahead repository to production Kotlin after
the B7 resource repository boundary. Database, schema migration, block records, Paper adapters,
event recovery callers, and the plugin entry point remain unchanged.

## Baseline

- Baseline: B7 final commit `0c114681b117c30f995ff253d492fc8872afe468`
- Branch: `feat/kotlin-b8-block-change-repository-abo`
- Implementation commit: `258ab1bcf36abd7e28c98cba2a411db3a877ef3b`
- Kotlin/JVM plugin: `2.4.10`; Gradle wrapper: `9.6.0`; Java/JVM target: `25`

## Production changes

- Replace `BlockChangeRepository.java` with `BlockChangeRepository.kt`.
- Preserve the public `BlockChangeRepository(Database)` constructor and ten public instance
  methods: prepare/apply generation and physical-operation methods, rollback methods, ledger loads,
  unresolved temporary-block counting, and prepared-rollback recovery loading.
- Preserve the Java static connection hooks used by `DefenseRepository`:
  `hasUnresolved` and `settleAppliedEventBlocks`.
- The formerly package-private static hooks are intentionally emitted as additive `public static`
  methods through Kotlin `@JvmStatic`. The ABI test fixes their descriptors and checked
  `SQLException` declarations.
- Keep the event block ledger SQL, column mapping, bind order, `BEGIN IMMEDIATE` transaction
  boundaries, generation overflow guard, active/terminal event guard, operation UUID idempotency,
  fingerprint conflict checks, recovery rollback decisions, terminal settlement, and unresolved
  temporary-block protection unchanged.
- Add `BlockChangeRepositoryKotlinBoundaryAbiTest` for constructor, instance methods, static hooks,
  return types, and checked-exception declarations.

No `Database`, `SchemaMigrator`, SQL schema, block record, `DefenseRepository`, Paper adapter,
listener, codec, or plugin entry-point implementation was changed.

## Verification

Command:

```text
./gradlew.bat clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
```

Result at implementation commit `258ab1bcf36abd7e28c98cba2a411db3a877ef3b`:

```text
BUILD SUCCESSFUL
77 test XML files, 294 tests, 0 failures, 0 errors, 0 skipped
```

The verification command and the HEAD check ran in the same shell; both before and after the build
reported `258ab1bcf36abd7e28c98cba2a411db3a877ef3b`. `git diff --check` was clean.

Generated ABI and artifact checks:

- `BlockChangeRepository` is emitted under `build/classes/kotlin/main` with JVM major version
  `69`; no `BlockChangeRepository.class` is emitted under `build/classes/java/main`.
- The public constructor and ten public instance methods retain their Java parameter and return
  types.
- Both Java static hooks retain their parameter/return descriptors and declare `SQLException`.
- The main plugin JAR SHA-256 is
  `794309974CF27900B1B9CD82F48515C080FCC08D20F5256CA697A31EBACBF1DC`.
- The processed `plugin.yml` SHA-256 is
  `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- The plugin JAR contains `1045` Kotlin runtime entries and no `kotlin-reflect` artifact entries.

Independent review and Paper GUI/start, restart, outcome, abort, and technical-recovery acceptance
remain separate follow-ups; this manifest does not claim those gates.
