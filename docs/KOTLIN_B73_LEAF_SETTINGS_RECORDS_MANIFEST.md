---
title: "Kotlin B73 Leaf Settings Record Migration Manifest"
tags: [kotlin-migration, records, config, settings]
status: active
created: 2026-08-15
---

# Kotlin B73 Leaf Settings Record Migration Manifest

## Scope

- Base: `09b09268d1fd87beeae366d88664fc5beb2ade00` (B72 final)
- Implementation: `15b8e943966ce1b44759ffa8fdf072e547320743`
- Migrated records: `EnemySettings` and `RewardSettings`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/ConfigLeafRecordsKotlinBoundaryAbiTest.java`

`TowerSettings` and `ProtectionSettings` remain Java in this slice. Their canonical constructors
normalize nested collections with exact Java `Map.copyOf`, `Set.copyOf`, and `List.copyOf`
semantics, so they remain explicit compatibility boundaries rather than being approximated in
this leaf-record migration.

## Preserved contract

- Both migrated classes remain public JVM Records with the original component names, order, types,
  public canonical constructors, and source-compatible shortened constructors.
- `EnemySettings` retains all five public default fields and both role-settings compatibility
  constructors.
- `RewardSettings` retains all ten public default fields, both shortened constructors,
  `defaults()`, `teamQueueRetention()`, `researchCrystalQuantity`, `battleFundsFor`, and
  `defenseShardsFor` behavior and exception boundaries.
- Kotlin callers continue to use normal method calls for the non-component `RewardSettings`
  methods; only the migrated record components are Kotlin properties.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 142 XML test suites / 397 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `94DD4C03E7284023AA2C621A01D90E431D658F043482014988127C3BBC1E4325`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin `@JvmRecord` data classes emit Kotlin metadata, `componentN`/`copy` helpers, companion
holders for static factories, final accessors, and a `WhenMappings` helper for the role switch.
`@JvmField` constants retain their public static-final field descriptors and values but do not
carry Java `ConstantValue` attributes. These are additive interop differences; the Java Record
components, constructors, methods, fields, and caller-facing behavior are preserved.
