# PR #18: destroyer block-action boundary

PR #18 connects the `BREAK_OBSTACLE` path decision for destroyers to a guarded, path-driven
block-break boundary. It reuses the existing Paper obstacle classifier, event-owned block WAL, and
drop escrow without changing the production activation setting.

## Included

- `PaperEnemyPathController.planBreak` creates a main-thread, read-only candidate only for a
  destroyer and only when the live block is classified as `BREAKABLE`.
- `PaperEnemyTerrainAction.tryBreakObstacle` rechecks the classification, combat-area policy,
  role gate, protected-block policy, and observed before-state before applying the expected AIR
  state.
- Breaks are recorded as `EVENT_BLOCK` rows. Ordinary block drops are prepared in escrow before
  the WAL apply and displayed only after the applied acknowledgement; failed applies void the
  prepared rows.
- `EnemyPathMetrics` records break attempts and successful acknowledgements in the terminal log.

## Deliberate boundary

The production `TerrainMutationPolicy(false)` remains unchanged, so this path is activation-ready
but does not currently mutate a live world. A player edit between planning and apply fails closed,
protected/core/inventory/tile blocks remain rejected, normal enemies cannot use this operation,
and event-owned destruction is not restored during ordinary terminal settlement. The real Paper
server movement and tick-load test remains a separate environment-dependent gate.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks --no-daemon
```
