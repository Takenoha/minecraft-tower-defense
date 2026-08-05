---
title: "Tower Research Progression Scope"
tags: [minecraft, tower, research, persistence]
status: active
created: 2026-08-04
---

# Tower research progression scope

This slice establishes the durable progression boundary required before individual tower
upgrades. It keeps stage progression and per-type research caps in SQLite so a later Paper GUI
or crystal-delivery flow can use the same source of truth.

## Included

- Schema version 17 adds one level-one research row for every existing and newly created team and
  each currently supported tower type (`arrow`, `cannon`).
- A victory advances `highest_cleared_level` and the next `unlocked_level` inside the same
  transaction that persists the terminal event. Defeat, abort, and technical recovery leave team
  progression unchanged.
- A UUID-idempotent research purchase spends team-shared research points for exactly one level of
  one tower type. It requires a current team member, rejects an active defense event, checks the
  available point balance, and stores a payload fingerprint for retry/conflict detection.
- Tower placement and its `PREPARED -> APPLIED` retry path both enforce the persisted per-type
  research cap. Level-one placement remains compatible with the initial level-one rows.

## Deliberate follow-up slices

- Research crystal PDC identity, source-team/batch metadata, boss/victory queue issuance, and core
  delivery/redeem UI remain separate. This slice does not mint or award research points.
- Battle funds, event reward catalogs, replay ratios, individual upgrade costs, and player-facing
  research purchase GUI remain separate balance/integration slices.
- The purchase API accepts the selected cost from the future balance/configuration layer; it does
  not hard-code a cost policy into persisted research state.

## Verification

Domain tests cover monotonic victory progression. Persistence tests cover schema creation, initial
research rows, idempotent research purchase, team-point deduction, and placement rejection above
the research cap. The full Gradle test/build command remains the acceptance command.
