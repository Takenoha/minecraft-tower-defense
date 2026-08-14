---
title: "Kotlin B60 Tactical Build Runtime Migration Manifest"
tags: [kotlin-migration, tactical-build, runtime, abi]
status: active
created: 2026-08-14
---

# Kotlin B60 Tactical Build Runtime Migration Manifest

## Scope

- Base: `764a4f8cd4b593079ca54d6b5741ed297812e783` (B59 final)
- Implementation and code-verification HEAD: `d8a7b8cd6951837b3898ac55c0895c137da2412f`
- Migrated source: `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalBuildRuntime.java` → `src/main/kotlin/io/github/takenoha/towerdefense/tactical/TacticalBuildRuntime.kt`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalBuildRuntimeKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved contract

- `TacticalBuildRuntime` remains a public final implementation of `TacticalEffectSnapshotProvider`.
- Both public constructors remain available: `(TacticalBuildLifecycle, TacticalBuildStateProvider)` and `(TacticalBuildLifecycle, TacticalEffectCache)`.
- Public static `disabled()` and the public lifecycle/cache methods retain their Java descriptors, return types, and operation order.
- Preparation, wave advancement, final-tier activation, rebuild, invalidate, terminal handling, current snapshot lookup, and cache access retain the selected-build guard and cache rebuild/invalidation boundaries.
- The disabled runtime remains neutral and idempotent, returning `TacticalUnlockResult.unchanged(0)` and using an empty state provider.
- Existing `TacticalBuildLifecycle`, `TacticalBuildStateProvider`, `TacticalEffectCache`, `TacticalEffectSnapshotProvider`, and caller/runtime boundaries were not changed by this slice.

## Verification

The committed implementation HEAD was checked with an explicit Git safe-directory override; the same HEAD was observed before and after the build:

```text
HEAD_BEFORE=d8a7b8cd6951837b3898ac55c0895c137da2412f
HEAD_AFTER=d8a7b8cd6951837b3898ac55c0895c137da2412f
```

Command:

```text
gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
129 XML test reports
367 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-HEAD checks:

- `git diff --check` for the implementation worktree: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `06B187750840A12C4D99F9BDC0EDD65ADCE308A2BF028916C4DD2F2A7C9DA050`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No `TacticalBuildRuntime.class` duplicate exists under `build/classes/java/main`.
- Packaged Kotlin output contains the runtime outer/companion and two disabled-runtime anonymous implementations; all four inspected class files are JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.
- The implementation worktree was clean before this documentation-only final commit.

## Known additive Kotlin surface

Kotlin generates a `Companion`, final method modifiers, nullable annotations/metadata, and anonymous implementation classes for the disabled runtime. Contract-breaking null inputs can have Kotlin-generated NPE message/order differences in internal paths. These are additive interop differences, not migration blockers.

Paper real-server acceptance remains a separate gate; this slice is tactical runtime/cache orchestration and does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
