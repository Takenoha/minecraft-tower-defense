# Raid seal start-item scope

This slice connects the existing SQLite raid-seal escrow to the Paper player-facing start path.
The database remains authoritative when the physical inventory cannot be part of the same ACID
transaction.

## Included

- A versioned, non-stackable `ENDER_EYE` with `raid_seal_id` and stage-level PDC values.
- A stage-1 recipe using eight `GOLD_INGOT` and one `NETHER_STAR`.
- Craft-time UUID issuance and asynchronous `AVAILABLE` registration in `raid_seals`.
- Stage-1 start from the core GUI or by right-clicking a registered core with the item.
- A two-step start boundary: SQLite event/lock plus seal `RESERVED`, main-thread physical removal,
  then seal `CONSUMED`.
- Validation and global-lock failures leave the seal untouched.
- Technical recovery keeps the original UUID unusable and exposes the fresh returned UUID through
  the existing refund table; the join reconciliation materializes that returned item once.
- Invalid, consumed, refunded, duplicated, or unregistered physical tokens are removed during
  owner reconciliation and after successful consumption.

## Deliberate boundary

Only stage 1 is selectable in this walking skeleton. Stage-specific recipes, the full stage
selector, research crystals, and tower placement/upgrades are later slices. The first team
management GUI is documented in `docs/TEAM_MANAGEMENT_GUI_SCOPE.md`. Terrain mutation remains
fail-closed and its three activation flags are unchanged.

## Verification

Persistence tests cover the reserved-start physical-removal boundary, idempotent consume, and
fresh-UUID technical refunds. The full Gradle test suite and Paper startup smoke are required
before publishing a build from this branch.
