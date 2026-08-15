---
title: "Kotlin B67 Method-Bearing Enum Migration Manifest"
tags: [kotlin-migration, enums, domain, persistence]
status: active
created: 2026-08-14
---

# Kotlin B67 Method-Bearing Enum Migration Manifest

## Scope

- Base: `3ed9f5254736a9b36da6756a502dbc3eeceb3c02` (B66 final)
- Implementation and code-verification HEAD: `9430456a2e546026f3be9ef346e7090a4677b2ac`
- Migrated enums: `DefensePhase`, `EnemyRole`, `TowerTargetPriority`, `TowerType`, `BattleBoostKind`, and `ResourceType`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/MethodEnumsKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after code verification.

## Preserved contract

- Every migrated type remains a public Java enum with the original constant names and declaration order.
- Instance method names, descriptors, mapped identifiers/display names, phase transitions, role rules, parsing behavior, and `Optional<ResourceType>` boundaries are preserved.
- `EnemyRole.fromId`, `TowerTargetPriority.fromId`, `TowerType.fromId`, `ResourceType.fromItemId`, and `ResourceType.require` remain public static Java methods through `@JvmStatic`.
- Existing Java callers and persistence/Paper boundaries are unchanged.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`, 136 XML reports, 385 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: clean for base-to-final.
- Fat JAR SHA-256: `95E89FF19510BC52962B878F337616D1D7BE65DB14FB1794AEB2E35725AE9527`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- All six migrated enums are JVM major 69.
- Packaged Kotlin runtime contains 1045 entries; `kotlin/reflect/jvm/internal/*` contains 0 entries.
- No Java duplicate remains for any migrated enum under `build/classes/java/main`.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin emits metadata, nullable annotations, companion holders for static factories, and the normal enum `getEntries()` surface. These are additive interop differences; the Java enum/method descriptors and caller-visible behavior are preserved.
