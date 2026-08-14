---
title: "Kotlin B14 Paper foundation manifest"
tags: [kotlin, java, migration, paper, foundation]
status: active
created: 2026-08-14
---

# Kotlin B14 Paper foundation manifest

## Scope

- Base: `feat/kotlin-b13-defense-repository-abo` at `f23487e3e4485f736969fd80ba501d565324e91e`
- Implementation commit: `7d5dd6a65c6809f7eaf0c06dd19f3092ad01401a`
- B13 whitespace and manifest follow-up commits were propagated before verification; they do not alter the Paper implementation.
- Verified code HEAD: `d64a1862c581a3f3afee8f9a2ccc9d92244738a4`
- Migrated boundaries: `TaggedEscrowDrop`, `RewardQueueReceipt`, `RaidSealRecipeDefinition`, and `CoreRecipeDefinition`
- Java sources removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperFoundationKotlinBoundaryAbiTest.java`
- Paper listeners, managers, GUI wiring, schema, and repository code were not changed.

## Boundary and invariants

- The two record-like identities retain Java record components, canonical constructors, UUID accessors, null validation, and record descriptors.
- Recipe utility classes retain private constructors, public static material constants, public static `shape()` methods, and exact recipe rows.
- Java callers continue to construct and consume these classes without adapter or descriptor changes.

## Build and artifact verification

The final clean verification build completed successfully:

- `BUILD SUCCESSFUL`
- 83 XML test reports / 303 tests
- failures 0 / errors 0 / skipped 0
- Kotlin Paper classes present; Java duplicate recipe/identity classes absent
- packaged JAR SHA-256: `9FE943F001682316CBFDA05C23EDD5E072A4CC2707252319698544811D21F27E`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
