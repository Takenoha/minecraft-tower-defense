---
title: "Kotlin B4 runtime compiler manifest"
tags: [kotlin, java, migration, tactical, runtime, compiler]
status: active
created: 2026-08-14
---

# Kotlin B4 runtime compiler

This slice moves the pure tactical effect compiler, in-memory effect cache, and automatic tier
progress policy to production Kotlin after the B3 codec boundary migration. The tactical records,
`tdb1`/`tdb2` codec, repository, schema, Paper integration, and plugin entry point remain Java or
unchanged boundaries.

## Baseline

- Baseline: B3 merge commit `0df9a72a31ca0fb70b0eda880f697886fb7da7f0`
- Branch: `feat/kotlin-b4-runtime-compiler-abo`
- Kotlin/JVM plugin: `2.4.10`; Gradle wrapper: `9.6.0`; Java/JVM target: `25`
- Baseline build before B4 changes: `clean test build`, successful on the B3 merge tree

## Production changes

- Replace `TacticalEffectCompiler.java` with `TacticalEffectCompiler.kt`.
- Replace `TacticalEffectCache.java` with `TacticalEffectCache.kt`, preserving both Java
  constructors, synchronized cache methods, neutral fallback, and remove-before-rebuild failure
  behavior.
- Replace `TacticalTierUnlockPolicy.java` with `TacticalTierUnlockPolicy.kt`, using `@JvmStatic`
  to preserve its Java static policy methods and private constructor boundary.
- Add `TacticalRuntimeKotlinBoundaryAbiTest` for constructors, public methods, synchronized cache
  access, static tier methods, and null-Optional fail-closed behavior.

## Compatibility boundaries

- `TacticalEffectCompiler()` and `compile(TacticalBuildSelectionView)` remain public Java-callable
  boundaries.
- `TacticalEffectCache` retains constructors for `(TacticalBuildStateProvider)` and
  `(TacticalBuildStateProvider, TacticalEffectCompiler)`, synchronized methods, and
  `TacticalEffectSnapshotProvider.currentForDefense(UUID)`.
- `TacticalTierUnlockPolicy.highestProgressTier(int, int)` and
  `newlyReachedProgressTiers(int, int, int)` remain public static Java methods.
- Compiler behavior retains tier filtering, explicit unlocked-node selection, bounded values,
  neutral defaults, condition derivation, overflow-safe multiplication, and all-tower policies.
- Cache behavior retains provider-missing neutral fallback, provider/compiler failure invalidation,
  and terminal-facing invalidation semantics.

## Verification

Command:

```text
./gradlew.bat --system-prop kotlin.compiler.execution.strategy=in-process clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1
```

Result at the fixed baseline HEAD plus B4 working tree:

```text
BUILD SUCCESSFUL
73 test XML files, 284 tests, 0 failures, 0 errors, 0 skipped
```

Generated ABI checks:

- Compiler: public no-argument constructor and public `compile` method returning
  `TacticalEffectSnapshot`.
- Cache: both public constructors and synchronized public cache methods.
- Tier policy: private no-argument constructor and public static policy methods.
- Generated classes are JVM major version `69`.

Artifact checks:

- Fat JAR SHA-256: `35824EC570CB658B55D05589BC10E801B74E7855438F642B5576D8B9D2A173A1`.
- Processed `plugin.yml` SHA-256:
  `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Fat JAR contains `1045` Kotlin runtime entries and `0` `kotlin-reflect` entries.
- `git diff --check` is clean.

The B3 codec golden fixtures and existing catalog hashes are unchanged because no codec, model
record, persistence, schema, or Paper source is included in this B4 slice. Paper GUI/start,
restart, outcome, abort, and technical-recovery acceptance remain outside this Kotlin conversion.
