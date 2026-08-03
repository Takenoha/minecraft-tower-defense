# PR #14: fail-closed world-aware obstacle classification

PR #14 adds the next boundary after the role-specific planner. Paper reads the candidate block,
its target BlockData, and one support block on the main thread; a Paper-independent classifier
then returns a conservative obstacle result for the role planner and terrain gate.

## Included

- `EnemyObstacleClassification` distinguishes clear paths, protected state, ordinary breakable
  blocks, verified buildable gaps, and unavailable state.
- `EnemyObstacleClassifier` is Paper-independent and treats combat-area misses, code-owned
  materials, current inventory/tile/core state, target tile state, and unknown action shapes as
  non-actionable.
- Builder gaps require a replaceable candidate, a solid support block inside the combat area, and
  a support block that is not a core, tile/inventory block, or required protected material.
- `PaperEnemyObstacleClassifier` enforces the Paper main-thread boundary and captures the target
  tile state before any future mutation can be considered.
- `PaperEnemyTerrainAction` rejects obstacle/action mismatches before the existing WAL adapter;
  the existing role gate and mandatory protection checks remain in force.
- Classified facts can be converted to the existing `EnemyPathContext`, and PR #15 connects that
  conversion to the role-aware path controller rather than inferring authorization from a failed
  pathfinder call.

## Deliberate boundary

The production plugin still constructs `TerrainMutationPolicy(false)`, and event listeners still
cancel tagged enemy block events. PR #15 invokes the read-only Paper path inspection boundary but
does not place bridge blocks, destroy blocks, or enable terrain mutation. Multi-block bridge
planning, load/region preflight during movement, and Paper integration tests remain later work.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
