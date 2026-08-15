---
title: "Kotlin B72 Domain Progress Record Migration Manifest"
tags: [kotlin-migration, records, domain, progression]
status: active
created: 2026-08-15
---

# Kotlin B72 Domain Progress Record Migration Manifest

## Scope

- Base: `33e125bee2522ab77a68c26788844b57d8c483c3` (B71 final)
- Implementation: `4d3dc64c4eced77f02192a11d6a4a01badec7ffd`
- Migrated records: `TeamProgress`, `TowerResearch`, and `EnemyObstacleFacts`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/DomainProgressRecordsKotlinBoundaryAbiTest.java`

## Preserved contract

- All three migrated classes remain public JVM Records with the original component names, order,
  types, and public canonical constructors.
- `TeamProgress.initial(UUID)` and `afterVictory(long)` preserve the progression validation,
  monotonic update, next-stage unlock, and exception-message behavior.
- `TowerResearch.initial(UUID, TowerType, Instant)` preserves the positive research-level and
  non-null identity/timestamp validation.
- `EnemyObstacleFacts.unavailable()`, `permits(EnemyTerrainActionKind)`, and
  `toPathContext(int)` preserve fail-closed classification, terrain-action admission, and path
  context conversion semantics.
- Kotlin callers use property access for the migrated record components; Java records and the
  existing persistence/Paper boundaries remain method-based at the JVM boundary.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 141 XML test suites / 395 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `5F2C5AFB94BDA5A61FF9D1F31B6C12740BAA948B8500DB88468715CDBEFE01C6`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin `@JvmRecord` data classes emit Kotlin metadata, `componentN`/`copy` helpers, companion
holders for static factories, final accessors, and a `WhenMappings` helper for the obstacle
classification switch. These are additive interop differences; the Java Record components,
constructors, methods, validations, and caller-facing behavior are preserved.
