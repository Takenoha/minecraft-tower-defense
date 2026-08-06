# Agent 2 integration notes

These notes are for Agent 1's final wiring. They intentionally leave
`TowerDefensePlugin.java`, SQL migration, repositories, candidate generation, and selection GUI
to Agent 1.

## Contract and source boundary

- Shared contract commit: `aa7ff53cd56cd63f0bdc57165c9834d497b35686`
- Agent 2 implementation: `792a5bd889e53c7a30058a0e14ea1cf5164caa2b`
- Runtime branch: `feat/tactical-runtime`
- Agent 2 owns the tactical runtime/effect package, `DefenseSessionManager` tactical hooks,
  `TowerManager` combat/repair/destroyer modifier hooks, and the tactical section of the Paper
  acceptance runbook.
- Existing constructor overloads remain available and use `TacticalBuildRuntime.disabled()` so
  old unit/test construction remains source-compatible. Production wiring must use the full
  tactical constructor; otherwise the feature is deliberately neutral.

## Required production objects

After Agent 1 has created the database/repository-backed lifecycle and state provider, construct:

```java
TacticalBuildRuntime tacticalRuntime = new TacticalBuildRuntime(
        tacticalBuildLifecycle,
        tacticalBuildStateProvider);
```

The supplied state provider must return the selected, versioned snapshot and current highest
unlocked tier for `findActiveByDefense(defenseId)`. It must be safe to call on the main thread
only during activation/rebuild; it is not called by the combat hot path.

The lifecycle implementation must make the following operations idempotent by `operationId` and
return the node IDs newly unlocked by that operation:

- `activateAtPreparation(defenseId, operationId)` for Tier 1.
- `advanceAfterWave(defenseId, completedWaveCount, totalWaveCount, operationId)` for 20/40/60/80
  percent progress. `TacticalTierUnlockPolicy` in this branch provides the integer threshold
  helper and short-stage tier ordering.
- `activateFinalTier(defenseId, operationId)` for Tier 6 immediately before the final wave.
- `markTerminal(defenseId, result, operationId)` for victory, defeat, aborted, and recovery.

## Constructor wiring

Pass the same `tacticalRuntime` instance to both full constructors:

```java
sessions = new DefenseSessionManager(
        plugin,
        settings,
        tagger,
        persistence,
        blockMutations,
        escrowDrops,
        rewardQueues,
        coreRegistry,
        regionProtection,
        resources,
        escrowDrops.actionBarBroker(),
        tacticalRuntime);

towerManager = new TowerManager(
        plugin,
        settings,
        repository,
        towerRepository,
        databaseExecutor,
        sessions,
        coreRegistry,
        towerRegistry,
        towerItemTagger,
        towerEntityTagger,
        resources,
        tacticalRuntime);
```

`TacticalBuildRuntime` implements `TacticalEffectSnapshotProvider`, so no adapter is required for
the `TowerManager` argument. Construct the runtime before `DefenseSessionManager`; ensure the
state provider can load a selected snapshot before any recovered `sessions.activate` call.

## Lifecycle and recovery expectations

`DefenseSessionManager` rebuilds the effect cache during activation. If that rebuild fails, the
event remains neutral and the old entry is explicitly invalidated. A failure during an unlock
operation is recorded as a persistence failure and follows the existing recovery/finish path.
When a terminal result is handled, `TacticalBuildRuntime.markTerminal` invalidates the cache in a
`finally` block. Do not retain a runtime snapshot in another static/global cache after the event
ends.

The lifecycle/repository implementation should preserve the selected definition snapshot and
highest tier across restart. A missing or unknown selection must return `Optional.empty()` from
the state provider, producing `EmptyTacticalEffectSnapshot` rather than a default build or a
guessed modifier.

## Calculation and hot-path boundary

The runtime reads one snapshot per TowerManager tick and passes it through all seven tower attack
paths. It also reads the same event snapshot for repair pricing and destroyer tower damage. Do not
replace these calls with per-attack repository queries or node-list scans. The immutable snapshot
contains the preaggregated effect maps; live target/core facts are supplied through
`TacticalTargetContext` at the point of damage/interval evaluation.

## Deliberately untouched files

No Agent 2 patch is required in `TowerDefensePlugin.java`; add only the imports and constructor
arguments above during final integration. `RaidSealListener.java`, the tactical repository and
schema migrator, candidate generator, selection GUI, and definition/config ownership remain with
Agent 1. Keep the existing `ResourceRepository`/voucher flow unchanged.

## Live acceptance handoff

Run section 9 of `docs/PAPER_ACCEPTANCE_RUNBOOK.md` after wiring. It covers automatic unlock
notifications, all six builds and seven tower paths, core HP thresholds, repair and destroyer
damage modifiers, restart rebuild, terminal invalidation, neutral unknown state, and the no-DB
per-attack performance boundary. Paper 26.2 build 87 / Java 25 acceptance is still outstanding
from this branch.
