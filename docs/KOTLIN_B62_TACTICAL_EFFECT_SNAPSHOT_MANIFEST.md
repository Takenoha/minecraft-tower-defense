---
title: "Kotlin B62 Tactical Effect Snapshot Migration Manifest"
tags: [kotlin-migration, tactical-build, effect-snapshot, abi]
status: active
created: 2026-08-14
---

# Kotlin B62 Tactical Effect Snapshot Migration Manifest

## Scope

- Base: `a27f66c251902819599f7f32e0a0245253fa3072` (B61 final)
- Implementation and code-verification HEAD: `bdb33cc031b7dda513d5f6e2c3cd90466372ffdb`
- Migrated source: `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalEffectSnapshotImpl.java` → `src/main/kotlin/io/github/takenoha/towerdefense/tactical/TacticalEffectSnapshotImpl.kt`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalEffectSnapshotImplKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved contract

- `TacticalEffectSnapshotImpl` remains a public final implementation of `TacticalEffectSnapshot`, with all ten public snapshot methods and their Java descriptors/return types preserved.
- Constructor inputs are copied into independent `EnumMap` structures, including every conditional row, before the snapshot is exposed. Missing rows remain empty and per-tower defaults remain the Java neutral values.
- Conditional damage and attack-interval matching preserves all eight target conditions and multiplies only matching entries. Non-finite, non-positive, and overflowing products normalize to `1.0` through the existing safe-product boundary.
- Range, area-radius, chain-count, slow-strength, burn-duration, support-buff, repair-cost, and tower-damage-taken defaults and null/type guards remain unchanged.
- `TacticalEffectCompiler` continues to use the same safe-product boundary, and cache/compiler/runtime callers were not changed by this slice.

## Verification

The implementation commit was tested at a fixed HEAD. The test invocation was:

```text
.\gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
131 XML test reports
371 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-artifact checks:

- `git diff --check`: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `6FE03F42424CA49F5A6F99E47EF8E6EF010136952C8D17C34A73916633F62C7B`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No `TacticalEffectSnapshotImpl.class` duplicate exists under `build/classes/java/main`.
- Packaged Kotlin output contains the snapshot outer class, `Companion`, and condition mapping helper; all three inspected class files are JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.

## Known additive Kotlin surface

The former package-private constructor is emitted as public by Kotlin's JVM representation of the `internal` constructor, and the former package-private `safeProduct` helper is exposed as a public static method for the existing Kotlin compiler caller. Kotlin also generates a `Companion`, condition mapping helper, final method modifiers, and nullable annotations/metadata. Contract-breaking null inputs can have Kotlin-generated NPE message/order differences. These are additive interop differences; the snapshot/copy/condition semantics and current caller boundary are preserved.

Paper real-server acceptance remains a separate gate; this slice is the compiled tactical effect snapshot and does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
