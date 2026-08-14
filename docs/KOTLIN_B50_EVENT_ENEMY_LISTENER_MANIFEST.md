---
title: "Kotlin B50 Event Enemy Listener Migration Manifest"
tags: [kotlin-migration, paper-listener, abi]
status: active
created: 2026-08-14
---

# Kotlin B50 Event Enemy Listener Migration Manifest

## Scope

- Base: `ca29636eab6f74b9cc19fc62207ab19356cc98a8`
- Event listener implementation commit: `5cd1fb5`
- Final HEAD: `d51b232177719d334330574f2cb8c9de8fd8314e`
- Target: `EventEnemyListener.java` → `EventEnemyListener.kt`
- Boundary test: `EventEnemyListenerKotlinBoundaryAbiTest.java`
- B49 corrected Candidate record bridge is included unchanged; no other caller, tagger, persistence, schema, or plugin wiring changes.

## Preserved contract

- `EventEnemyListener` remains public final and implements Bukkit `Listener`; the five-argument constructor retains its Java descriptor and null guards.
- All 18 public event handlers retain their names, parameter descriptors, `void` returns, `HIGHEST` priority, and `ignoreCancelled` values, including the default-false settings for entities-load and death.
- Enemy damage/target authorization, entity-load removal, combat-area block/bucket/ignite/piston protection, death cleanup and lifecycle callback, no-drop/no-portal/no-transform boundaries, terrain delegation, and explosion block filtering preserve the Java decision order.
- Private `responsiblePlayer` remains static; piston source/destination checks and existing `EventEnemyTagger`, `EnemyAccessPolicy`, `EnemyLifecycleSink`, `PaperEnemyTerrainAction`, and `TowerEntityTagger` boundaries are unchanged.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 119 XML files; 352 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `3BCC7680C89AE2A53CF114B0366EF08384C935E6D6EB3B6ED1EB2E6DC5B13582`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated listener/Candidate JVM major: 69
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- Java listener duplicate source/class: absent
- `git diff --check`: clean; worktree clean
- Paper real-server enemy combat, terrain, loot, portal, GUI/start/restart/win-loss/abort/technical-recovery acceptance remains a separate final gate.
