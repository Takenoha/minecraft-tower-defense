---
title: "Kotlin B18 Paper core tagger manifest"
tags: [kotlin, java, migration, paper, pdc, tagger]
status: active
created: 2026-08-14
---

# Kotlin B18 Paper core tagger manifest

## Scope

- Base: `feat/kotlin-b17-paper-policy-remainder-abo` at `290a77c`
- Implementation commit: `27d601cf30c8c139c119d26640b12ae1b9e62d73`
- Migrated boundaries: `CoreBlockTagger`, `DefenseShardTagger`, `EnhancementCoreTagger`, `CoreRepairReceiptTagger`, and `RewardQueueReceiptTagger`
- Java sources removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperTaggerCoreKotlinBoundaryAbiTest.java`
- Paper listeners, managers, GUI wiring, schema, and repository code were not changed.

## Boundary and invariants

- Core block checks retain the current/legacy material policy and constructor/method descriptors.
- Defense-shard and enhancement-core taggers retain `ITEM_VERSION`, PDC key names, materials, amount bounds, display/lore, UUID validation, and invalid-item rejection.
- Repair and reward receipt taggers retain clone-before-mutate behavior, metadata-holder guards, PDC key removal, Optional reads, malformed UUID rejection, and receipt matching semantics.
- Existing Java callers continue to construct and consume the taggers without adapter changes.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `27d601cf30c8c139c119d26640b12ae1b9e62d73`:

- `BUILD SUCCESSFUL`
- 87 XML test reports / 311 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- five Kotlin tagger classes present; Java duplicate classes absent
- packaged JAR SHA-256: `AA48BDF64A69E1F2C2996B7DEE6348A51C962BE8764824761DFE64057AEEED27`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
