---
title: "Kotlin B51 Tactical Build Selection Listener Migration Manifest"
tags: [kotlin-migration, paper-listener, tactical-selection, abi]
status: active
created: 2026-08-14
---

# Kotlin B51 Tactical Build Selection Listener Migration Manifest

## Scope

- Base: `109d746db1f088ed457908c4102935dd0456f921`
- Implementation/code verification HEAD: `8a78415b71869dc49e3c3594f13b5c0aeb325a47`
- Target: `TacticalBuildSelectionListener.java` → `TacticalBuildSelectionListener.kt`
- Boundary test: `TacticalBuildSelectionListenerKotlinBoundaryAbiTest.java`
- The final branch manifest commit is docs-only after the code verification HEAD; no
  `src/main` or `src/test` implementation changes follow the verification commit.

## Preserved contract

- The listener remains public final, implements Bukkit `Listener`, and retains the
  seven-argument public constructor, `beginSelection(Player, UUID, long, UUID)`,
  and the three public event-handler descriptors.
- `onClick` preserves the holder ownership/raw-slot guard, candidate and branch
  selection refreshes, close behavior, branch-required validation, asynchronous
  selection operation, outcome handling, cancellation recovery, and existing
  `TowerDefenseCommand.startWithSeal` handoff.
- `onDrag` continues to cancel only tactical-selection inventory drags, while
  `onClose` retains `MONITOR` priority, the confirming guard, cancellation UUID,
  asynchronous repository call, and warning logging on failure.
- Candidate reload/generation keeps team-owner, core-team, unlocked-stage, existing
  generated-session, generator-version, persistence, and main-thread scheduling
  boundaries. Existing `DefenseRepository`, `TacticalBuildRepository`,
  `DatabaseExecutor`, GUI/holder, command, and plugin wiring are unchanged.
- Private `cancelAfterSelectionFailure`, `runOnMainThread`, and static
  `rootMessage(Throwable)` helper boundaries remain present; `rootMessage` keeps
  the CompletionException unwrapping and root-cause message fallback.

## Verification

- Command: `gradlew clean test`
- Result: `BUILD SUCCESSFUL`
- Test reports: 120 XML files; 353 tests; failures 0; errors 0; skipped 0
- Fat JAR SHA-256: `DD0891DBCCAE555AD5C775A7AE28D2731FA74A106735ECF349B6FE55ED9CA794`
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Generated listener JVM major: 69
- Kotlin listener classes: outer class plus `Companion`; Java duplicate: absent
- Kotlin runtime entries: 1045
- `kotlin/reflect/jvm/internal/*` entries: 0
- `git diff --check`: clean; worktree clean
- Paper real-server tactical-selection, GUI, start/restart, win/loss, abort, and
  technical-recovery acceptance remains a separate final gate.
