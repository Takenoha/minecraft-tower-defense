---
title: "Kotlin B66 Domain and Persistence State Enum Migration Manifest"
tags: [kotlin-migration, enums, persistence, domain]
status: active
created: 2026-08-14
---

# Kotlin B66 Domain and Persistence State Enum Migration Manifest

## Scope

- Base: `5e21a3c058fc5dc53ff325aba55c23d7eff2cd0e` (B65 final)
- Implementation and code-verification HEAD: `8fa935ad25b0c3ddc861cab0845ce48880dae9a3`
- Migrated pure enums: 25 domain, persistence, and runtime state/result types
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/StateEnumsKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after code verification.

## Preserved contract

- Every migrated type remains a public Java enum with the original constant names and exact declaration order.
- Java `values()`, `valueOf(String)`, enum identity, switch compatibility, and existing persistence/Paper caller boundaries remain unchanged.
- Method-bearing enums (`DefensePhase`, `EnemyRole`, `TowerTargetPriority`, `TowerType`, `BattleBoostKind`, and `ResourceType`) are intentionally outside this slice; their behavior is not altered here.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`, 135 XML reports, 381 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: clean for base-to-final.
- Fat JAR SHA-256: `E6C474AB945282B1F9A26F247BD8C7DC4669F0860EB84A9556A753AC8B28F06A`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- All 25 migrated enums are JVM major 69.
- Packaged Kotlin runtime contains 1045 entries; `kotlin/reflect/jvm/internal/*` contains 0 entries.
- No Java duplicate remains for any migrated enum under `build/classes/java/main`.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin emits metadata and the normal enum `getEntries()` surface in addition to Java's enum methods. These are additive interop differences; the public enum identity, names, order, and caller-visible behavior are preserved.
