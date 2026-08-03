# PR #11 TEAM reward retention and owner fallback boundary

PR #11 closes the abandoned TEAM reward handoff gap without changing the durable delivery receipt
or the existing escrow settlement boundary.

## Included

- Schema v10 adds `event_reward_queue.team_claim_deadline` and an index for pending TEAM rows.
- The `rewards.team-queue-retention-seconds` setting defaults to seven days and is captured in the
  validated plugin settings snapshot used by each defense repository.
- Victory-created TEAM rows store `settled_at + retention` as their deadline. Personal rows keep no
  TEAM deadline, and migrated legacy rows remain participant-only because their deadline is null.
- Registered participants can still claim a TEAM row before and after the deadline.
- After the deadline, the current owner recorded in `teams.owner_player_id` can discover, reserve,
  and deliver the row even when that owner was not registered for the event.
- The existing durable one-operation reservation and PDC receipt remain authoritative. If another
  player already owns an unfinished handoff, the new owner receives `HELD_BY_OTHER` rather than a
  second inventory insertion.

## Deliberately not included

- Automatic expiry or deletion of queue rows.
- Transfer of ownership by the reward-delivery path; ordinary team ownership mutations remain
  separate and auditable.
- Custom reward catalogs, research progression, towers, GUI flows, role-specific terrain AI, or
  production activation of enemy terrain mutation.

## Verification

The persistence tests cover deadline persistence, pre-deadline exclusion, current-owner fallback,
non-owner rejection, and idempotent delivery at the exact deadline. Configuration tests cover the
default, custom, and invalid retention values. The full Gradle clean/test/build command is the
acceptance command for this boundary.
