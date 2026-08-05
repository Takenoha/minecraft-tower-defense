# PR #13: role-specific enemy composition and action gate

PR #13 adds the Paper-independent role boundary needed before enemy terrain mutation can be
enabled. It does not enable terrain mutation in the production plugin.

## Included

- Optional `enemies.destroyer-ratio` and `enemies.builder-ratio` settings with non-negative,
  finite values whose sum cannot exceed one. Existing configurations keep the 0.15/0.10 defaults.
- A deterministic wave role schedule for normal enemies, destroyers, builders, intermediate
  bosses, and a final-wave boss slot. Special-role allocation grows by a bounded stage/wave
  multiplier and never changes the total logical enemy count.
- Role metadata in `TaggedEnemy` and the event-enemy PDC. Legacy tags without the role key remain
  readable as normal enemies; malformed role values are rejected.
- Role-specific navigation speed and persisted ledger types for spawned event enemies.
- A Paper-independent path planner that distinguishes advance, protected-obstacle recalculation,
  destroyer breaking, builder support, and bounded recovery.
- A terrain authorization gate: destroyers may break, builders may build, and normal enemies may
  break only when a caller explicitly proves the fallback condition. Mandatory block protection
  still runs first.

## PR #14 obstacle classification boundary

PR #14 adds a fail-closed `EnemyObstacleClassifier` and a Paper main-thread adapter. Protected
current state, target tile state, out-of-area coordinates, unsupported action shapes, and unsafe
support blocks cannot become terrain actions. Breakable and buildable classifications can be
converted to the existing `EnemyPathContext` for the PR #15 path controller, while the Paper
action handler rejects a role/action mismatch before the WAL adapter.

PR #15 connects the read-only Paper obstacle snapshot to the role-aware planner. The controller
keeps direct pathfinder success authoritative and supplies classified protected, breakable, or
buildable states only when the direct path is unavailable.

The production `TerrainMutationPolicy` remains disabled and tagged enemy events remain cancelled.
No bridge placement, destroyer block operation, or activation is included. See
`docs/OBSTACLE_CLASSIFICATION_SCOPE.md`.

## Deliberate boundary

The production plugin still constructs `TerrainMutationPolicy(false)`. Actual builder bridge
placement, destroyer block/tower attacks, custom Mob types, and enabling terrain mutation remain
later Paper integration work. The role schedule, PDC metadata, and read-only path controller
are therefore safe to ship independently of world mutation.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
