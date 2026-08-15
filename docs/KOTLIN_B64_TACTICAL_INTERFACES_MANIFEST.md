---
title: "Kotlin B64 Tactical Interface and Terminal Enum Migration Manifest"
tags: [kotlin-migration, tactical-build, interfaces, abi]
status: active
created: 2026-08-14
---

# Kotlin B64 Tactical Interface and Terminal Enum Migration Manifest

## Scope

- Base: `c97ae16f80f40ffabf4c6b420f0f8a5c447d5fb8` (B63 final)
- Implementation and code-verification HEAD: `7d4258129690bd9f42cbb00bd0e84fcd0a9616f2`
- Migrated interfaces: `TacticalBuildLifecycle`, `TacticalBuildStateProvider`, `TacticalEffectSnapshot`, and `TacticalEffectSnapshotProvider`
- Migrated enum: `TacticalTerminalResult`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalInterfacesKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved contract

- All four interfaces remain Java interfaces with the original public abstract method names, parameter descriptors, return types, and method count.
- `TacticalBuildLifecycle` retains preparation, wave advancement, final-tier, and terminal methods; `TacticalBuildStateProvider` retains its `Optional` lookup; and the snapshot/provider interfaces retain the compiled-effect hot-path boundary.
- `TacticalTerminalResult` retains the exact enum names and order: `VICTORY`, `DEFEAT`, `ABORTED`, `RECOVERY`.
- Existing implementors and callers (`TacticalBuildRepository`, `TacticalBuildRuntime`, `TacticalEffectCache`, compiler/cache/runtime tests, and Paper wiring) were not semantically changed by this slice.

## Verification

The implementation commit was tested at a fixed HEAD. The test invocation was:

```text
.\gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
133 XML test reports
377 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-artifact checks:

- `git diff --check`: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `2F12A693205EC46746BF30E03E9EDD41ED89DA1D5C5DAD8A3504221671FD819D`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No Java duplicate exists for any of the five migrated tactical types under `build/classes/java/main`.
- The four interface classes and terminal enum are JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.

## Known additive Kotlin surface

Kotlin adds nullable annotations/metadata and Kotlin metadata to the interface/enum classes. Interface implementations remain public abstract JVM methods; the enum receives the normal Kotlin metadata surface. Contract-breaking Java null inputs can have Kotlin-generated NPE message/order differences where non-null Kotlin parameters are used. These are additive interop differences, not descriptor or behavior blockers.

Paper real-server acceptance remains a separate gate; this slice is tactical lifecycle/effect interface wiring and does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
