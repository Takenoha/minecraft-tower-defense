---
title: "Tower Foundation Scope"
tags: [minecraft, tower, foundation]
status: active
created: 2026-08-04
---

# Tower foundation scope

This document records the common durable tower boundary and the first usable tower type: Arrow.
The later Cannon extension is documented separately so the remaining balance and research
decisions do not get hidden in an incomplete seven-type implementation.

## Included

- A unique, PDC-tagged Arrow tower item. The provisional recipe is `IRI / RDR / IRI`, where
  `I` is an iron ingot, `R` is redstone, and `D` is a diamond. Shift-click crafting is rejected.
- Placement on an adjacent air block above a normal solid block inside the installing team's core
  combat area. Placement is persisted with a `PREPARED` to `APPLIED` operation and a schema-v13
  tower row before the item is removed.
- A persistent, PDC-tagged ArmorStand representation. Unknown or mismatched tower entities are
  removed; a missing known entity is left fail-closed rather than silently recreated.
- Default team capacity `8 + 2 * highestClearedLevel`, bounded by `40`. Capacity is checked in
  the same SQLite transaction as placement.
- Automatic line-of-sight Arrow attacks every 20 ticks, with range 16 and damage 4. Targets are
  hostile `Monster` entities, sorted by distance to the team's core and then tower distance.
  Event enemies are accepted only when the active event belongs to the tower's team.
- Damage from a tower marks a natural hostile mob for the remainder of its life. A marked mob
  drops no items and no experience when it dies.
- Tower placement is rejected during COUNTDOWN and WAVE_ACTIVE. The persistence boundary permits
  the owning team's PREPARATION and INTERMISSION windows; no other team's event can be changed.
- Player damage, explosions, pistons, block changes, and mob targeting cannot move or destroy the
  physical tower representation.

## Deliberate follow-up slices

The five other initial types (Frost, Lightning, Support, Sniper, Flame), upgrades, research
gates, enemy destruction, economy/rewards, and full manual multi-player verification are not part
of the foundation slice. Target-priority GUI and tower removal/move are covered by the retrieval
slice, while Cannon is covered by `TOWER_CANNON_SCOPE.md`. The item recipes and tower balance
values are configuration-backed.

The three terrain-mutation flags remain `false`; this slice performs no world block mutation.
