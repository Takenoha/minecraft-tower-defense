---
title: "Team Management GUI Scope"
tags: [team, gui, paper]
status: active
created: 2026-08-04
---

# Team management GUI scope

This slice connects the existing durable team membership operations to the player-facing core
management flow. The SQLite repository remains authoritative for authorization, the global defense
lock, and UUID-idempotent mutations.

## Included

- A team screen opened from the team row of the core-management GUI.
- Member heads with role and self-identification, plus a confirmation screen for mutations.
- Owner-only invitation of the single nearby online player within six blocks. The invite is
  accepted by the owner click and the invited player receives an in-game notification.
- Owner-only removal of a non-owner member and transfer of ownership to an existing member.
- Member self-service leave, with the repository enforcing the owner-transfer and persisted-core
  constraints.
- Durable team display names with owner-only UUID-idempotent rename operations.
- A repository-enforced eight-member limit, including invitation acceptance and direct member adds.
- Offline invitation records with a seven-day expiry, reconnect-time listing, accept, decline, and
  UUID-idempotent state transitions.
- Player commands for offline invites, invitation decisions, team naming, and team chat:
  `/td team invite`, `invites`, `accept`, `decline`, `rename`, and `chat`.
- Inventory click and drag cancellation so GUI items cannot be moved into player inventories.
- Asynchronous persistence with a per-player in-flight guard, success refresh, and failure message.

## Deliberate boundary

The GUI invite button remains intentionally limited to one nearby online player. Offline invites and
chat-input naming are exposed as commands so the GUI does not guess a player name or consume chat
input. This scope still does not add tower placement, research purchasing, stage selection beyond
the initial stage-1-through-10 catalog, or terrain mutation.

## Verification

The persistence lifecycle tests cover authorization, idempotency, ownership transfer, removal,
leave constraints, active-event rejection, team naming, invitation expiry/decision recovery, and
the member limit. The implementation must pass the full Gradle test suite and a Paper 26.2 build 87
/ Java 25 startup smoke. Manual inventory clicks and live command interaction are not available in
this environment and remain verification items.
