---
title: "Kotlin B2 core domain/config slice manifest"
tags: [kotlin, java, migration, core-domain, config]
status: active
created: 2026-08-13
---

# Kotlin B2 core domain/config slice

This manifest records the first production Kotlin migration after the compatibility spike.
The slice is limited to the Paper-independent core settings and repair-cost domain boundary.

## Baseline

- Branch: `feat/kotlin-b2-core-domain-abo`
- Baseline: `origin/main` at `1aaeb8a9baa1394158627200d1a4553de6449a6b`
- Formal Kotlin base: PR #31 merge `1aaeb8a9baa1394158627200d1a4553de6449a6b`
- Kotlin/Gradle baseline: Kotlin JVM plugin `2.4.10`, Gradle wrapper `9.6.0`, Java 25
- Previous spike evidence: `docs/KOTLIN_INTEROP_SPIKE_MANIFEST.md`

## Production changes

- Replace `config.CoreSettings` Java record with a Kotlin `@JvmRecord` data class.
- Replace `domain.CoreRepairCost` Java record with a Kotlin `@JvmRecord` data class.
- Keep the existing 12-component and 5-component Java record surfaces, canonical constructors,
  `CoreSettings` shortened constructors, `CoreRepairCost.forMissing(long,long,CoreSettings)`,
  record accessors, and `CoreSettings.DEFAULT_*` static fields.
- Add `kotlin-stdlib:2.4.10` to `implementation`; production Kotlin requires the runtime and the
  existing fat-JAR task packages it. `kotlin-reflect` is not added.
- No SQL, schema, codec, Paper API, plugin entry point, or main-thread boundary changed.

## Verification

Command:

```text
.\gradlew.bat clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel
```

Result:

```text
BUILD SUCCESSFUL
277 test cases, 0 failure/error elements; BUILD SUCCESSFUL
```

ABI and artifact checks:

- `CoreSettings.class` and `CoreRepairCost.class` are JVM records with class major version 69.
- Java ABI test: `src/test/java/io/github/takenoha/towerdefense/interop/CoreDomainKotlinJavaAbiTest.java`.
- Kotlin record/equivalence tests: `src/test/kotlin/io/github/takenoha/towerdefense/config/CoreSettingsJvmRecordTest.kt`
  and `src/test/kotlin/io/github/takenoha/towerdefense/domain/CoreRepairCostJvmRecordTest.kt`.
- `plugin.yml` SHA-256 remains
  `50EEC0225EA57EEBB4CDFF6D877C7AC02A9E65E4A6986969156CBA83F13178A6`.
- Fat plugin JAR SHA-256 is
  `130C0D84C31C86EFBFD11DDE9BD53137F3DB28393F7E152BC7B8C9E552794240`.
  The B1 spike JAR was `A3CE9BDE3B47AA092F9DE4F7B0BC7F3E981B830373A28E6BCA6F1D5A77278B53`;
  the expected difference is the packaged Kotlin runtime.
- Runtime dependency tree is `org.jetbrains.kotlin:kotlin-stdlib:2.4.10` (with
  `org.jetbrains:annotations:13.0`) plus `org.xerial:sqlite-jdbc:3.50.3.0`.
- The fat JAR contains Kotlin stdlib entries but no `kotlin-reflect` entries.
- `git diff --check` is clean.

## Acceptance boundary

Paper GUI/start/restart, outcome, abort, and technical-recovery acceptance were not run. This
slice has no Paper server artifact or disposable runtime; automatic Gradle success is not Paper
acceptance evidence.
