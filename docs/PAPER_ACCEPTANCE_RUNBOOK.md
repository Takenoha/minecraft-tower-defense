---
title: "Paper Acceptance Runbook"
tags: [paper, minecraft, acceptance, terrain, recovery]
status: active
created: 2026-08-05
---

# Paper acceptance runbook

This runbook is the manual boundary for the code paths that cannot be proven by the
Paper-independent Gradle suite. It is written for a disposable Paper 26.2 build 87 / Java 25
server with a copied world and database. Do not run the terrain activation steps on a survival
server until the evidence template at the end has been completed and reviewed.

## 1. Install and baseline

1. Build the exact commit under review:

   ```text
   .\gradlew.bat clean test build --rerun-tasks --no-daemon
   ```

2. Record the commit SHA, Paper jar SHA-256, Java version, plugin jar SHA-256, server seed, and
   database backup path. Start with the checked-in terrain gate unchanged:

   ```yaml
   terrain-mutation:
     requested: false
     paper-integration-verified: false
     recovery-verified: false
   ```

3. Confirm startup succeeds, `/td admin status` is available to an operator, and the startup log
   reports terrain mutation as disabled. Register a test core with `/td admin core` while looking at
   a solid Overworld block.

## 2. Stage and role smoke test

1. Use `/td admin simulate 1` for a fast administrator-only lifecycle smoke. Confirm countdown,
   preparation, waves, intermissions, final boss naming, victory/defeat, and the event lock release.
2. Craft the core with one `DIAMOND_BLOCK` and four `IRON_INGOT` items. Confirm the old diamond-8 /
   `NETHER_STAR` recipe no longer matches.
3. Craft stage seals 1–10 one at a time. The first ten stage ingredients are, in order,
   `GOLD_INGOT`, `DIAMOND`, `EMERALD`, `AMETHYST_SHARD`, `PRISMARINE_CRYSTALS`, `QUARTZ`,
   `GLOWSTONE_DUST`, `REDSTONE`, `LAPIS_LAZULI`, and `NETHERITE_SCRAP`; each recipe uses four
   `PAPER` items and produces an `ECHO_SHARD` seal without a center `NETHER_STAR`.
4. If a database-owned legacy `ENDER_EYE` seal is available, confirm it retains its UUID/stage,
   is usable on a registered core, and is converted to `ECHO_SHARD` on owner login. Confirm both
   legacy and new seals are cancelled on air/ordinary-block right-clicks from either hand and as
   crafting inputs. Put a plugin core/seal recipe and a seal-shaped input into a Crafter and
   confirm the automated craft is cancelled; no UUID-less core/seal may be emitted.
5. Start stage 2 from the core GUI or by right-clicking a stage-2 seal. Confirm the GUI selects the
   requested stage, the seal is reserved/consumed once, the stage has eight waves, and the status
   line reports the selected stage. Repeat once with stage 3 or 10 to prove the progression path.
6. During a wave, identify role names `防衛戦破壊兵`, `防衛戦建築兵`, and the intermediate/final
   boss names. Confirm Destroyers are Husks, Builders are Zombie Villagers, and normal/boss roles
   are Zombies. Confirm all four event roles glow, while a natural zombie/husk/zombie villager
   spawned outside the event remains non-glowing. Remove the glow from one event entity and force
   the reconciliation/requeue path; confirm the replacement or reconciled entity is glowing again.
   Confirm the same for a replacement spawned after an out-of-range spawn attempt. After each of
   victory, defeat, operator abort, and technical recovery, inspect every loaded world and confirm
   no event-tagged glowing Mob remains. Confirm every variant can be damaged only by a registered
   participant or team tower and drops no vanilla loot.

## 3. Tower attack and destruction

1. Place at least one tower in the combat area and record its tower ID, type, level, and HP from its
   management GUI.
2. Let a Destroyer enter the configured `enemies.tower-attack-range`. Confirm its damage is applied
   at the configured interval, survives a `/td admin status` refresh, and is reflected after a
   restart of the test server.
3. Allow the tower to reach zero HP. Confirm the ArmorStand is removed only after the persistence
   mutation succeeds, the tower item is not dropped, and its individual level/investment row is
   gone. Confirm another tower remains unaffected.
4. Repair a different damaged tower during preparation/intermission and confirm the team funds and
   repair operation are consumed once. Attempt the same action during `WAVE_ACTIVE` and confirm it
   is rejected without a spend.

## 4. Team-management follow-up

With two test accounts (one may be offline between steps):

1. Run `/td team rename <name>` as the owner. Reopen the core/team GUI and confirm the name remains
   after a restart. Verify blank, control-character, and over-32-code-point names are rejected.
2. Run `/td team invite <player>` while the target is offline. On the target account run
   `/td team invites`, then `/td team accept <8-char-code>`. Confirm both accounts see the updated
   membership. Repeat with `decline` and with an expired invitation.
3. Add members until eight total members are present. Confirm the ninth add and ninth invitation
   are rejected in the database boundary, including when two requests are issued close together.
4. Run `/td team chat <message>` and confirm only online team members receive the message. Confirm
   ordinary server chat is unchanged. Attempt rename/invite/accept during a defense and confirm the
   active-event lock rejects the mutation.

## 5. Terrain action and recovery gate

The three gate inputs are an operator attestation, not test switches. Keep them false until the
following evidence exists:

1. With all flags false, present a breakable obstacle and a buildable support location to a stalled
   Destroyer and Builder. Confirm the path metrics increase but the block event remains cancelled
   and no world block changes.
2. On a disposable copy, set all three inputs true only for the reviewed test profile. Confirm a
   Destroyer can break one permitted obstacle and a Builder can place one permitted support block.
   Confirm stale-before-state, combat-area, WorldGuard, core, tower, inventory, portal, redstone,
   and tile-entity protections still reject unsafe targets.
3. Stop the server during each of `PREPARED`, `APPLIED`, and normal terminal settlement. Restart
   and verify the WAL generation order, conflict preservation of later player edits, escrow/drop
   settlement, and event-lock recovery. Re-run the same operation UUID and confirm it does not
   duplicate a block action.
4. Restore the gate flags to false after the test unless the evidence is explicitly reviewed for
   the target server profile. Never commit live-server attestation values to the repository's
   default configuration.

## 6. Resource vault, legacy payment, and receipt stop boundaries

1. Complete a victory with at least one picked-up defense drop and one unpicked drop. Confirm the
   pickup plays one experience-orb sound and shows the added quantity plus the player's cumulative
   provisional amount in the Action Bar. Start a countdown while the pickup message is visible;
   the pickup must remain visible for at least 40 ticks, and repeated pickups for the same event
   must coalesce instead of cancelling the first notice.
2. Open the resource-vault GUI during preparation and intermission. Confirm settled wallet points
   are spendable in those phases, while provisional points remain unavailable until terminal
   settlement. Confirm the displayed wording says that provisional points are locked until the
   event ends. Repeat a claim retry and verify no second sound or duplicate wallet credit.
3. On a copy of a v26 database, remove one or both `team_resource_balances` rows and restart the
   plugin. Confirm the rows are backfilled at zero without changing team progress. Run the legacy
   reward-queue migration and verify operationless `PENDING` rows become wallet credit exactly
   once, while delivery `PREPARED` and `DELIVERED` rows remain physical-delivery rows.
4. Set `rewards.legacy-resource-payments-enabled: true`, spend a legacy defense-shard and
   vanilla-material quote for a core repair, and spend both legacy materials for a tower upgrade.
   Confirm the warning identifies the path as deprecated and each receipt is consumed once. Set
   the flag false and confirm the same insufficient-wallet operation is rejected without touching
   inventory. Verify a team with either non-zero wallet balance cannot disband or let its sole
   owner leave.
5. During core repair and legacy tower upgrade, stop the server or disconnect the player at each
   prepare, receipt-reserve, receipt-secure, apply, and physical-clear step. On restart/login,
   confirm `RESERVED`/`SECURED` operations reconcile from tagged stacks, `CLEAR_PENDING` removes
   any remaining tagged stacks and clears once, and no ordinary surplus material is guessed,
   minted, or removed. A logout before physical receipt handoff must roll back durably; a logout
   after handoff must defer reconciliation to the saved inventory on join.
6. Attempt to move or use a tagged receipt through normal click, number-key hotbar swap,
   off-hand swap, drag, pickup, drop, place, dispense, craft, Crafter, item-frame, and every
   entity-interaction route. Every route must be cancelled. Repeat with ordinary untagged items
   and confirm they remain usable. After a receipt-bearing team is restored, verify wallet credit,
   receipt state, and physical stacks are unchanged by a restart.

## Evidence record

```text
Commit:
Paper build / jar SHA-256:
Java version:
Plugin jar SHA-256:
World / database backup:
Tester and date:

Stage 2+ start and wave evidence:
Role-specific mob evidence:
Tower damage / destruction evidence:
Team invitation / name / limit / chat evidence:
Terrain-disabled evidence:
Terrain-enabled permitted-action evidence:
Prepared/applied/terminal recovery evidence:
Resource vault / legacy payment / receipt recovery evidence:
Action Bar pickup priority and 40-tick evidence:
Later-player-edit conflict evidence:
Event IDs and operation UUIDs:
Reviewed by:
Gate decision (remain false / approved for this profile):
```
