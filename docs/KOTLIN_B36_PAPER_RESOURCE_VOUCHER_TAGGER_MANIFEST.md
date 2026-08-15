---
title: "Kotlin B36 Paper resource voucher tagger manifest"
tags: [kotlin, java, migration, paper, resource-voucher, pdc]
status: active
created: 2026-08-14
---

# Kotlin B36 Paper resource voucher tagger

- Base: `032bae4e2dd26028e4403ae320f043c59af64ced` (B35 final).
- Verified code HEAD: `86c3b934496bc01e518694d9060dd856e58f6a59`.
- Scope: migrate `ResourceVoucherTagger` from Java to Kotlin and add `ResourceVoucherTaggerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/ResourceVoucherTagger.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/ResourceVoucherTagger.kt`.

## Preserved boundaries

- Public final class, `Plugin` constructor, create/tag/strip/read/predicate/matching descriptors, `ResourceVoucher` parameter shape, and `ResourceVoucherItemData` Optional return remain Java-compatible.
- Canonical voucher PDC keys, version `1`, PRISMARINE_CRYSTALS material, max stack size `1`, display/lore, UUID/resource/quantity encoding, and receipt key ownership remain unchanged.
- Delivery/redeem tagging remains clone-before-mutate, amount-one, and mutually exclusive; strip operations preserve unrelated receipts and malformed/missing metadata behavior.
- Read remains fail-closed for null/wrong material/missing metadata/PDC, invalid UUID/resource/quantity payloads, and preserves delivery/redeem Optional identity parsing. `TowerDefensePlugin` and `ResourceVoucherListener` callers were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 105 XML test suites, 338 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `7C3E32389A1CCE90F04D9E7A533A06CDFD1F0D1DFCF9705FA442299F9F9DBB0C`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin tagger class and Companion present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and receipt/recipe acceptance remain a separate real-server gate.
