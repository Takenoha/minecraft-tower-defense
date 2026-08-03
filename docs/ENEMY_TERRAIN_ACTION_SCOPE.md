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

Reward-queue delivery, protected-region validation, and role-specific pathing remain future work.
PR #9 adds the Tile NBT API projection and protected-target synchronization. PR6 adds normal-end
terrain settlement and PR7 adds held block-drop capture,
tagged display entities, participant claims, and physical transfer protection. A future activation
must keep the mandatory policy and add the remaining lifecycle boundaries first.

PR6 adds normal terminal terrain settlement in `docs/TERRAIN_SETTLEMENT_SCOPE.md`, and PR7 adds
block-drop escrow plus its physical-item protection lifecycle. The action policy remains disabled
until reward delivery, protected-region validation, and role-specific AI are connected. The PR9
protection and tile-recovery boundary is present, but the production terrain policy remains
disabled.
