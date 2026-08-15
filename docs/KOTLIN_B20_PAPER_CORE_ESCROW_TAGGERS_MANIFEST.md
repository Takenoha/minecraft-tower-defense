---
title: "Kotlin B20 Paper core and escrow taggers manifest"
tags: [kotlin, java, migration, paper, pdc, tagger, escrow]
status: active
created: 2026-08-14
---

# Kotlin B20 Paper core and escrow taggers manifest

## Scope

- Base: `feat/kotlin-b19-paper-entity-taggers-abo` at `a5cc092`
- Implementation commit: `bea29c907bb43fc5705cab9fb3bcfb0f2fbb70af`
- Compatibility fix commit: `d57d35589d8781940a62a1e9bbce7978c949e79e`
- Migrated boundaries: `CoreItemTagger` and `EscrowDropTagger`
- Java sources for the two migrated taggers removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperCoreEscrowTaggersKotlinBoundaryAbiTest.java`
- Paper listeners, managers, GUI wiring, schema, and repository code were not changed.

## Boundary and invariants

- Core item recipe templates, unbound/bound identity creation, `ITEM_VERSION`, material checks, amount-one reads, Optional identity parsing, and item-id matching retain their Java-facing behavior. `hasItemId` accepts a nullable UUID and returns false for a missing/mismatched identity, matching the Java null-input contract.
- Escrow tagging retains both entity and ItemStack overloads, clone-before-mutate stack behavior, escrow event/drop PDC keys, partial/malformed UUID rejection, and the entity-first read fallback for dropped items.
- Existing Java callers continue to use the overloads and constructors without adapter changes.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `d57d35589d8781940a62a1e9bbce7978c949e79e`:

- `BUILD SUCCESSFUL`
- 89 XML test reports / 315 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- two Kotlin tagger classes present; Java duplicate classes absent
- packaged JAR SHA-256: `183F3A58123E04C91EA2E964B4F4A1F73572ED0B67C2D53EDD34809E6EFC961C`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
