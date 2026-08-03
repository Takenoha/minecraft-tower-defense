# PR #6: normal terminal terrain settlement

This milestone connects the PR5 block ledger to normal `VICTORY`, `DEFEAT`, and `ABORTED`
termination without enabling enemy terrain mutation in production.

## Included

- Schema v6 adds a durable `SETTLED` block-row status and migrates v5 databases without losing
  mutation or rollback-operation records.
- `EVENT_BLOCK` rows are settled only after their physical apply acknowledgement and remain in the
  world, matching the confirmed rule that enemy destruction is not restored at normal termination.
- `TEMPORARY_BLOCK` rows use the existing reverse-generation rollback planner, so player edits are
  preserved as durable conflicts instead of being overwritten.
- Paper terminal handling settles the terrain before the asynchronous event-finish transaction.
  Partial progress is idempotent; a failure keeps the event lock and retries on a later tick.
- Technical startup/shutdown recovery remains the full rollback path for every unresolved row.

## Deliberate boundary

The production `TerrainMutationPolicy` remains disabled. Block-drop capture and physical escrow
display/pickup, Tile NBT, hopper/container/death protection, protected-region validation, and
role-specific terrain AI remain future work. Enemy break/place behavior must not be enabled until
the drop escrow lifecycle is connected to the same normal-terminal and technical-recovery
boundaries.
