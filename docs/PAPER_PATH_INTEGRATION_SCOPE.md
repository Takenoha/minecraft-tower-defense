# PR #17: Paper path integration and load measurement boundary

PR #17 adds the read-only seam needed before running the plugin against a real Paper server. It
also measures path-inspection cost without adding database work to the enemy tick.

## Included

- `PaperEnemyPathIntegrationBoundary` owns the main-thread call into the Paper path inspector.
- A Paper read failure becomes `UNAVAILABLE`, so a transient integration problem cannot turn into
  a terrain action; failures are counted for diagnosis.
- Each active defense event keeps in-memory inspection latency, decision, and builder-attempt
  counters. The terminal log reports count, average/max nanoseconds, role actions, and bridge
  acknowledgements.
- `EnemyPathMetricsTest` covers the load counters and invalid snapshot guards without requiring a
  live server or database write.

## Deliberate boundary

This PR does not enable terrain mutation, replace the Paper pathfinder, or claim a real-server test
result. The production `TerrainMutationPolicy(false)` and tagged enemy event cancellation remain
unchanged. A Paper test server is still required to validate entity movement, block snapshots, and
tick-time latency under a configured enemy load.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
