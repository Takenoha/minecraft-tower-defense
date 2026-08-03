# PR #7: physical block-drop escrow

This milestone connects ordinary `EVENT_BLOCK` destruction to the existing database-owned escrow
without enabling enemy terrain mutation in production.

## Included

- Captures the no-tool Paper block drops before the WAL-backed block apply and stores the complete
  untagged `ItemStack` payload in a held escrow row.
- Uses stable event/drop identity and restores an already-prepared payload on a retry, so a second
  physical display cannot create a second reward.
- Spawns a PDC-tagged Item entity only after the block apply has been acknowledged. The entity is a
  display only: pickup is cancelled and a registered participant claim is recorded asynchronously
  in SQLite.
- Blocks inventory pickup/move/click/drag, player dropping, crafting, placement, dispensing, item
  frames, merging, despawning, damage, portals, and death-inventory paths for tagged escrow items.
- Removes physical displays at normal terminal settlement and technical recovery. Terminal escrow
  settlement clears the display reference, while recovery voids held drops and clears the same
  reference in its transaction.

## Deliberate boundary

`TerrainMutationPolicy` is still constructed disabled in the production plugin. Reward-queue
delivery into player inventories, offline delivery, Tile NBT capture, protected-region validation,
and role-specific terrain AI remain future work. The stored reward payload is never released from
the queue by this milestone.
