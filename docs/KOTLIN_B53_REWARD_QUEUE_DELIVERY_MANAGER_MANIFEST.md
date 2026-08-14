---
title: "Kotlin B53 Reward Queue Delivery Manager Migration Manifest"
tags: [kotlin-migration, paper-manager, reward-queue, abi]
status: active
created: 2026-08-14
---

# Kotlin B53 Reward Queue Delivery Manager Migration Manifest

## Scope

- Base: `2205ecf728921a76d0aae8b41669ec67b8d45025`
- Implementation/code verification HEAD: `8c82cfe24ea1aa647a6ece40f380baf5a7d4b1b3`
- Target: `RewardQueueDeliveryManager.java` → `RewardQueueDeliveryManager.kt`
- Boundary test: `RewardQueueDeliveryManagerKotlinBoundaryAbiTest.java`
- The final branch manifest commit is docs-only after the code verification HEAD; no
  `src/main` or `src/test` implementation changes follow the verification commit.

## Preserved contract

- `RewardQueueDeliveryManager` remains a public final `AutoCloseable` with the public
  four-argument and five-argument constructors, `onPlayerJoin(Player)`,
  `onPlayerQuit(Player)`, `onEventSettled(UUID)`, `tagger()`, and `close()` Java
  boundaries.
- Main-thread guards, closed/active-run suppression, asynchronous database loading,
  main-thread callback scheduling, pending-entry ordering, and cleanup of receipts for
  non-pending queue rows are preserved.
- Delivery preparation uses the same deterministic queue/player operation UUID and
  retains ACQUIRED/ALREADY_ACQUIRED, ALREADY_DELIVERED, VOIDED, and HELD_BY_OTHER
  outcome handling. Inventory mutation remains on the Paper main thread.
- Existing receipt quantities are counted before adding more. Standard payloads are
  cloned, split at the item stack limit, and tagged. Research-crystal v1/v2 payload
  validation, segment offsets, stack-limit splitting, batch identity, and receipt
  tagging are retained.
- Full-inventory and invalid-payload/item failures stop delivery without committing a
  database transition. Successful inventory insertion commits the same
  `markRewardDelivered` operation, accepts APPLIED/ALREADY_APPLIED, strips receipts,
  and continues in queue order.
- `RewardQueueReceiptTagger`, `ResearchCrystalTagger`, `EscrowRepository`,
  `DatabaseExecutor`, Bukkit inventory scheduling, and `RewardQueueDeliveryListener`
  callers/wiring are unchanged. Private delivery, receipt, scheduling, logging, and
  root-cause helper boundaries remain private; Kotlin-generated lambda/data helpers are
  additive implementation artifacts only.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 122 XML files; 355 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `F6CC11201349F783B64D20DEBABD4959B40065E42B82E470E993FC30AF234A69`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated manager JVM major: 69
- Kotlin manager classes: outer class, `Companion`, `DeliveryRun`, and
  `RewardLoadResult`; Java duplicate: absent
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean; worktree clean at the code verification HEAD
- Paper real-server reward delivery, receipt protection, inventory-full retry,
  reconciliation, restart, win/loss, abort, and technical-recovery acceptance remains
  a separate final gate.
