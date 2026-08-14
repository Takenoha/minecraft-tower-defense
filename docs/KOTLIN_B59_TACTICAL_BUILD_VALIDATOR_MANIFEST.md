---
title: "Kotlin B59 Tactical Build Definition Validator Migration Manifest"
tags: [kotlin-migration, tactical-build, validator, abi]
status: active
created: 2026-08-14
---

# Kotlin B59 Tactical Build Definition Validator Migration Manifest

## Scope

- Base: `41fac05f08c61d850bb49ebc5a79a582b26abec1` (B58 final)
- Implementation and code-verification HEAD: `b59193286e0e059f51600cfc26483e4dbb0239d7`
- Migrated source: `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalBuildDefinitionValidator.java` → `src/main/kotlin/io/github/takenoha/towerdefense/tactical/TacticalBuildDefinitionValidator.kt`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalBuildDefinitionValidatorKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved contract

- `TacticalBuildDefinitionValidator` remains a public final utility class with a private no-argument constructor.
- Public static `validate(TacticalBuildDefinition)` and `validateAll(List<TacticalBuildDefinition>)` retain their Java descriptors, `void` return types, and public static visibility.
- Null/empty definition lists, null definitions, build and node slug checks, six-node/tier shape, duplicate IDs, prerequisite references and tiers, acyclic graph checks, branch metadata/topology, and effect value validation retain their original failure conditions and messages.
- The validator continues to run against the Java record model (`TacticalBuildDefinition`, `TacticalSkillNodeDefinition`, and `TacticalEffectEntry`), so record components and the tactical definition codec boundary are unchanged.
- Existing catalog, codec, repository, runtime, listener, and tactical tests/callers were not changed by this slice.

## Verification

The committed implementation HEAD was checked with an explicit Git safe-directory override; the same HEAD was observed before and after the build:

```text
HEAD_BEFORE=b59193286e0e059f51600cfc26483e4dbb0239d7
HEAD_AFTER=b59193286e0e059f51600cfc26483e4dbb0239d7
```

Command:

```text
gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
128 XML test reports
364 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-HEAD checks:

- `git diff --check` for the implementation worktree: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `A5DD9ED80F9932EF19018D6D48591112C08610AA930699E1AC6ECE4BEA3D2E72`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No `TacticalBuildDefinitionValidator.class` duplicate exists under `build/classes/java/main`.
- Packaged Kotlin output contains `TacticalBuildDefinitionValidator` and its `Companion`; both inspected class files are JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.
- The implementation worktree was clean before this documentation-only final commit.

## Known additive Kotlin surface

Kotlin generates a `Companion`, final static methods, nullable annotations/metadata, and private helper methods on the companion/outer class. The public methods preserve the Java utility boundary; contract-breaking null inputs may have Kotlin-generated NPE message/order differences in internal paths. These are additive interop differences, not migration blockers.

Paper real-server acceptance remains a separate gate; this slice is pure tactical validation and does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
