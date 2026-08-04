---
title: "Tower Retrieval Scope"
tags: [minecraft, tower, retrieval, persistence]
status: active
created: 2026-08-04
---

# Tower retrieval scope

This slice adds the first player-facing management operation for the Arrow tower foundation:
right-clicking a registered tower opens a GUI, and the GUI returns the same unique tower item so
the player can place it at another valid location.

## Included

- A member-only tower-management GUI with tower identity, individual level, coordinates, and a
  retrieval/move action.
- Retrieval is allowed only when no defense event is active. Once an event has started, the GUI
  remains view-only and the persistence boundary rejects the operation as well.
- The returned item keeps the tower UUID, type, and individual level. Existing placement checks
  continue to enforce team membership, core combat area, world border, capacity, and duplicate
  UUID protection.
- The management GUI exposes core-nearest, nearest, high-health, low-health, and boss-first target
  priorities. The selected priority is persisted with the tower and preserved by retrieval.
- `tower_removal_operations` schema version 14 with `PREPARED`, `APPLIED`, and `ROLLED_BACK`
  states. The item is secured before the durable tower row is deleted; retries use the operation
  UUID and cannot delete a second tower.
- Startup recovery removes stale physical entities from applied removals. Prepared removals are
  rolled back and their temporary returned item is removed from online inventories and drops.
- The existing physical protection listeners remain in force. Terrain mutation flags stay false.

## Deliberate follow-up slices

- Direct atomic move confirmation, upgrades, research, HP/repair, and the six remaining tower types
  are outside this slice.
- This environment still lacks a Minecraft client bot, so manual two-player GUI verification is
  not included in automated validation.
