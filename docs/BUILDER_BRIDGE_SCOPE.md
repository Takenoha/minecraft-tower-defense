# PR #16: bounded builder bridge action boundary

PR #16 turns the PR #15 `BUILD_SUPPORT` planner result into a guarded one-block temporary bridge
action. It keeps the world mutation behind the existing disabled production policy.

## Included

- `EnemyBridgePlanner` admits only a `BUILDABLE_GAP` inside the combat area with verified support,
  rejects air and mandatory protected materials, and plans at most one block per path decision.
- Unresolved `TEMPORARY_BLOCK` rows are counted from the durable SQLite ledger. An event may have at
  most eight active temporary bridge blocks, including rows left prepared or applied across a
  restart.
- `PaperEnemyPathController` records the candidate's observed before-state and the verified support
  BlockData. `PaperEnemyTerrainAction` refuses a stale candidate when a player changed the block.
- Accepted bridge placement uses the existing prepare/apply WAL as `TEMPORARY_BLOCK`; normal
  terminal settlement and technical recovery therefore reuse the existing reverse-generation,
  conflict-safe rollback path.
- The live path controller invokes the action only for a builder `BUILD_SUPPORT` decision and
  resets path-failure progress only after a placement is acknowledged.

## Deliberate boundary

The production plugin still constructs `TerrainMutationPolicy(false)`, so tagged enemy block events
remain cancelled and the path-driven bridge action remains read-only in production. This PR does
not enable terrain mutation, add multi-block bridge chains, implement destroyer breaking, or add a
Paper-server integration test. Those are separate activation and integration milestones.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
