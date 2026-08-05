---
title: "Team Management Follow-up Scope"
tags: [team, invitations, chat, persistence]
status: active
created: 2026-08-05
---

# Team management follow-up scope

This follow-up closes the previously deliberate team-management boundaries without weakening the
SQLite authorization boundary. The repository remains authoritative for team names, the member
limit, invitation state, active-event rejection, and UUID-idempotent mutations.

## Included

- A `display_name` column and owner-only rename operation, limited to one non-control line of at
  most 32 Unicode code points.
- A hard maximum of eight members enforced inside the same transaction as member insertion.
- Seven-day invitation records that survive both players being offline. Expired invitations are
  settled when listed or accepted; acceptance and decline require the addressed player and reject
  active defense events.
- `/td team invite <player>`, `invites`, `accept <code>`, `decline <code>`,
  `rename <name>`, and `chat <message>` command paths. Team chat is an explicit command and does
  not intercept ordinary server chat.
- Schema migration 26 and persistence tests for restart, expiry, idempotency, authorization, and
  the member limit.

## Deliberate boundary

The nearby-player GUI invite remains a convenience path; arbitrary-player selection is handled by
the command so a GUI does not need chat input. Live Paper inventory and command interaction, player
name resolution on a real server, and client presentation are verification tasks rather than
automated test claims.
