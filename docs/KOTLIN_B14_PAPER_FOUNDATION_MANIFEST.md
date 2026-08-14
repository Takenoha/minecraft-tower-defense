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

The clean verification build completed successfully after the ABI test was added. Final test and artifact counts will be recorded from the final fixed HEAD before the PR is opened.

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
