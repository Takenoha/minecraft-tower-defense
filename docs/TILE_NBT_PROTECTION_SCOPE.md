# PR #9: tile payload and protected-block synchronization

This milestone extends the block WAL so recovery compares and restores the mutable tile data
available through the Paper API, and adds a main-thread listener for indirect changes to protected
targets.

## Included

- Schema v9 stores `before_tile_nbt` and `expected_after_tile_nbt` alongside every block change.
- The block snapshot and rollback planner include the tile payload in equality and conflict
  decisions, so a later inventory or tile edit cannot be overwritten during recovery.
- `PaperTileNbtCodec` stores a versioned Paper API projection containing persistent-data bytes,
  snapshot inventory bytes, lock state, and custom name. The payload is applied to a mutable tile
  snapshot and acknowledged with `BlockState.update` on the Paper main thread.
- Tile states are mandatory protected targets for enemy terrain actions, even when their material
  is not in the required-material suffix list.
- Protected cores and protected TileState/material targets in an active combat area are shielded
  from break/place, piston, explosion, fluid, growth/fade/burn/ignite, physics, and entity-change
  paths.

## Deliberate boundary

Paper's public API does not expose the server's complete raw vanilla NBT compound. The stored
payload is therefore the stable API projection above, not an internal CraftBukkit/NMS object. Raw
NMS access, role-specific pathing, and production activation of `TerrainMutationPolicy` remain
future work. PR #10 adds the explicit configured deny-list and WorldBorder start/placement gate;
third-party region-plugin integration remains out of scope. See
`docs/PROTECTION_BOUNDARIES_SCOPE.md`.

## Verification

The persistence suite verifies schema v9 columns, durable tile payloads, and tile-sensitive
rollback conflicts. The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
