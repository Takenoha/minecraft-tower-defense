---
title: "Kotlin B54 Paper Block Mutation Adapter Migration Manifest"
tags: [kotlin-migration, paper-adapter, block-mutation, abi]
status: active
created: 2026-08-14
---

# Kotlin B54 Paper Block Mutation Adapter Migration Manifest

## Scope

- Base: `d10f8c7efcb87b47bbb0e12b97e53424d043187a`
- Implementation/code verification HEAD: `567fdf4aa65dffe563e8677cefdf90014ec391b3`
- Target: `PaperBlockMutationAdapter.java` → `PaperBlockMutationAdapter.kt`
- Boundary test: `PaperBlockMutationAdapterKotlinBoundaryAbiTest.java`
- The final branch manifest commit is docs-only after the code verification HEAD; no
  `src/main` or `src/test` implementation changes follow the verification commit.

## Preserved contract

- `PaperBlockMutationAdapter` remains a public final class with the public one-argument
  constructor and the two-argument ledger/planner constructor. The old two-argument
  constructor was package-private; Kotlin exposes it publicly as an additive visibility
  change because the implementation is now a single Kotlin class. Existing callers use
  the one-argument constructor and retain the same descriptor.
- `nextGeneration(UUID, Block)` and `countUnresolvedTemporaryBlocks(UUID)` retain their
  main-thread guards and durable ledger delegation.
- `apply` keeps the before/after snapshot construction, existing-row generation and
  before-state reuse, prepare/prepare-apply/apply operation UUID boundaries, conflict
  checks, rolled-back/applied idempotency, live-state compare-and-set checks, physical
  Paper application, and expected-after verification.
- `recoverEvent` and `settleEvent` retain unresolved-row ordering from the ledger,
  temporary-block rollback, terminal-phase validation, event-owned applied-state
  validation, prepared rollback reuse, planner decisions, deterministic rollback
  operation UUIDs, restore/skip conflict guards, and durable rollback acknowledgement.
- `PaperBlockStateCodec`, `BlockChangeRepository`, `BlockRollbackPlanner`,
  `DefenseSessionManager`, `PaperEnemyTerrainAction`, and plugin wiring are unchanged.
  Private validation, block lookup, deterministic-operation, and main-thread helper
  boundaries remain private; Kotlin companion/lambda helpers are additive artifacts.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 123 XML files; 356 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `41E335385EF254FB99BA5931B5C45F725685F7575179EC85FC287A71F430706E`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated adapter JVM major: 69
- Kotlin adapter classes: outer class plus `Companion`; Java duplicate: absent
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean; worktree clean at the code verification HEAD
- Paper real-server block mutation, rollback/recovery, restart, win/loss, abort,
  technical-recovery, and terrain acceptance remains a separate final gate.
