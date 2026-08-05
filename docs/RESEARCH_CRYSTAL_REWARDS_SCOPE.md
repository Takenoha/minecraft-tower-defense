# Research crystal rewards scope

This slice connects the already durable victory progression and reward queue to a team-bound
research-crystal item and the core management screen.

## Included

- Schema v18 stores one crystal issuance batch per event, source team, stage, issued quantity,
  redeemed quantity, and `ISSUED/EXHAUSTED/VOIDED` state.
- Victory creates a deterministic synthetic escrow drop and one TEAM reward-queue row. Defeat,
  abort, and technical recovery never issue research crystals, even when the terminal operation is
  retried.
- Default reward policy is 100 crystals per first clear of stage 1 (scaling by stage), 25% for
  the current best level or either of the two immediately lower levels, and 0% for older replays.
  The values are configuration-backed under `rewards`.
- Delivered crystals carry PDC version, batch UUID, source-team UUID, and issued quantity. The
  payload is created only by the reward delivery bridge; a renamed vanilla amethyst shard is not
  accepted.
- Core GUI deposit reserves a two-phase redemption operation before removing the held item,
  validates source-team/core/member ownership, credits team research points in the same database
  transaction, and retries idempotently by operation UUID.

## Deliberate follow-up

- Reward catalog entries for ordinary enemy drops, battle funds, and boss-specific drop accounting
  remain separate from the stage-crystal boundary.
- Inventory crash recovery for a prepared physical deposit still requires the broader item receipt
  reconciliation milestone; a failed apply rolls back the reservation and returns the tagged item.
- Player-facing research purchase controls and individual tower upgrades use the existing research
  tables in a later slice.

## Verification

- `DefenseEventPersistenceTest` covers victory-only issuance, source-team binding, PDC payload
  quantity authority at the queue boundary, prepared redemption, and idempotent apply.
- Existing escrow queue tests assert that ordinary block rewards remain independently claimable
  alongside the new crystal queue row.
- Full Gradle test/build remains the acceptance command.
