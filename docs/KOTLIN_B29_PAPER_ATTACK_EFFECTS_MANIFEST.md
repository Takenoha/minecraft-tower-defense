---
title: "Kotlin B29 Paper attack effects manifest"
tags: [kotlin, java, migration, paper, particles, tower]
status: active
created: 2026-08-14
---

# Kotlin B29 Paper attack effects

- Base: `3b77be8d8212143b62a66f2037b7b290fe8239c3` (B28 final).
- Verified code HEAD: `d528f56bc4621b87d2f7bb7abfc8a0d8b8120a90`.
- Scope: migrate `TowerAttackEffects` from Java to Kotlin and add `TowerAttackEffectsKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/TowerAttackEffects.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TowerAttackEffects.kt`.
- `TowerEffectDefinition` is consumed unchanged through its B28 Kotlin record ABI.

## Preserved boundaries

- Public utility constructor remains private; `MAX_EFFECTS_PER_ATTACK` remains public static final `32`.
- `definition`, `newBudget`, `renderAttack`, `renderHit`, and `renderBuff` retain their Java-facing static descriptors and behavior.
- All seven tower particle definitions, trail/hit/buff counts, trace-point cap, world guard, budget cap, and FLASH `Color.WHITE` payload are unchanged.
- Particle spawn overload selection remains data-free versus data-bearing exactly as before.
- `Budget` remains a public static nested class with a private integer constructor and the `remaining()`/`claim()` state machine.

## Known additive interop surface

- Kotlin cannot express the old package-private static `particleDataFor` and package-private `Budget.claim()` members while keeping the existing Java test/caller boundary; both are public in the generated Kotlin class. This is additive visibility only and does not alter the public method descriptors or runtime behavior.
- Kotlin companion/helper metadata and final modifiers are additive generated artifacts.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 98 XML test suites, 326 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `ACA81A4E78A9C3C05581A6C34FA0DED200B01539F5825D9D369CEBAB8B74A9E4`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin outer and nested `Budget` classes are present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
