---
title: "Kotlin B58 Tactical Build Catalog Migration Manifest"
tags: [kotlin-migration, tactical-build, catalog, abi]
status: active
created: 2026-08-14
---

# Kotlin B58 Tactical Build Catalog Migration Manifest

## Scope

- Base: `98327b0068d46af41680c4277ac3e7ad6174a28e` (B57 final)
- Implementation and code-verification HEAD: `5768b2aaf6a9818c217ffcc960e230b8c7764848`
- Migrated source: `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalBuildCatalog.java` → `src/main/kotlin/io/github/takenoha/towerdefense/tactical/TacticalBuildCatalog.kt`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalBuildCatalogKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved contract

- `TacticalBuildCatalog` remains a public final class with the public `List<TacticalBuildDefinition>` constructor.
- `DEFINITION_VERSION` and `GENERATOR_VERSION` remain public static final `int` fields with value `1`.
- The public static `defaults()` factory, `definitions()`, `enabledDefinitions()`, and `require(String)` descriptors and return types remain unchanged for Java callers.
- The default catalog retains the exact seven-definition order: `rapid-fire`, `long-range`, `heavy-fortress`, `flame-suppression`, `ice-lightning`, `final-defense-line`, and `arrow-specialization`.
- All definition categories, rarities, icon materials, tower target sets, six linear tiers, branched node IDs, branch prerequisites, effects, values, and target conditions are unchanged.
- Constructor validation still runs `TacticalBuildDefinitionValidator.validateAll`, copies the definitions and ID map, and rejects null/unknown lookup values with the existing failure boundary. Returned definition lists remain unmodifiable.
- Existing callers (`TowerDefensePlugin`, `TacticalBuildSelectionListener`, `TacticalDefinitionCodec`, repository/runtime code, and tactical tests) were not changed.
- The tactical definition/node/effect records and their wire-format/codec boundaries remain Java-side and unchanged in this slice.

## Verification

The committed implementation HEAD was checked with an explicit Git safe-directory override; the same HEAD was observed before and after the build:

```text
HEAD_BEFORE=5768b2aaf6a9818c217ffcc960e230b8c7764848
HEAD_AFTER=5768b2aaf6a9818c217ffcc960e230b8c7764848
```

Command:

```text
gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
127 XML test reports
362 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-HEAD checks:

- `git diff --check` for the implementation worktree: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `932E40A48C5BAB6F24C4A1175DF4D0F5B7F6B555C30685D3081DAF7D9F7FBD71`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No `TacticalBuildCatalog.class` duplicate exists under `build/classes/java/main`.
- Packaged Kotlin output contains only `TacticalBuildCatalog` and its `Companion` for this migrated class; both inspected class files are JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.
- The implementation worktree was clean before this documentation-only final commit.

## Known additive Kotlin surface

Kotlin generates a `Companion`, final method modifiers, nullable annotations/metadata, and private helper methods on the companion/outer class. The Kotlin nullable constructor and `require` parameter preserve the former explicit Java null guards; other Java calls with contract-breaking nulls can still have Kotlin-generated NPE message/order differences. These are additive interop differences, not migration blockers.

Paper real-server acceptance remains a separate gate; this slice is a pure tactical catalog boundary and does not claim GUI, selection, restart, win/loss, abort, or technical-recovery acceptance.
