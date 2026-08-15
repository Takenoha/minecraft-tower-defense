---
title: "Kotlin B55 Core Item Listener Migration Manifest"
tags: [kotlin-migration, paper-listener, core-placement, abi]
status: active
created: 2026-08-14
---

# Kotlin B55 Core Item Listener Migration Manifest

## Scope

- Base: `de0c791d9388408a5899793acebd9b6188efab72`
- Implementation/code verification HEAD: `c75ce07bdf09eb622edf8aa6e8729fa319d80c80`
- Target: `CoreItemListener.java` → `CoreItemListener.kt`
- Boundary test: `CoreItemListenerKotlinBoundaryAbiTest.java`
- The final branch manifest commit is docs-only after the code verification HEAD; no
  `src/main` or `src/test` implementation changes follow the verification commit.

## Preserved contract

- `CoreItemListener` remains a public final Bukkit `Listener` with the public eight-argument
  constructor, `reconcileRegisteredCoreBlocks()`, `registerRecipe()`,
  `recoverPreparedPlacements()`, and the `onCraft`, `onJoin`, and `onInteract` handlers.
  Handler priority and `ignoreCancelled` values remain HIGHEST/true for all three events.
- The recipe keeps the `core` namespace key, current core item template, `D/I` shape, and
  diamond-block/iron-ingot ingredients. The package-private Java `configureRecipe(ShapedRecipe)`
  helper is exposed public-static by Kotlin as an additive visibility change; its recipe shape,
  materials, null guard, and return boundary are unchanged.
- Crafting keeps the shift-click rejection, unbound UUID creation, and tower-recipe discovery
  message. Join reconciliation still repairs registered legacy beacon blocks conservatively and
  removes already-applied core items from player inventories.
- Main-hand right-click placement retains the identity read/cancel boundary, active-defense and
  in-flight guards, target material/tile/core/required-material checks, Overworld check, combat
  area and third-party protection validation, and main-thread Paper mutation boundary.
- Durable placement preserves solo-team creation, team owner/member authorization, full-health
  relocation, destroyed-core rebuild, placement UUID/idempotency, before-block snapshot,
  prepare → physical tag → apply ordering, registry refresh, item cleanup, and user messages.
  Failed target validation, source detachment, block tagging, persistence, and registry paths
  retain the same restoration and `PREPARED` rollback/recovery behavior.
- Startup recovery retains ordinary and relocation recovery separately, never overwrites an
  unknown physical block state, restores source/target snapshots only when tagged or equal to the
  durable before-state, and acknowledges rollback asynchronously after safe restoration.
- `CoreItemTagger`, `CoreBlockTagger`, `DefenseRepository`, `CoreRegistry`, `DatabaseExecutor`,
  `DefenseSessionManager`, `PaperCombatAreaSafetyValidator`, `ThirdPartyRegionProtectionAdapter`,
  `CoreManagementListener`, and plugin wiring are unchanged. Private placement, restoration,
  recovery, scheduling, item-removal, and root-cause helpers remain private; Kotlin companion,
  data-class, and lambda artifacts are additive implementation details.
- The Java package-private `beginGuiRelocation(Player, Block, CoreRecord)` helper is public in
  the single Kotlin implementation class so the existing package caller remains available; this
  is an additive visibility change and does not alter its relocation identity or placement flow.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 124 XML files; 357 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `352A8C00B77D482B4F5A595735745086024B270EF74F2DB6D878B3BD48545C2B`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated listener JVM major: 69
- Kotlin listener classes: outer, `Companion`, `CombatAreaContext`,
  `RelocationPhysicalState`, and one prepare-plan synthetic helper; Java duplicate: absent
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean; worktree clean at the code verification HEAD
- Paper real-server core recipe, GUI relocation, placement, restart, win/loss, abort,
  technical-recovery, and protected-boundary acceptance remains a separate final gate.
