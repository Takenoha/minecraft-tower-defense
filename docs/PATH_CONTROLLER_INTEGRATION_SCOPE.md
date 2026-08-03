# PR #15: obstacle facts into enemy path control

PR #15 connects the main-thread obstacle snapshot to the existing role-aware planner without
enabling survival-world terrain mutation.

## Included

- `PaperEnemyPathController` inspects the next horizontal candidate block on the Paper main
  thread and delegates classification to `PaperEnemyObstacleClassifier`.
- Normal enemies and destroyers inspect an air target for clear, protected, unavailable, or
  breakable path decisions.
- Builders use the verified support block as a planning target so a supported replaceable gap can
  become `BUILDABLE_GAP`; no block is placed by this controller.
- `EnemyPathController` converts the live facts into `EnemyPathContext` and calls
  `EnemyRolePlanner`.
- Repeated protected-path failures still recover, while a classified breakable obstacle or
  buildable gap remains an explicit role action for a later mutation controller.

PR #16 adds the bounded builder bridge action boundary described in
`docs/BUILDER_BRIDGE_SCOPE.md`. It still keeps the production mutation policy disabled.

## Deliberate boundary

The production plugin continues to construct `TerrainMutationPolicy(false)`, and
`EntityChangeBlockEvent` remains cancelled for tagged enemies. This change only connects
read-only world facts to the planner. Destroyer block operations, path detours, and mutation
activation remain later work.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
