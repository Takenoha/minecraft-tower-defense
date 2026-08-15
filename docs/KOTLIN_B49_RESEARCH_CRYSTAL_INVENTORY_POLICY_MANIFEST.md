---
title: "Kotlin B49 Research Crystal Inventory Policy Migration Manifest"
tags: [kotlin-migration, paper-policy, abi]
status: active
created: 2026-08-14
---

# Kotlin B49 Research Crystal Inventory Policy Migration Manifest

## Scope

- Base: `8b187e86c05fa36ac4e44fb3f640f3b2b3949fe0`
- Implementation verification commit: `295186e94fe4a5d024127c104d635082a339964b`
- Final HEAD: `295186e94fe4a5d024127c104d635082a339964b`
- Target: Kotlin scan implementation behind the `ResearchCrystalInventoryPolicy` Java ABI facade
- Boundary test: `ResearchCrystalInventoryPolicyKotlinBoundaryAbiTest.java`
- No caller, tagger, persistence, schema, or Paper wiring changes.

## Preserved contract

- `ResearchCrystalInventoryPolicy` remains a public final utility class with a private no-arg constructor and static `scan(ItemStack[], ItemStack, ResearchCrystalTagger)` boundary.
- The Java outer facade and nested `Candidate` record are intentionally retained as a narrow ABI bridge: Kotlin `@JvmRecord` cannot replace a record component inside its canonical constructor, while the old compact constructor must clone `ItemStack` before normalizing its amount.
- The Kotlin `ResearchCrystalInventoryPolicyKotlinBridge` owns the scan implementation; the Java facade only delegates and retains the exact public record constructor contract.
- `Candidate` remains a public JVM Record nested under the policy, with the same component order/types, `OFF_HAND_SLOT = -1`, canonical constructor, accessors, and `isOffHand()` behavior. Its bytecode contains the required `clone()` then `setAmount()` sequence.
- Storage slots are scanned in ascending order before the offhand slot; only stacks accepted by `ResearchCrystalTagger` become candidates.
- Candidate snapshots are cloned and normalized to the source quantity, and the returned candidate list is unmodifiable.
- Candidate null/slot/quantity validation and existing Java caller references are preserved. No duplicate policy class is emitted; the Java facade is the sole target class.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 118 XML files; 351 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `C059BB2CD92F0247B60BB4851FFDE5838878946552FB0B45DA582A3B2D8BC611`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated policy/bridge class JVM major: 69
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean
- Paper real-server inventory/redeem/GUI/start/restart/win-loss/abort/technical-recovery acceptance remains a separate final gate.
