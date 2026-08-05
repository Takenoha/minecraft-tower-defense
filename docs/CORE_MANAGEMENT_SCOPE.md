# Core management and team GUI boundary

This slice builds on the public core placement ledger and adds the first player-facing team
management flow. It keeps all state changes behind the existing global defense lock, team
membership checks, and idempotent operation UUIDs.

## Included

- Schema v12 `team_progress` snapshots with a default first-stage unlock for existing and newly
  created teams.
- Configurable repair material and repair economics. A repair quote rounds missing HP into repair
  units and scales vanilla-material and `防衛の欠片` costs by the team's highest cleared level.
- Versioned, plugin-owned `防衛の欠片` PDC items for future admin/reward delivery paths.
- A core-management GUI available to team members by empty-hand right-clicking their registered
  beacon. It displays team membership, HP, progression, repair quote, and relocation controls.
- Atomic repair through `DefenseRepository.repairCore`, including main-thread inventory removal,
  asynchronous persistence, and material refund on a persistence failure.
- Bound core item identity with team/core UUIDs and a GUI-confirmed relocation path for full-health
  cores. Members may relocate the core outside active defense; the source beacon, target block,
  registry, durable position, and prepared-operation recovery are coordinated as one stop-window.
- Idempotent relocation apply/rollback and startup recovery that remains `PREPARED` when either
  physical location has an unknown block state.
- Stage-1 `襲撃の印` start-item PDC, craft registration, core GUI/right-click start path, and the
  reserved → physical removal → consumed boundary are implemented in the follow-on
  `docs/RAID_SEAL_START_ITEM_SCOPE.md` slice.
- The first team-management GUI slice is implemented in the follow-on
  `docs/TEAM_MANAGEMENT_GUI_SCOPE.md` scope.

## Deliberate boundary

This slice does not implement tower placement/upgrades or research purchasing/crystal delivery.
The team-management follow-up now supplies offline invitation records, team naming, chat controls,
and arbitrary-player selection through commands rather than this first GUI.
Enemy terrain mutation remains fail-closed and its three activation flags are unchanged.

## Verification

Domain tests cover repair rounding and progression defaults. Persistence tests cover v12 defaults,
member-authorized same-UUID relocation, idempotent apply, repair, and existing placement recovery
boundaries. The full Gradle test suite and a Paper 26.2 build 87 / Java 25 startup smoke test are
required before publishing a build from this branch.
