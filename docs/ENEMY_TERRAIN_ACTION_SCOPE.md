# PR #5: guarded single enemy terrain action

This milestone adds the first one-block `EntityChangeBlockEvent` path without enabling it in the
production plugin. It makes the safety decision and ledger call explicit before broader terrain AI
is allowed to change a survival world.

## Included

- A Paper-independent `TerrainMutationPolicy` with code-owned mandatory protection for cores,
  inventory-like blocks, beds, redstone machinery, portals, administrative blocks, and dangerous
  materials.
- A Paper handler that validates the tagged enemy, active combat area, target BlockData, and
  protected-block decision before cancelling the vanilla event and calling the PR4 adapter.
- Per-coordinate durable generation calculation so repeated changes can be rolled back in reverse
  order without relying on an in-memory counter.
- Destruction is classified as `EVENT_BLOCK`; placement is classified as `TEMPORARY_BLOCK` for the
  later normal-end cleanup path.
- Unit coverage for the mandatory protection set, disabled-by-default behavior, and generation
  sequencing.

## Deliberate boundary

The production plugin constructs this handler with the policy disabled. PR5 does not enable enemy
break/place behavior because normal-end settlement of `EVENT_BLOCK` and `TEMPORARY_BLOCK` rows is
not yet connected to the event finish transaction. Existing `EntityChangeBlockEvent` handling
therefore continues to cancel tagged enemy actions.

Tile-container NBT, block drops, escrow display entities, hopper/container/death protection,
protected-region validation, role-specific pathing, and normal-end terrain settlement remain future
work. A future activation must keep the mandatory policy and add those lifecycle boundaries first.
