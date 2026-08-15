---
title: "Kotlin B74 Runtime Status Record Migration Manifest"
tags: [kotlin-migration, records, runtime, status]
status: active
created: 2026-08-15
---

# Kotlin B74 Runtime Status Record Migration Manifest

## Scope

- Base: `35ec532395f5535fd98feb56c44a482fce7e2785` (B73 final)
- Implementation: `913a0201405f4eff3d720b8e9926174d1390bc33`
- Migrated record: `DefenseRuntimeStatus`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/RuntimeStatusKotlinBoundaryAbiTest.java`

`EnemyPathMetrics.Snapshot` remains part of the Java `EnemyPathMetrics` outer-class boundary in
this slice; the nested binary name and its existing counter validation are unchanged.

## Preserved contract

- `DefenseRuntimeStatus` remains a public JVM Record with the original 15 component names, order,
  types, public canonical constructor, and Java-facing accessors.
- The 13-argument compatibility constructor continues to initialize `coreAttackers` and
  `coreAttackCount` to zero.
- Non-null identity/phase/path-metrics guards and non-negative core attack validation retain their
  original exception messages and behavior.
- Nullable `persistenceFailure` and the existing `EnemyPathMetrics.Snapshot` type remain at the
  same JVM descriptors.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 143 XML test suites / 398 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `2975CDBE05832D289B31D18594865C0A88D1A5B509684402274F0096F5449723`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated class uses JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

The Kotlin `@JvmRecord` data class emits metadata, `componentN`/`copy` helpers, and final
accessors. These are additive interop differences; the Java Record components, constructors,
validation, and caller-facing behavior are preserved.
