---
title: "Remaining Tower Types and Battle Economy Scope"
tags: [minecraft, tower, battle-economy, upgrades, persistence]
status: active
created: 2026-08-05
---

# Remaining tower types and battle economy

This slice completes the initial seven tower identities and connects the player-facing tower
progression to the event-scoped battle economy. The durable UUID boundaries remain separate from
Paper's main-thread combat loop so a retry cannot duplicate a placement, purchase, repair, or
reward-side balance mutation.

## Included

- Adds Frost, Lightning, Support, Sniper, and Flame to the stable tower-type enum, item PDC,
  recipes, material validation, research rows, placement/removal checks, and SQLite type checks.
- Adds configurable specialist profiles. Frost applies damage plus Slowness, Lightning chains to
  nearby enemies, Support supplies capped same-team range/damage/speed stacks, Sniper is a
  long-range single-target tower, and Flame applies area damage plus fire ticks. Existing Arrow
  and Cannon behavior remains configurable and keeps its line-of-sight and team-enemy filters.
- Adds per-tower current/max HP persistence and a GUI repair action. Repair is an atomic,
  idempotent spend of team-shared event funds and is allowed only during preparation/intermission.
- Adds event-scoped Power, Speed, and Range battle boosts with configurable costs, multipliers,
  and stack limits. Boost purchases update the funds account, boost row, and both operation ledgers
  in one transaction; terminal finish/recovery clears active boost rows and the runtime cache.
- Adds the player-facing tower-management slots for battle boosts and repair while retaining
  individual-level and target-priority controls. Research caps continue to gate every tower type.
- Advances the schema to version 24: specialist type checks (21), boost state and operation
  ledgers (22), tower HP columns (23), and repair operation ledger (24).

## Operation boundaries

- Battle funds are event-scoped and team-shared. Enemy and wave rewards credit the account
  idempotently; terminal settlement returns the balance to zero.
- Boost and repair mutations require a team member, the owning team, an active event, and a
  preparation/intermission window. The persistence layer rechecks each condition inside its
  immediate transaction, so GUI timing cannot bypass the WAVE_ACTIVE restriction.
- Research purchases and individual upgrades remain outside an active event or inside the
  explicitly permitted preparation/intermission window, respectively. Individual levels and
  research caps persist after a defense ends; temporary boosts do not.

## Deliberate follow-up

- The current slice does not add an enemy-to-tower damage producer; the existing enemy runtime
  attacks the core, while the durable HP/repair boundary is ready for the later destroyer/tower
  combat integration. Enemy destruction and item-loss semantics therefore still need a Paper
  gameplay pass.
- Minecraft client GUI, recipe, visual, and live combat behavior require manual Paper-server
  verification; automated tests cover the Paper-independent settings and persistence boundaries.

## Verification

`./gradlew.bat test --no-daemon` passes all 178 tests on commit preparation. Persistence coverage
includes schema creation/migration, seven-type research initialization, battle-fund settlement,
idempotent boost purchase, idempotent repair, HP updates, and terminal boost cleanup.
