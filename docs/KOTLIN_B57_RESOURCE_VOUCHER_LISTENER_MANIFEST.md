---
title: "Kotlin B57 Resource Voucher Listener Migration Manifest"
tags: [kotlin-migration, paper-listener, resource-voucher, abi]
status: active
created: 2026-08-14
---

# Kotlin B57 Resource Voucher Listener Migration Manifest

## Scope

- Base: `e501d2e9bd7cbd7babd9526e3d5539b01561aaa2` (B56 final)
- Implementation and code-verification HEAD: `0bb4699d214daab50acd3d6ba6ba5f42d04645c5`
- Migrated source: `src/main/java/io/github/takenoha/towerdefense/paper/ResourceVoucherListener.java` → `src/main/kotlin/io/github/takenoha/towerdefense/paper/ResourceVoucherListener.kt`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/paper/ResourceVoucherListenerKotlinBoundaryAbiTest.java`
- Final branch state: this manifest is the documentation-only commit after the code-verification HEAD.

## Preserved Java/Paper boundary

- `ResourceVoucherListener` remains a public final Bukkit `Listener` with the eight-argument public constructor:
  `JavaPlugin`, `DefenseRepository`, `DatabaseExecutor`, `DefenseSessionManager`, `CoreRegistry`, `ResourceRepository`, `ResourceVoucherRepository`, and `ResourceVoucherTagger`.
- All 33 event handlers remain public and retain their event parameter descriptors and `EventHandler` priorities/`ignoreCancelled` values. This includes the LOWEST core-interaction gate, NORMAL vault/join/respawn/quit handlers, HIGH voucher interaction, and HIGHEST transfer/protection and lifecycle handlers.
- The core-interaction and vault GUI paths retain their cancellation, holder, owner/team, balance, and resource-operation boundaries.
- Join, respawn, quit, held-item changes, and vault interaction retain player recovery guards and main-thread/asynchronous callback boundaries.
- Inventory, crafting, smithing, anvil, grindstone, crafter, placement, dispense, consume, interaction, armor-stand, hanging, swap-hand, death, merge, despawn, damage, portal, and teleport protection continue to use the canonical voucher predicates and fail closed for forbidden transfers.
- Withdrawal, delivery, and redemption retain deterministic operation IDs, receipt/canonical validation, owner/member authorization, resource and voucher repository transactions, offline holds, rollback/recovery, reconciliation, and terminal settlement behavior.
- Existing private static and instance helper boundaries, including `onlinePlayer`, `deterministic`, `isForbiddenVoucherInventory`, `rootMessage`, `runOnMainThread`, and vault/recovery helpers, remain private. Kotlin `Companion`, data-holder, and lambda classes are additive compiler output.
- `TowerDefensePlugin`, `DefenseSessionManager`, `CoreManagementListener`, `ResourceVoucherTagger`, `ResourceRepository`, `ResourceVoucherRepository`, `DatabaseExecutor`, `PlayerRecoveryGuard`, and the other existing caller/dependency boundaries were not changed by this slice.

## Verification

The fixed implementation HEAD was checked with the explicit Git safe-directory override; the same HEAD was observed before and after the build:

```text
HEAD_BEFORE=0bb4699d214daab50acd3d6ba6ba5f42d04645c5
HEAD_AFTER=0bb4699d214daab50acd3d6ba6ba5f42d04645c5
```

Command:

```text
gradlew.bat clean test
```

Result:

```text
BUILD SUCCESSFUL
126 XML test reports
359 tests
0 failures / 0 errors / 0 skipped
```

Additional fixed-HEAD checks:

- `git diff --check e501d2e9bd7cbd7babd9526e3d5539b01561aaa2..0bb4699d214daab50acd3d6ba6ba5f42d04645c5`: clean.
- `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` SHA-256: `5C432DAC516015F3B32E61B2AD321060FF816EE824D43B84DE73F39BE3DAEE4D`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- No `ResourceVoucherListener.class` duplicate exists under `build/classes/java/main`.
- Kotlin output contains `ResourceVoucherListener` plus `Companion`, `DeliveryRecovery`, `RecoveryData`, `RedeemRecovery`, `VaultData`, and `WithdrawalRequest`; all inspected class files are JVM major 69.
- The packaged Kotlin runtime contains 1045 entries and `kotlin/reflect/jvm/internal/*` contains 0 entries.
- The code-verification worktree was clean before this documentation-only final commit.

## Known additive Kotlin surface

Kotlin generates `Companion`, final method modifiers, nullable annotations/metadata, and private data-holder/lambda implementation classes. Kotlin non-null checks can precede the former Java explicit checks when Java callers pass null, so NPE message/order can differ; the normal non-null caller contract and operation semantics are preserved. These are additive interop differences, not migration blockers.

Paper real-server acceptance remains a separate gate: voucher vault UI, withdrawal/delivery/redeem, offline/recovery handling, transfer protection, restart, win/loss, abort, and technical recovery.
