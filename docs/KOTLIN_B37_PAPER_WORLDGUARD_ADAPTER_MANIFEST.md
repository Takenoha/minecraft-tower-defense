---
title: "Kotlin B37 Paper WorldGuard adapter manifest"
tags: [kotlin, java, migration, paper, worldguard, protection]
status: active
created: 2026-08-14
---

# Kotlin B37 Paper WorldGuard adapter

- Base: `262a905acb27ae7fdaa6fd7efcb1ac1006196641` (B36 final).
- Verified code HEAD: `67221f7097bbe4c2542f02718ab998a6ed5aa569`.
- Scope: migrate `WorldGuardRegionProtectionAdapter` from Java to Kotlin and add `WorldGuardRegionProtectionAdapterKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/WorldGuardRegionProtectionAdapter.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/WorldGuardRegionProtectionAdapter.kt`.

## Preserved boundaries

- Public final adapter class, private `JavaPlugin` constructor, static `discover(JavaPlugin)` return descriptor, and `violations(World,double,double,double): List<String>` descriptor remain Java-compatible.
- WorldGuard remains a soft dependency loaded only through reflection. Missing, disabled, incompatible, or failed reflective integration remains no-op or fail-closed with the existing messages and one-time query failure logging.
- Query geometry validation, world adaptation, region-manager lookup, global-region exclusion, conservative bounding-box/circle intersection, sorted violation messages, and immutable result lists remain unchanged.
- `ThirdPartyRegionProtectionAdapter`, `TowerDefensePlugin`, and existing WorldGuard callers were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 106 XML test suites, 339 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `39D5DC51B0D62762DC206F86E899F050DCFAFFAA7238C988639836A9AA333FD3`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin adapter class and Companion present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and WorldGuard integration acceptance remain a separate real-server gate.
