---
title: "Tower Cannon Scope"
tags: [minecraft, tower, cannon, combat]
status: active
created: 2026-08-04
---

# Cannon tower scope

This slice adds the second initial tower type, Cannon, on top of the shared Arrow tower
foundation. Cannon is intentionally a slower, high-damage area attacker so its type identity,
configuration, recipe, persistence, and combat behavior are all exercised before the economy and
research layers are introduced.

## Included

- A unique PDC-tagged Cannon item using a dispenser as its display material. The provisional
  recipe is `CGC / GIG / CGC`, where `C` is cobblestone, `G` is gunpowder, and `I` is an iron
  ingot. Crafting still rejects shift-click results and assigns a fresh tower UUID.
- Configuration-backed Cannon damage, range, attack interval, and splash radius. Defaults are
  damage `8`, range `14`, interval `40` ticks, and splash radius `2.5` blocks.
- Line-of-sight target selection using the existing target-priority choices. The selected hostile
  mob is the splash center; eligible hostile mobs in the radius receive Cannon damage. Event
  enemies are accepted only for the owning team's active event, and tower entities, players, and
  unrelated event enemies remain excluded.
- Schema version 16 widens the durable tower, placement-operation, and removal-operation type
  checks to include `cannon` while preserving unique IDs, target priority, and idempotent
  `PREPARED`/`APPLIED`/`ROLLED_BACK` recovery boundaries.
- Existing team capacity, core-area, world, protection, combat-phase, terrain-mutation, and
  tower-damage/drop rules remain shared with Arrow.

## Deliberate follow-up slices

- Frost, Lightning, Support, Sniper, and Flame remain separate tower-type slices.
- Economy/rewards and research gates should be implemented before individual tower upgrades so
  the upgrade cost and unlock rules have a durable source of truth.
- HP/repair, direct atomic move confirmation, and Minecraft-client manual GUI verification remain
  outside automated validation.
