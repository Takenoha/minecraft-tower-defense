---
title: "Kotlin B21 Paper raid seal tagger manifest"
tags: [kotlin, java, migration, paper, pdc, tagger, raid-seal]
status: active
created: 2026-08-14
---

# Kotlin B21 Paper raid seal tagger manifest

## Scope

- Base: `feat/kotlin-b20-paper-core-escrow-taggers-abo` at `59a95e9`
- Implementation commit: `f3242c53a9028a9c489c8c11ae824b9046a8d3a2`
- Migrated boundary: `RaidSealTagger`
- Java source for `RaidSealTagger` removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin source added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `RaidSealTaggerKotlinBoundaryAbiTest.java`
- Raid-seal listeners, recipe registration, schema, and repository code were not changed.

## Boundary and invariants

- Public constants retain `ITEM_VERSION`, `FOUNDATION_STAGE`, current/legacy materials, values, and static-field descriptors.
- Recipe template overloads, stage-specific creation, stage validation, supported-material checks, legacy-material detection, OptionalLong template-stage parsing, and seal-id matching retain their Java-facing behavior.
- PDC marker/version/seal/stage keys, display/lore content, amount-one enforcement, malformed UUID rejection, and invalid-stage rejection remain unchanged.
- The inherited B20 `CoreItemTagger.hasItemId` nullable UUID compatibility fix is included in the base tree.

## Build and artifact verification

The clean verification build completed successfully at verified tree HEAD `bb08c6d398589601f3023970b3c87d3916bb8f7e` (the subsequent commit is manifest-only):

- `BUILD SUCCESSFUL`
- 90 XML test reports / 317 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- Kotlin RaidSealTagger class present; Java duplicate absent
- packaged JAR SHA-256: `6F502058A3B084DB6C9F469423A17CDC910A8DC8D41DE9DF0B4FFF9CD6C1C83E`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
