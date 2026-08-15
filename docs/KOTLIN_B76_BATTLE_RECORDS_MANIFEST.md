---
title: "Kotlin B76 Battle Records Migration Manifest"
tags: [kotlin-migration, records, persistence, battle]
status: active
created: 2026-08-15
---

# Kotlin B76 Battle Records Migration Manifest

## Scope

- Base: `f329116ea047f3a4471726941c6c55218509c7c3` (B75 final)
- Implementation: `925d708f3f631115823ad20905c414a0da9d2485`
- Migrated records: `BattleBoost`, `BattleFunds`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/BattleRecordsKotlinBoundaryAbiTest.java`

## Preserved contract

- `BattleBoost` and `BattleFunds` remain public JVM Records with the original
  component names, order, types, public canonical constructors, accessors, and
  validation messages.
- Battle boost validation retains the non-null identity/timestamp checks and the
  positive finite level/multiplier boundary.
- Battle funds validation retains non-negative totals, the
  `totalSpent <= totalEarned` invariant, and the settled-zero-balance rule.
- `DefenseRepository` and `TowerManagementGui` use the Kotlin record properties
  while preserving the existing persistence mapping, mutation ordering, and
  Java-facing record descriptors.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 145 XML test suites / 402 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `30E715E6F91140096AB882DDD67418F19D71D111767127AE867F15F92AF0AFD4`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

The Kotlin `@JvmRecord` data classes emit metadata, `componentN`/`copy` helpers,
final accessors, and Kotlin-formatted `toString` behavior. These are additive
interop differences; the Java Record components, constructors, accessors,
validation, persistence mapping, and caller-facing behavior are preserved.
