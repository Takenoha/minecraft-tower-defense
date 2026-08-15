---
title: "Kotlin B56 Paper Escrow Drop Manager Migration Manifest"
tags: [kotlin-migration, paper-manager, escrow-drop, abi]
status: active
created: 2026-08-14
---

# Kotlin B56 Paper Escrow Drop Manager Migration Manifest

## Scope

- Base: `8da899d6674619128e8f77cbaa5d51e4fef95663`
- Implementation/code verification HEAD: `52a4a451eccc0e1002add902b4811c18d2dca6b4`
- Target: `PaperEscrowDropManager.java` → `PaperEscrowDropManager.kt`
- Boundary test: `PaperEscrowDropManagerKotlinBoundaryAbiTest.java`
- The final branch manifest commit is docs-only after the code verification HEAD; no
  `src/main` or `src/test` implementation changes follow the verification commit.

## Preserved contract

- `PaperEscrowDropManager` remains a public final Paper bridge with the public four-
  argument and five-argument constructors. The nullable `ResourceRepository` compatibility
  dependency remains part of the five-argument boundary; the four-argument constructor
  delegates to the same implementation state.
- `prepareBlockDrops` retains its main-thread and argument guards, block-drop filtering,
  deterministic drop/create operation IDs, cloned encoded payloads, existing HELD-row
  reuse, terminal-row conflict rejection, prepared-row rollback on failure, and immutable
  result boundary.
- `spawnPreparedDrops` and enemy-drop issuance preserve duplicate-display suppression,
  escrow tagger usage, pickup delay/tick initialization, display-entity persistence, and
  the asynchronous prepare-to-main-thread spawn boundary.
- `discardPreparedDrops` retains deterministic void operation IDs and the prepared-row
  settlement boundary. `readyForTerminal` and `beginTerminal` retain pending-claim
  suppression and terminal-event freezing.
- Pickup handling preserves tagged-item cancellation, terminal and non-player guards,
  per-display pending-claim idempotency, deterministic claim operations, asynchronous
  claim callbacks, APPLIED/ALREADY_APPLIED cleanup, display-row clearing, and resource
  pickup sound/action-bar feedback.
- Event display removal preserves immediate physical cleanup, deferred action-bar cleanup,
  startup removal of all tagged displays, chunk-load stale-display checks, and pending/
  terminal state reset. `tagger()` and `actionBarBroker()` retain their public accessors.
- `PreparedDrop` remains a public JVM Record with the `EscrowDrop` and `ItemStack`
  components, public canonical constructor, accessors, and null validation. Kotlin
  record/data helpers are additive implementation artifacts; the Java record boundary is
  preserved for existing callers.
- `EscrowDropTagger`, `EscrowRepository`, `ResourceRepository`, `DatabaseExecutor`,
  `ActionBarBroker`, `PaperItemStackCodec`, `PaperEnemyTerrainAction`,
  `EventEnemyListener`, `EscrowDropListener`, `DefenseSessionManager`, and plugin wiring
  are unchanged. Private display lookup, spawn, deterministic-operation, and main-thread
  helper boundaries remain private; Kotlin companion/lambda helpers are additive.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 125 XML files; 358 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `31C0DEC47C45DE8FA0A2B5D0E1AF987F4A96DC2B74407D1AF07F13B479741AD3`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated manager JVM major: 69
- Kotlin manager classes: outer class, `Companion`, and `PreparedDrop`; Java duplicate:
  absent
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean; worktree clean at the code verification HEAD
- Paper real-server escrow display, pickup, terminal settlement, inventory/lifecycle,
  restart, win/loss, abort, and technical-recovery acceptance remains a separate final
  gate.
