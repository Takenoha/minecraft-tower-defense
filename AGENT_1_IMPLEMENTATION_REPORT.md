# Agent 1 tactical foundation implementation report

## Scope and commits

- Base: `1afcbe0` (`feat/reward-ux-vouchers`)
- Shared contract: `aa7ff53cd56cd63f0bdc57165c9834d497b35686`
- Branch: `feat/tactical-foundation`
- Foundation implementation: `c9b0b82`

The implementation preserves the existing defense, team, core, seal, and operation boundaries.
Agent 2-owned runtime files were not edited on this branch.

## Implemented behavior

- Added immutable definition, effect, six-tier node, category, rarity, and fail-closed validator
  models for the six initial builds: `rapid-fire`, `long-range`, `heavy-fortress`,
  `flame-suppression`, `ice-lightning`, and `final-defense-line`.
- Added weighted deterministic three-candidate generation keyed by start operation, team, stage,
  and generator version. Candidate IDs are unique and category diversity is used when available.
- Added versioned definition snapshots and candidate/session persistence with operation UUID
  idempotency, owner-only selection, selection cancellation, bind-to-defense, unlock ledger rows,
  and terminal state tracking.
- Added `GENERATED` candidate reuse by team/stage so an interrupted GUI can reopen the same
  persisted candidate snapshot after a restart.
- Added a 27-slot Paper selection GUI integrated with raid-seal right-click and the existing core
  start GUI. Closing the GUI does not start the defense or consume the seal; selection is bound
  before the existing seal consumption and Paper activation path.
- Added start failure/cancellation cleanup for unbound tactical sessions and `RECOVERY` terminal
  cleanup for sessions already bound to a defense.
- Added schema migration v38 and the four tactical tables:
  `tactical_build_sessions`, `tactical_build_candidates`, `tactical_build_operations`, and
  `tactical_build_unlocked_nodes`.
- Added candidate-selection steps to `docs/PAPER_ACCEPTANCE_RUNBOOK.md`.

## Changed files

Production wiring and Paper selection:

- `src/main/java/io/github/takenoha/towerdefense/TowerDefensePlugin.java`
- `src/main/java/io/github/takenoha/towerdefense/paper/RaidSealListener.java`
- `src/main/java/io/github/takenoha/towerdefense/paper/TacticalBuildSelectionGui.java`
- `src/main/java/io/github/takenoha/towerdefense/paper/TacticalBuildSelectionInventoryHolder.java`
- `src/main/java/io/github/takenoha/towerdefense/paper/TacticalBuildSelectionListener.java`
- `src/main/java/io/github/takenoha/towerdefense/paper/TowerDefenseCommand.java`

Definitions and persistence:

- `src/main/java/io/github/takenoha/towerdefense/tactical/`
- `src/main/java/io/github/takenoha/towerdefense/persistence/Tactical*.java`
- `src/main/java/io/github/takenoha/towerdefense/persistence/SchemaMigrator.java`

Tests and acceptance material:

- `src/test/java/io/github/takenoha/towerdefense/tactical/TacticalBuildFoundationTest.java`
- `src/test/java/io/github/takenoha/towerdefense/persistence/DatabaseTest.java`
- `docs/PAPER_ACCEPTANCE_RUNBOOK.md`

## Verification

- Migration version: `38`.
- Final Foundation validation command:
  `./gradlew.bat clean test build --rerun-tasks --no-daemon`.
- Result: `246` tests, `0` failures, `0` errors, `0` skipped; build successful.
- `git diff --check`: clean before commit.
- Paper 26.2 build 87 / Java 25 manual acceptance: not run.

## Agent 2 handoff

Agent 2 runtime commits are `792a5bd889e53c7a30058a0e14ea1cf5164caa2b` and
`59c1e4f2c42dfaef4164bd7fc8366686d82a5680`, with Buzz PR
`buzz://pr?id=35800ba31aa95ec0f8b660ef6508828aec395a1918986100b9573e013da60314&owner=9a44b3bf2660b3731822095c0de5967fa33b3738a9c394e6a636e6041b59cd65&d=minecraft-tower-defense-plugin`.
The shared APIs consumed by that runtime are implemented by `TacticalBuildRepository`:

- `TacticalBuildStateProvider.findActiveByDefense(defenseId)` returns the selected snapshot and
  current highest unlocked tier.
- `TacticalBuildLifecycle.activateAtPreparation`, `advanceAfterWave`, `activateFinalTier`, and
  `markTerminal` are transactional and operation-idempotent.
- Unknown/corrupt snapshots fail closed instead of selecting a default build.

The production runtime wiring described in `AGENT_1_INTEGRATION_NOTES.md` should be applied after
the Agent 2 PR is merged, because this Foundation PR intentionally does not modify
`DefenseSessionManager.java` or `TowerManager.java`.
