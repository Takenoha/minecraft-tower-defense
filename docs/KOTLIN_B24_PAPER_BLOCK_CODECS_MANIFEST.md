---
title: "Kotlin B24 Paper block codecs manifest"
tags: [kotlin, java, migration, paper, codec, block, tile]
status: active
created: 2026-08-14
---

# Kotlin B24 Paper block codecs manifest

## Scope

- Base: `feat/kotlin-b23-paper-codecs-settings-abo` at `29bbfed`
- Implementation commit: `9a644f3e9238efae36fbb9daf11509346618e25a`
- Migrated boundaries: `PaperTileNbtCodec` and `PaperBlockStateCodec`
- Java sources for the two migrated codecs removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperBlockCodecsKotlinBoundaryAbiTest.java`
- Block mutation adapter, terrain action, repository ledger, schema, and transaction code were not changed.

## Boundary and invariants

- Tile payload version `v1`, separator/field shape, Base64 URL encoding, persistent-data bytes, inventory bytes, lock, and custom-name fields remain unchanged.
- Invalid binary/version/shape/inventory payloads retain the same exception classes and messages; ordinary blocks still capture as an empty payload.
- The nullable `Lockable.setLock(null)` contract is preserved through the explicit Java API setter; lock/name handling remains fail-closed for incompatible state types.
- BlockData parsing, comparable snapshots, tile payload capture/application, no-physics block updates, existing-tile guard, and durable update rejection remain unchanged.
- Both utilities retain public final classes, private no-argument constructors, and public static Java-facing method descriptors.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `9a644f3e9238efae36fbb9daf11509346618e25a`:

- `BUILD SUCCESSFUL`
- 93 XML test reports / 320 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- Kotlin block codec classes present; Java duplicate classes absent
- packaged JAR SHA-256: `907709C4C269FEA876D63A7D8C72B3B1722E49B94FD22CC3EA198431FBD388C5`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
