---
title: "Kotlin B61 Tactical Candidate Generator Migration Manifest"
tags: [kotlin-migration, tactical-build, candidate-generation, abi]
status: active
created: 2026-08-14
---

# Kotlin B61 Tactical Candidate Generator Migration Manifest

## Scope

- Base: `e218d50d8a69e87603cd43615ddbc90c58182476` (B60 final)
- Implementation and code-verification HEAD: `b4af43781d3d384dec5163a27925604c1326dfc7`
- Migrated source: `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalCandidateGenerator.java` → `src/main/kotlin/io/github/takenoha/towerdefense/tactical/TacticalCandidateGenerator.kt`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalCandidateGeneratorKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved contract

- `TacticalCandidateGenerator` remains a public final class with its public no-argument constructor, public `generate(...)` instance method, public static `seedFor(...)`, and public static final `CANDIDATE_COUNT=3` field.
- Input null guards, positive stage/generator-version validation, full definition validation, enabled/weighted pool filtering, stable ID ordering, weighted sampling, deterministic seed mixing, and the minimum-three-build failure remain unchanged.
- Weighted integer overflow continues to raise `IllegalArgumentException("tactical candidate weights overflow", cause)`, and an exhausted weighted draw continues to raise the same `IllegalStateException`.
- Candidate category diversity repair, slot ordering, `TacticalCandidate` construction, and `TacticalCandidateSet` validation/copy boundaries remain unchanged.
- Existing callers (`TowerDefensePlugin`, `TacticalBuildSelectionListener`, and tactical foundation tests), validator, catalog, records, persistence, and runtime boundaries were not changed by this slice.

## Verification

The implementation commit was tested at a fixed HEAD. The test invocation was:

```text
.\gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
130 XML test reports
369 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-artifact checks:

- `git diff --check`: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `09DD8FEB80BDB90909E9A5C5EA991766E557462BC23748298B677D9E18D45D3B`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No `TacticalCandidateGenerator.class` duplicate exists under `build/classes/java/main`.
- Packaged Kotlin output contains `TacticalCandidateGenerator.class` and `TacticalCandidateGenerator$Companion.class`; both inspected class files are JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.

## Known additive Kotlin surface

Kotlin generates a `Companion`, final method modifiers, nullable annotations/metadata, and a companion copy of the static helper. The `@JvmField` constant has the required Java field descriptor, visibility, final modifier, and value; it is initialized by Kotlin class initialization rather than Java `ConstantValue` metadata. Contract-breaking null inputs can have Kotlin-generated NPE message/order differences in internal paths. These are additive interop differences, not migration blockers.

Paper real-server acceptance remains a separate gate; this slice is deterministic candidate generation and does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
