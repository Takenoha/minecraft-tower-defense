---
title: "Kotlin B22 Paper tower item tagger manifest"
tags: [kotlin, java, migration, paper, pdc, tagger, tower]
status: active
created: 2026-08-14
---

# Kotlin B22 Paper tower item tagger manifest

## Scope

- Base: `feat/kotlin-b21-paper-raid-seal-tagger-abo` at `d073f7f`
- Implementation commit: `f10316c`
- Migrated boundary: `TowerItemTagger`
- Java source for `TowerItemTagger` removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin source added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `TowerItemTaggerKotlinBoundaryAbiTest.java`
- Tower-item listeners, managers, GUI/command wiring, schema, and repository code were not changed.
- The inherited B20 nullable `CoreItemTagger.hasItemId` compatibility fix and B21 verification alignment are included in the base tree.

## Boundary and invariants

- The public constructor, `ITEM_VERSION`, recipe-template overloads, create overloads, read/template predicates, recipe type lookup, tower-id matching, and static material mapping retain their Java-facing descriptors.
- Tower-item PDC marker/version/id/type/level/target-priority keys, material mapping, amount-one rules, display/lore text, UUID/type/priority parsing, and invalid-input rejection retain the Java implementation's behavior.
- Missing target priority continues to default to `CORE_NEAREST`; malformed identity data returns an empty `Optional`.
- Existing Java callers continue to use the constructor, overloads, and `Optional<TowerItemIdentity>` boundary without adapter changes.

## Build and artifact verification

The clean verification build completed successfully at verified tree HEAD `0cd76cba1892537eff364f3564d8e412561a777b` (the subsequent commit is manifest-only):

- `BUILD SUCCESSFUL`
- 91 XML test reports / 318 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- Kotlin `TowerItemTagger.class` present; Java duplicate absent
- packaged JAR SHA-256: `44AEED8BA507F9C2426A36725744780F1580F591EB9ED2A4F1EF617E81B260CA`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
