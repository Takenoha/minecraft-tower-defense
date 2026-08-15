---
title: "Kotlin B71 Config Record Migration Manifest"
tags: [kotlin-migration, records, config, interop]
status: active
created: 2026-08-15
---

# Kotlin B71 Config Record Migration Manifest

## Scope

- Base: `c1a4bed4c6be4bd9f3dced7841f1127f1a5d2801` (B70 final)
- Implementation: `d54c8650f32c59db853cdb0b7107a81ce08182f1`
- Migrated records: `CombatSettings`, `ForbiddenRegion`, and `TerrainMutationSettings`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/ConfigRecordsKotlinBoundaryAbiTest.java`

`ProtectionSettings` remains Java in this slice because its compact constructor normalizes nullable
collections with `Set.copyOf` and `List.copyOf`; preserving that exact canonical-constructor
mutation is a deliberate compatibility boundary rather than a Kotlin approximation.

## Preserved contract

- All three migrated classes remain public JVM Records with the original component names, order,
  types, and public canonical constructors.
- `ForbiddenRegion` retains nullable `worldName` behavior, inclusive point containment, case-
  insensitive world matching, and circle/rectangle intersection semantics.
- `TerrainMutationSettings.disabled()` remains a public static factory returning the all-false,
  fail-closed configuration.
- Kotlin callers use property access only for the migrated `CombatSettings` components; Java
  records and the retained Java `ProtectionSettings` remain method-based.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 140 XML test suites / 393 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `877A87D65A08B4958AE4B84CCBA401063DE61B7B3F39D5B1B8E7CCEA02C0BFAE`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin `@JvmRecord` data classes emit Kotlin metadata, `componentN`/`copy` helpers, companion
holders for static factories, and final accessors. These are additive interop differences; the
Java Record components, constructors, methods, and caller-facing behavior are preserved.
