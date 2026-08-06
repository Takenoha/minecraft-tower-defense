# Agent 1 integration notes

## Shared contract

Use `aa7ff53cd56cd63f0bdc57165c9834d497b35686` as the common base. Do not change the signatures
in `io.github.takenoha.towerdefense.tactical` after the contract commit.

## Agent 2 runtime handoff

Agent 2 completed runtime implementation at `792a5bd889e53c7a30058a0e14ea1cf5164caa2b` and
documentation at `59c1e4f2c42dfaef4164bd7fc8366686d82a5680`. Buzz PR:

`buzz://pr?id=35800ba31aa95ec0f8b660ef6508828aec395a1918986100b9573e013da60314&owner=9a44b3bf2660b3731822095c0de5967fa33b3738a9c394e6a636e6041b59cd65&d=minecraft-tower-defense-plugin`

The runtime intentionally leaves `TowerDefensePlugin.java` untouched. After merging the runtime
PR with this Foundation branch, add only the following production wiring:

```java
TacticalBuildRuntime tacticalRuntime = new TacticalBuildRuntime(
        tacticalBuilds,
        tacticalBuilds);

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
        new TowerItemTagger(plugin),
        towerEntityTagger,
        resources,
        tacticalRuntime);
```

The actual local variable should use the existing `this`/field style. Construct the runtime before
the session manager and tower manager. Pass the exact same runtime instance to both. The current
Foundation-only constructors intentionally leave runtime behavior neutral through
`TacticalBuildRuntime.disabled()` for source compatibility; production must use the full
constructors above.

## State and lifecycle boundary

`TacticalBuildRepository` is both `TacticalBuildStateProvider` and `TacticalBuildLifecycle`.
`findActiveByDefense` loads the selected definition snapshot and highest unlocked tier before
activation/recovery. It returns `Optional.empty()` for an unknown defense; the runtime must then
use `EmptyTacticalEffectSnapshot` and never guess a build.

The repository owns schema v38 and these tables; Agent 2 must not add SQL or modify their
transitions:

- `tactical_build_sessions`
- `tactical_build_candidates`
- `tactical_build_operations`
- `tactical_build_unlocked_nodes`

The Paper start path calls `bindToDefense` after the existing `DefenseRepository.tryStart*`
operation returns `STARTED`, before physical seal consumption and `sessions.activate`. If the
bind succeeds, start recovery calls `markTerminal(defenseId, RECOVERY, operationId)` before the
existing `recoverUnfinishedEvent` operation. If bind fails, the selected-but-unbound tactical
session is cancelled and the existing event recovery path is used.

## File ownership

Foundation owns `TowerDefensePlugin.java`, `RaidSealListener.java`, the start GUI/command wiring,
schema migration, definitions, candidate generation, and tactical persistence. Runtime owns
`DefenseSessionManager.java`, `TowerManager.java`, `ActionBarBroker.java`, and the tactical
effect/cache classes. Resolve the shared `docs/PAPER_ACCEPTANCE_RUNBOOK.md` by retaining both
candidate-selection and runtime sections.

## Verification after merge

Run the full command from the merged worktree:

```bat
./gradlew.bat clean test build --rerun-tasks --no-daemon
```

Then execute runbook section 9 on Paper 26.2 build 87 / Java 25. Confirm candidate display,
owner-only confirmation, non-consumptive cancellation, bind-before-consume ordering, automatic
Tier 1–6 unlocks, terminal cache invalidation, restart rebuild, and no per-attack DB reads.

Paper acceptance was not run in the Foundation worktree.
