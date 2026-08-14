---
title: "Kotlin B19 Paper entity taggers manifest"
tags: [kotlin, java, migration, paper, pdc, tagger]
status: active
created: 2026-08-14
---

# Kotlin B19 Paper entity taggers manifest

## Scope

- Base: `feat/kotlin-b18-paper-tagger-core-abo` at `03b6d67`
- Implementation commit: `9ec591c976b0387beea6e23508322a7da7e401c9`
- Migrated boundaries: `EventEnemyTagger` and `TowerEntityTagger`
- Java sources removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperEntityTaggersKotlinBoundaryAbiTest.java`
- Paper listeners, managers, GUI wiring, schema, and repository code were not changed.

## Boundary and invariants

- Event enemy tagging retains the `event_id`, `logical_enemy_id`, and `enemy_role` PDC keys, UUID parsing, `EnemyRole.NORMAL` fallback for missing role, and unknown-role rejection.
- Tower entity tagging retains marker/version/tower/team/type/level PDC keys, `ENTITY_VERSION`, positive-level validation, `TowerType` parsing, and malformed-identity rejection.
- Both public constructors, `tag` methods, and Optional-returning `read` methods retain Java descriptors and existing caller shape.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `9ec591c976b0387beea6e23508322a7da7e401c9`:

- `BUILD SUCCESSFUL`
- 88 XML test reports / 313 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- two Kotlin tagger classes present; Java duplicate classes absent
- packaged JAR SHA-256: `29EB25793CF255F3C0BC36EB915974666E30B01FA554034EBC71A05BFF9C3FA8`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
