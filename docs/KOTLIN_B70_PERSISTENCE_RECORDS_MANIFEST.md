---
title: "Kotlin B70 Persistence Record Migration Manifest"
tags: [kotlin-migration, records, persistence, interop]
status: active
created: 2026-08-15
---

# Kotlin B70 Persistence Record Migration Manifest

## Scope

- Base: `b26cda396ec9d1cb8954a45fa6608a8abfb5195a` (B69 final)
- Implementation: `42e28f4ef593dffaf4523a05a884aba11f1a7eb5`
- Migrated records: `BlockStateSnapshot`, `TowerDamageMutationResult`, `StartRequest`,
  `StoredBlockChange`, and `RewardQueueEntry`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/PersistenceRecordsKotlinBoundaryAbiTest.java`

## Preserved contract

- All five migrated classes remain public JVM Records with the original component names, order,
  types, and public canonical constructors.
- Existing validation, exception messages, persistence state invariants, and Java-facing record
  accessors remain available at the same descriptors.
- `BlockStateSnapshot` retains its two-argument compatibility constructor; `StartRequest` retains
  its five-argument no-seal constructor; `RewardQueueEntry` retains its twelve-argument
  compatibility constructor.
- Kotlin callers use property access only for the migrated record components; nested Java records
  and unchanged Java model types remain method-based.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 139 XML test suites / 391 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `1AB8129497B4265944556B432C42B3A428A46F07ED0A9F1F3EADB4E1FC0C5A14`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin `@JvmRecord` data classes emit Kotlin metadata, `componentN`/`copy` helpers, companion
holders, and final accessors. These are additive interop differences; the Java Record components,
constructors, compatibility constructors, and caller-facing validation are preserved.
