---
title: "Kotlin B48 Tower Management GUI Migration Manifest"
tags: [kotlin-migration, paper-gui, abi]
status: active
created: 2026-08-14
---

# Kotlin B48 Tower Management GUI Migration Manifest

## Scope

- Base: `a75f5967b52e623c2215673f99a20ffe54946e5f`
- Implementation verification commit: `d1e52a9`
- Target: `TowerManagementGui.java` → `TowerManagementGui.kt`
- Boundary test: `TowerManagementGuiKotlinBoundaryAbiTest.java`
- No caller, holder, persistence, schema, or Paper wiring changes.

## Preserved contract

- `TowerManagementGui` remains a public final utility class with a private no-arg constructor.
- The 11 public slot constants, five `create` overloads, and `priorityAt(int): Optional<TowerTargetPriority>` retain their Java-facing names, descriptors, visibility, and return types.
- The tower item, boost, repair, target-priority, upgrade, legacy-payment, removal, help, and close rendering paths preserve the original materials, colors, titles, lore, slot placement, guards, wallet checks, and delegation boundaries.
- `TowerManagementInventoryHolder` remains the inventory holder boundary; lore is supplied as an unmodifiable list as in the Java implementation.
- The Java source is removed and no Java duplicate class is emitted.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 117 XML files; 350 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `CF869284519FC8501CB761A6DDD6E88B4F619169CC49D4C3BB19DC56AABC2F30`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated class JVM major: 69
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean
- Paper real-server GUI/start/restart/win-loss/abort/technical-recovery acceptance remains a separate final gate.
