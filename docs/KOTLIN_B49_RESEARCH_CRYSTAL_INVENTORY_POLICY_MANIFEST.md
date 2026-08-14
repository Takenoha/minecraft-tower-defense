---
title: "Kotlin B49 Research Crystal Inventory Policy Migration Manifest"
tags: [kotlin-migration, paper-policy, abi]
status: active
created: 2026-08-14
---

# Kotlin B49 Research Crystal Inventory Policy Migration Manifest

## Scope

- Base: `8b187e86c05fa36ac4e44fb3f640f3b2b3949fe0`
- Implementation verification commit: `38b87ec326f3488ac5570a6ec8ba23be913c51ed`
- Target: `ResearchCrystalInventoryPolicy.java` → `ResearchCrystalInventoryPolicy.kt`
- Boundary test: `ResearchCrystalInventoryPolicyKotlinBoundaryAbiTest.java`
- No caller, tagger, persistence, schema, or Paper wiring changes.

## Preserved contract

- `ResearchCrystalInventoryPolicy` remains a public final utility class with a private no-arg constructor and static `scan(ItemStack[], ItemStack, ResearchCrystalTagger)` boundary.
- `Candidate` remains a public JVM Record nested under the policy, with the same component order/types, `OFF_HAND_SLOT = -1`, canonical constructor, accessors, and `isOffHand()` behavior.
- Storage slots are scanned in ascending order before the offhand slot; only stacks accepted by `ResearchCrystalTagger` become candidates.
- Candidate snapshots are cloned and normalized to the source quantity, and the returned candidate list is unmodifiable.
- Candidate null/slot/quantity validation and existing Java caller references are preserved. No Java duplicate class is emitted.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 118 XML files; 351 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `4BF30CD52626BC42576CD85D22033ABA0D4589879F04E68C0F6BCE2A15B7E0B7`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated class JVM major: 69
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean
- Paper real-server inventory/redeem/GUI/start/restart/win-loss/abort/technical-recovery acceptance remains a separate final gate.
