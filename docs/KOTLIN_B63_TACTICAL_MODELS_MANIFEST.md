---
title: "Kotlin B63 Tactical Model Migration Manifest"
tags: [kotlin-migration, tactical-build, models, abi]
status: active
created: 2026-08-14
---

# Kotlin B63 Tactical Model Migration Manifest

## Scope

- Base: `d3672bf06f9bebdc64b27d284a3a8394cfb2fd24` (B62 final)
- Implementation and code-verification HEAD: `867b6d59466a42393f5373aee1cc9c6521578992`
- Migrated enum sources: `TacticalBuildCategory`, `TacticalBuildRarity`, `TacticalEffectType`, and `TacticalTargetCondition`
- Migrated record sources: `TacticalCandidate` and `TacticalTargetContext`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalModelsKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved contract

- All four tactical enums retain their exact Java names, order, `values()`/`valueOf()` behavior, and caller-facing type identity.
- `TacticalCandidate` remains a JVM `Record` with components `(int slot, TacticalBuildDefinition definition)`, a public canonical constructor, the original slot range validation, and the original definition null guard.
- `TacticalTargetContext` remains a JVM `Record` with the five original components, public canonical constructor/accessors, `neutral()`, high/low-health helpers, core-threshold helpers, and the original finite `[0,1]` validation messages.
- Existing Kotlin callers that consumed the newly Kotlin-backed records were changed only from Java accessor-call syntax to Kotlin property syntax; their Java-facing boundaries and behavior are unchanged.
- `TacticalCandidateGenerator`, `TacticalBuildCatalog`, `TacticalBuildDefinitionValidator`, compiler/cache/runtime, codec, repository, and Paper selection callers retain their existing descriptors and operation boundaries.

## Verification

The implementation commit was tested at a fixed HEAD. The test invocation was:

```text
.\gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
132 XML test reports
374 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-artifact checks:

- `git diff --check`: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `6AD7FC6D5342C2F5D913BEE25A744EDC7DCBD58D9207AE9406DFDB5BF7672BFB`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No Java duplicate exists for any of the six migrated tactical model classes under `build/classes/java/main`.
- The four enum classes and two record outer classes are JVM major 69; `TacticalTargetContext$Companion` is also JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.

## Known additive Kotlin surface

The two `@JvmRecord data class` types gain Kotlin `componentN`/`copy` helpers, Kotlin data-class `toString()` formatting, final accessors, and nullable annotations/metadata; `TacticalTargetContext` also has a `Companion`. These are additive generated surfaces. Contract-breaking null inputs can have Kotlin-generated NPE message/order differences. The Java record components, constructors, accessors, validation, enum order, and current caller behavior are preserved.

Paper real-server acceptance remains a separate gate; this slice is tactical model ABI and does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
