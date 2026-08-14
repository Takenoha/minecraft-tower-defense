---
title: "Kotlin B3 tactical codec boundary manifest"
tags: [kotlin, java, migration, tactical, codec]
status: active
created: 2026-08-14
---

# Kotlin B3 tactical codec boundary

This slice moves the existing versioned tactical definition snapshot codec to production Kotlin
after the B2 core domain/config migration. The public branch records remain Java compatibility
boundaries so their canonical constructors continue to perform the existing `List.copyOf`
defensive copies. The feature contract, database schema, Paper integration, and tactical runtime
are unchanged.

## Baseline

- Branch: `feat/kotlin-b3-tactical-codec-abo`
- Baseline: B2 PR #32 merge commit `0cba95a6d3b6c46f1beca6169b157a867f846fab`
- Kotlin/JVM plugin: `2.4.10`; Gradle wrapper: `9.6.0`; Java/JVM target: `25`
- Existing branch feature: `tdb1` linear snapshots and `tdb2` branch-metadata snapshots

## Production changes

- Keep `TacticalSkillNodeDefinition` and `TacticalSkillNodeSnapshot` as Java records, preserving
  their nine-component shape, canonical Java constructor, six-argument legacy constructor, record
  accessors, `snapshot()`, and canonical defensive copies.
- Replace `TacticalDefinitionCodec` with a Kotlin class exposing the same Java static
  `encode(TacticalBuildDefinition)` and `decode(String)` boundary.
- Keep Kotlin away from the public collection-bearing record canonical constructors: Kotlin
  `@JvmRecord` requires `val` components and cannot reassign them to `List.copyOf` in `init`.
- Preserve deterministic ordering, binary payload layout, `tdb1` backward readability, and
  `tdb2` branch metadata encoding.
- Keep SQL/schema, `TacticalBuildRepository`, Paper GUI/listeners, runtime/compiler behavior, and
  plugin entry-point wiring unchanged.

## Verification

Command:

```text
./gradlew.bat --system-prop kotlin.compiler.execution.strategy=in-process clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
```

Result:

```text
BUILD SUCCESSFUL
281 tests, 0 failures, 0 errors, 0 skipped
```

Boundary checks:

- Java ABI test: `src/test/java/io/github/takenoha/towerdefense/interop/TacticalKotlinBoundaryAbiTest.java`.
- The test verifies both JVM record component lists and canonical/legacy constructors, the Java
  static codec methods, a full `tdb2` round trip, and exact re-encoding of a pre-migration `tdb1`
  golden fixture.
- Direct pre/post codec output hashes match for the existing catalog: `rapid-fire` UTF-8 output
  length 1285, SHA-256 `bb837a470866bbadd3027615312332a4f84001a724cba8162ee8aad92ca28e04`;
  `arrow-specialization` UTF-8 output length 1954, SHA-256
  `1616a4117b321c04244baa2c0388a8f6a69d4eb03a8c30e8fdd4279ef2f37040`.
- Generated classes are JVM major version 69; fat JAR contains Kotlin stdlib and no
  `kotlin-reflect` entries.
- Fat JAR SHA-256:
  `9ACF37F3D1D1D45EEB0C657EA810E1C299EBE6ECF93937C59090B3A73EC5AB3F`.
- `plugin.yml` SHA-256:
  `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- `git diff --check` is clean.
- The in-process compiler setting keeps this release-gate run isolated from other worktrees'
  Kotlin daemon sessions; it does not change production compiler or runtime configuration.

## Acceptance boundary

Paper GUI/start/restart, outcome, abort, and technical-recovery acceptance remain outside this
Kotlin conversion slice. Automatic Gradle success is not Paper runtime acceptance evidence.
