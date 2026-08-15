---
title: "Kotlin B69 Domain Record Migration Manifest"
tags: [kotlin-migration, records, domain, config]
status: active
created: 2026-08-14
---

# Kotlin B69 Domain Record Migration Manifest

## Scope

- Base: `b6f4882c6f0ae218a38bf87b14c23ecfdfbd8a18` (B68 final)
- Implementation: `48759b9d69bd3765b1d0fd28d2cf79ec409b9828`
- Migrated records: `CombatArea`, `CoreState`, `WorldBorderSnapshot`, `EnemyBridgePlan`,
  `TerrainMutationInput`, and `TowerProfile`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/DomainRecordsKotlinBoundaryAbiTest.java`

## Preserved contract

- All six migrated classes remain public JVM Records with the original component names, order,
  types, and public canonical constructors.
- Existing validation, exception messages, pure calculation methods, and static factories remain
  available at the Java boundary.
- `TerrainMutationInput` retains its four-fact compatibility constructor.
- Kotlin callers use property access only where the migrated record component is now a Kotlin
  property; Java records such as `CoreRecord` remain method-based.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 138 XML test suites / 389 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `0AC5A406489B1D66DD9A3C046DA6A72F3C8E8BDA37CA6239C22C93EC9E361125`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin `@JvmRecord` data classes emit Kotlin metadata, `componentN`/`copy` helpers, companion
holders for static factories, and final accessors. These are additive interop differences; the
Java Record components, constructors, methods, and caller-facing validation are preserved.
