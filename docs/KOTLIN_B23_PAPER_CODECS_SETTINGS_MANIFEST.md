---
title: "Kotlin B23 Paper codecs and settings manifest"
tags: [kotlin, java, migration, paper, codec, settings]
status: active
created: 2026-08-14
---

# Kotlin B23 Paper codecs and settings manifest

## Scope

- Base: `feat/kotlin-b22-paper-tower-item-tagger-abo` at `bb260c5`
- Implementation commit: `262cc55566646456e8f18eff91fa3fd31a7bdcb1`
- Migrated boundaries: `PaperItemStackCodec` and `PaperSettingsLoader`
- Java sources for the two migrated utilities removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperCodecsSettingsKotlinBoundaryAbiTest.java`
- Escrow managers, plugin lifecycle, schema, repository code, and config validation were not changed.

## Boundary and invariants

- `PaperItemStackCodec.encode(ItemStack)` keeps the clone-before-serialize behavior and YAML payload format.
- `PaperItemStackCodec.decode(String)` keeps invalid YAML wrapping, missing/air/zero-quantity rejection, and the `ItemStack` return descriptor.
- `PaperSettingsLoader.load(FileConfiguration)` keeps the six config section names, insertion order, nested map conversion, and delegation to `PluginSettings.from`.
- Both utilities retain public final classes, private no-argument constructors, and public static Java-facing methods.
- Existing Java callers continue to use the static boundaries without adapters.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `262cc55566646456e8f18eff91fa3fd31a7bdcb1`:

- `BUILD SUCCESSFUL`
- 92 XML test reports / 319 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- Kotlin codec/settings classes present; Java duplicate classes absent
- packaged JAR SHA-256: `1DA5C4BED8990A52D6AB7A5ED6AC6D65E867A950E92612D45B0361F6497E6004`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
