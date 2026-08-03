# Public core physical-placement boundary

This milestone connects the durable team/core foundation to one safe public Paper interaction:
craft a unique core item and use it to replace one validated ordinary block with a tagged beacon.
The terrain-mutation activation flags remain unchanged and disabled; this is a protected core
placement path, not enemy terrain mutation.

## Included

- A shaped recipe using eight diamonds and one nether star.
- A unique item UUID and versioned plugin-owned PDC on every crafted core item.
- A fail-closed right-click interaction that only accepts a solid, non-tile Overworld block.
- Existing combat-area safety checks, forbidden-world/rectangle checks, WorldBorder checks, and
  optional WorldGuard checks before the physical replacement.
- Team ownership enforcement: a player without a team gets a deterministic solo team, while an
  existing team can be placed only by its owner.
- Rejection while any defense event is active, both before and after the asynchronous persistence
  boundary.
- A durable `PREPARED` → physical block replacement/tag → `APPLIED` ledger in schema v11.
- Startup recovery that restores a tagged prepared block before marking the operation rolled back.
- Idempotent database apply/rollback and inventory/entity reconciliation so the consumed item cannot
  be duplicated after a successful apply.
- Rebuilding a persisted zero-health core at a new validated position while retaining its core UUID.

## Deliberate boundary

This slice does not yet provide bound-core itemization, GUI-confirmed relocation, repair-cost
economy, team invitations/member GUI, towers, research, start-item reservation, or enemy terrain
mutation. Bound items are rejected with an explicit message until the relocation/itemization flow
has its own physical handoff and recovery ledger. The administrator `/td admin core` command remains
available for the existing test flow.

The recipe values and item presentation are an initial implementation choice, not the final game
economy balance. Core placement still requires the existing minimum core distance and combat-area
clearance rules. The three terrain-mutation flags remain `false` by default and are not changed by
this milestone.

## Verification

The persistence tests cover prepared/apply idempotency, startup-readable applied identities,
physical-stop-window rollback, owner and active-event authorization, and destroyed-core rebuild.
The full Gradle test suite and a Paper 26.2 build 87 / Java 25 startup smoke test are required
before publishing a build from this branch.
