---
title: "Kotlin B68 Simple Record Migration Manifest"
tags: [kotlin-migration, records, persistence, runtime]
status: active
created: 2026-08-14
---

# Kotlin B68 Simple Record Migration Manifest

## Scope

- Base: `bf982634762a7baae5560adc7a3ff68b53bc8ae2` (B67 final)
- Implementation: `af2b432` (simple records plus migrated Kotlin caller property access)
- Migrated records: 16 pure key, identity, durability, settlement, and mutation-result records
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/SimpleRecordsKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after code verification.

## Preserved contract

- All migrated classes remain public JVM Records with the original component names, order, types, and public canonical constructors.
- Existing validation and exception messages for the migrated records remain in place.
- Compatibility overloads remain for `TaggedEnemy(eventId, logicalEnemyId)` and `EscrowClaimResult(outcome, claimedQuantity)`.
- `CoreBlockKey.from(Block)` remains a public static factory with the same return type and descriptor.
- `VoucherDeliveryResult.operation` remains nullable as in the Java record, while the other required components retain their null guards.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 137 XML test suites / 387 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `BF338ED1E446A6FA76B5D3D54658AF47FD018423CDF67F0E741E8136AB38C731`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.

- `git diff --check`: clean.
- No Java duplicate remains for any migrated record.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

Kotlin `@JvmRecord` data classes emit Kotlin metadata, `componentN`/`copy` helpers, companion holders for static factories, and final accessors. These are additive interop differences; the Java Record components and caller-facing constructors/accessors are preserved.
