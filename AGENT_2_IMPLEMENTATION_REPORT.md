# Agent 2 tactical runtime implementation report

## Scope and commits

- Base: `1afcbe0` (`feat/reward-ux-vouchers` / `docs/player-guide`)
- Shared contract: `aa7ff53cd56cd63f0bdc57165c9834d497b35686`
- Branch: `feat/tactical-runtime`
- Runtime implementation commit: `792a5bd889e53c7a30058a0e14ea1cf5164caa2b`
- Runtime validation HEAD: `792a5bd889e53c7a30058a0e14ea1cf5164caa2b`

The implementation commit is based directly on the shared contract commit. The report and
integration notes are documentation follow-up commits; the runtime validation HEAD above is the
source commit whose behavior was tested.

## Implemented behavior

- Added `TacticalEffectCompiler`, `TacticalEffectSnapshotImpl`, and `TacticalEffectCache`.
  Selected node effects are compiled once per lifecycle/recovery rebuild into maps keyed by
  tower and condition. The attack loop never scans node IDs or queries persistence.
- Added `TacticalBuildRuntime`, which couples the shared lifecycle boundary to cache rebuilds and
  terminal invalidation. Rebuild failures remove the previous entry and return to the neutral
  behavior boundary.
- Added `TacticalTierUnlockPolicy` for exact integer 20/40/60/80 percent thresholds,
  including short-stage multi-tier ordering. The Agent 1 lifecycle implementation should use
  this helper when applying progress operations.
- Hooked `DefenseSessionManager` at preparation start, completed-wave progress, final-wave
  preparation, and terminal/recovery cleanup. Operation IDs are deterministic for the progress
  hooks, and notification failures never roll back a committed unlock.
- Applied the shared snapshot to all seven tower combat paths: damage, attack interval, range,
  Cannon/Flame area radius, Frost slow strength, Lightning chain count, Flame burn duration,
  Support radius/buff strength, repair cost, and Destroyer tower damage.
- Added live `TacticalTargetContext` facts for target HP fraction, core HP fraction, boss role,
  Slowness, and burning. Core thresholds use strictly below 50% and 30%; target high/low
  thresholds use the shared contract semantics.
- Added a lower-priority tactical action-bar notice alongside the existing countdown/pickup
  broker. Chat and note-block sound are emitted once per result containing newly unlocked nodes.
- Extended `docs/PAPER_ACCEPTANCE_RUNBOOK.md` with runtime, modifier, restart, terminal, and
  performance acceptance steps.

## Changed files

Runtime/effects:

- `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalBuildRuntime.java`
- `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalEffectCache.java`
- `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalEffectCompiler.java`
- `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalEffectSnapshotImpl.java`
- `src/main/java/io/github/takenoha/towerdefense/tactical/TacticalTierUnlockPolicy.java`
- `src/main/java/io/github/takenoha/towerdefense/runtime/DefenseSessionManager.java`
- `src/main/java/io/github/takenoha/towerdefense/paper/TowerManager.java`
- `src/main/java/io/github/takenoha/towerdefense/runtime/ActionBarBroker.java`

Tests and acceptance material:

- `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalBuildRuntimeTest.java`
- `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalEffectCacheTest.java`
- `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalEffectCompilerTest.java`
- `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalTierUnlockPolicyTest.java`
- `src/test/java/io/github/takenoha/towerdefense/runtime/ActionBarBrokerTest.java`
- `docs/PAPER_ACCEPTANCE_RUNBOOK.md`

`TowerDefensePlugin.java`, `RaidSealListener.java`, tactical SQL/repositories, candidate
generation, selection GUI, and `TowerSettings.java` were intentionally not changed.

## Existing calculation order and insertion point

The existing TowerManager calculation remains the base. For damage and attack interval the
effective value is computed as:

1. TowerSettings profile value.
2. Existing Support stack multiplier.
3. Existing battle boost (`POWER` or `SPEED`).
4. Tactical unconditional/conditional multiplier from the active snapshot.
5. Existing final round and positive/integer clamp.

Range keeps the existing profile, Support range stack, and `RANGE` battle boost, then applies the
tactical additive range before the safe range clamp. Area radius, chain count, slow strength,
burn duration, and Support radius follow the same base-then-tactical boundary. Conditional facts
are read from the live entity/core state at attack time, while the effect maps themselves remain
immutable in memory.

Repair cost is scaled only at the event-scoped repair calculation and is rounded/clamped before
the repository call. Destroyer damage to towers is scaled before the serialized persistence
mutation. No tower row, core maximum HP, core saved HP, or permanent tower value is modified by a
tactical effect.

## Verification

- `./gradlew.bat test --no-daemon`: 251 tests, successful.
- `git diff --check`: clean before the implementation commit.
- Commit trailers verified on `792a5bd`: `Co-authored-by` and `Signed-off-by` both use the
  repository-configured human identity.
- The required final `./gradlew.bat clean test build --rerun-tasks --no-daemon` is run after the
  documentation follow-up is committed and is reported with the PR result.

## Runtime/performance notes

`TacticalEffectCache.currentForDefense` is a synchronized in-memory map lookup. Node snapshots
are read and compiled only during activation, an unlock lifecycle operation, or startup recovery.
When state is missing, invalid, or fails to rebuild, the cache returns
`EmptyTacticalEffectSnapshot`; it never guesses a stronger effect. Terminal handling invalidates
the event key in a `finally` path even when lifecycle persistence reports an error.

## Not verified in this worktree

Paper 26.2 build 87 / Java 25 server acceptance was not run. Production plugin wiring to Agent 1's
actual lifecycle and state-provider implementations remains the integration step described in
`AGENT_2_INTEGRATION_NOTES.md`. The tactical runbook records the required live-server checks.
