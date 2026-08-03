# PR #3: core and team persistence foundation

This milestone makes team ownership and core lifecycle mutations durable and safe to retry. It is
the persistence boundary for the next Paper-facing team GUI and core interaction work.

## Included

- SQLite schema migration 4 with operation UUIDs and payload fingerprints for team/core mutations.
- Team lookup by member, owner-authorized member add/remove, ownership transfer, leave, and
  disband operations.
- Idle-state enforcement: membership, ownership, repair, relocation, and rebuild mutations are
  rejected while the server-global defense lock is held.
- Actor membership checks for core placement, repair, relocation, and destroyed-core rebuild.
- Core repair, full-health relocation, and destroyed-core rebuild with same-world distance checks.
- Idempotent retries for every new mutation and persistence across database reopen.
- Main-thread core registry support for replacing or unregistering destroyed core entries.

## Deliberate boundary

The existing administrator command still registers a solid Overworld block as a test core. Public
core crafting, item identity, GUI confirmation, team invitations, member-facing commands, repair
costs, tower ownership checks, world-border/protected-region validation, and physical block
replacement are not enabled yet. A destroyed core is represented by its durable row at zero HP;
rebuilding updates that row after the future Paper adapter has validated the replacement block.

The next milestone should connect these repositories to main-thread team/core commands or GUI,
validate world borders and protected regions before placement, reconcile the physical core block,
and wire the registry replacement after event defeat or rebuild. Then implement the real Mob/terrain
adapter using the PR #2 ledger before enabling enemy block changes.
