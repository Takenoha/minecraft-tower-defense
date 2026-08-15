---
title: "Kotlin B65 Empty Tactical Effect Snapshot Migration Manifest"
tags: [kotlin-migration, tactical-build, effect-snapshot, abi]
status: active
created: 2026-08-14
---

# Kotlin B65 Empty Tactical Effect Snapshot Migration Manifest

## Scope

- Base: `77e77a2e6defb02f01b4e22a1c8c71df3edaa231` (B64 final)
- Implementation and code-verification HEAD: `64335ea0c5ccb155f2b6e1acbf433e2063fc3b4c`
- Migrated class: `EmptyTacticalEffectSnapshot`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/EmptyTacticalEffectSnapshotKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after code verification.

## Preserved contract

- The class remains a public final `TacticalEffectSnapshot` implementation with a private no-argument constructor.
- The public static final `INSTANCE` singleton field remains available with the same field type and identity semantics.
- All ten public snapshot methods retain their Java names, descriptors, visibility, and neutral values: multipliers return `1.0`, `rangeAdd` returns `0.0`, and `chainCountAdd` returns `0`.
- Existing Java callers (`TacticalBuildRuntime`, `TacticalEffectCache`, `TowerManager`, and tactical tests) continue using the same singleton field and method boundary.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`, 134 XML reports, 380 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: clean for base-to-final.
- Fat JAR SHA-256: `93C2AA85C6AA1EE95132D84CC1FFC71D879D36B0F24CCEA2F106639D212D25CA`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- The migrated outer class and its Kotlin companion are JVM major 69.
- Packaged Kotlin runtime contains 1045 entries; `kotlin/reflect/jvm/internal/*` contains 0 entries.
- No duplicate Java class remains for `EmptyTacticalEffectSnapshot` under `build/classes/java/main`.

## Known additive Kotlin surface

Kotlin emits its normal metadata, nullable annotations, companion holder, and final method modifiers. These are additive interop differences; the public Java field, constructor visibility, method descriptors, neutral behavior, and singleton identity are preserved.

Paper real-server acceptance remains a separate gate; this slice does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
