# Raid seal start-item scope

This slice connects the existing SQLite raid-seal escrow to the Paper player-facing start path.
The database remains authoritative when the physical inventory cannot be part of the same ACID
transaction.

## Included

- A versioned, non-stackable `ECHO_SHARD` with `raid_seal_id` and stage-level PDC values.
- Stage-specific recipes for stages 1 through 10. Each recipe uses four `PAPER` items and one
  copy of its stage's vanilla material; no `NETHER_STAR` is required.
- Existing valid `ENDER_EYE` seals remain readable with the same UUID and stage. Player-login
  reconciliation converts database-owned `AVAILABLE` legacy items to `ECHO_SHARD`; legacy items
  outside an inventory remain usable until reconciliation.
- Craft-time UUID issuance and asynchronous `AVAILABLE` registration in `raid_seals`.
- Stage 1 through 10 start from compact core-GUI buttons or by right-clicking a registered core
  with the matching item. The start path accepts every valid stage level, so later recipe catalogs
  can extend the same PDC and escrow contract without a schema change.
- A two-step start boundary: SQLite event/lock plus seal `RESERVED`, main-thread physical removal,
  then seal `CONSUMED`.
- Validation and global-lock failures leave the seal untouched.
- Technical recovery keeps the original UUID unusable and exposes the fresh returned UUID through
  the existing refund table; the join reconciliation materializes that returned item once.
- Invalid, consumed, refunded, duplicated, or unregistered physical tokens are removed during
  owner reconciliation and after successful consumption.
- Valid seals cancel every right-click action unless the click is a registered core start, and they
  cannot be used as vanilla crafting ingredients.

## Deliberate boundary

The initial vanilla recipe catalog and GUI expose stages 1 through 10. Stages above 10 already
work through the validated administrator simulation and the physical PDC/start contract, but do
not yet have a vanilla recipe. The first team-management GUI is documented in
`docs/TEAM_MANAGEMENT_GUI_SCOPE.md`. Terrain mutation remains fail-closed and its three
activation flags are unchanged.

## Verification

Persistence tests cover the reserved-start physical-removal boundary, idempotent consume, and
fresh-UUID technical refunds. The full Gradle test suite and Paper startup smoke are required
before publishing a build from this branch.
