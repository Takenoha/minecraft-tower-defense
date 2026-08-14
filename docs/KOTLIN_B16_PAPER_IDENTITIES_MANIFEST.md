---
title: "Kotlin B16 Paper identities manifest"
tags: [kotlin, java, migration, paper, identities]
status: active
created: 2026-08-14
---

# Kotlin B16 Paper identities manifest

## Scope

- Base: `feat/kotlin-b15-paper-policies-abo` at `6faabec9584578f3763c1784f7cb6fe59c2a3769`
- Implementation commit: `bf625748079e1cc6cb554615d0f483448fab33ba`
- Migrated boundaries: `CoreItemIdentity`, `RaidSealItemIdentity`, `TowerItemIdentity`, `TowerEntityIdentity`, `ResearchCrystalItemIdentity`, and `ResourceVoucherItemData`
- Java sources removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin `@JvmRecord` sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperIdentitiesKotlinBoundaryAbiTest.java`
- Paper taggers/listeners/managers, GUI wiring, schema, and repository code were not changed.

## Boundary and invariants

- All six Java records retain record component names/order, canonical constructors, accessors, and `java.lang.Record` generation.
- Compatibility constructors remain for unbound cores, default-priority tower items, and unsplit research crystals.
- Identity validation preserves null checks, positive stage/level/quantity guards, research-crystal segment bounds, and mutually exclusive voucher receipt operations.
- Existing Java callers continue to construct and consume the records without adapter changes; custom `isBound`, `hasSegmentIdentity`, and `hasReceipt` methods remain available.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `bf625748079e1cc6cb554615d0f483448fab33ba`:

- `BUILD SUCCESSFUL`
- 85 XML test reports / 307 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- all six Kotlin identity classes present; Java duplicate classes absent
- packaged JAR SHA-256: `07499FD4D86D951A66960952924C39B4E68CDB04B670EAC3CB1DF73E18DA160A`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
