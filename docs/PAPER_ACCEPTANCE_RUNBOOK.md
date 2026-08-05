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
2. Craft the core with one `DIAMOND_BLOCK` and four `IRON_INGOT` items. Confirm the result is the
   plugin-owned `RESIN_BRICKS` core item, and that the old diamond-8 / `NETHER_STAR` recipe no
   longer matches.
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
5. Place a newly crafted core item on a validated ordinary solid block. Confirm the placed block is
   `DRIED_KELP_BLOCK` (乾燥した昆布ブロック), not a beacon. A normal untagged `RESIN_BRICKS` item
   must not place a core. A normal untagged `NETHER_STAR` must not place a core either.
6. On a disposable copy of a world/database containing a registered legacy beacon core, restart
   the plugin and confirm only the exact DB-registered core coordinate changes from `BEACON` to
   `DRIED_KELP_BLOCK`. Repeat the restart and confirm no further mutation. Place ordinary beacons
   elsewhere and confirm they are untouched. Move, rebuild, and recover the core and verify every
   resulting registered coordinate uses `DRIED_KELP_BLOCK`; an unexpected ordinary block at a
   registered coordinate is logged and left intact rather than guessed over.
7. Hold an old plugin-owned PDC `NETHER_STAR` core item. Confirm it remains usable for placement,
   keeps its item/core/team UUIDs, and is removed exactly once after the DB apply. A normal
   PDC-less nether star remains an ordinary item.
8. During a wave, exercise `ARROW`, `CANNON`, `FROST`, `LIGHTNING`, `SNIPER`, and `FLAME` attacks.
   Confirm each has a visually distinct vanilla-particle trail and hit effect, and that cannon
   area targets and lightning chain targets each show a hit effect only when damage succeeds.
   Cancel or otherwise prevent a damage event and confirm no hit effect is emitted. Confirm the
   effects do not create entities, damage blocks, add extra damage, or change tower balance.
9. Place a `SUPPORT` tower inside support range of an attacking tower. Confirm the support pulse
   uses its buff effect and appears only while the configured support multiplier is applied. With
   many simultaneous attacks, confirm particle output stays bounded and the vanilla client needs
   no resource pack.
10. During `WAVE_ACTIVE`, let an enemy reduce core HP. Confirm the configured
    `core.warning-sound` (default `ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR`) is heard only by online
    players in the combat area. Repeated same-tick/near-tick hits must be debounced by
    `core.warning-min-interval-ticks`; players outside the area and the rest of the server must
    not hear it. Confirm mere proximity, a cancelled/non-damaging hit, victory/defeat cleanup,
    and startup/recovery do not play the warning.

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
   must coalesce instead of cancelling the first notice. Complete a claim during the terminal
   `ending` window and verify it is rendered before the event lock is released; after disconnecting
   for more than 40 ticks, reconnect and confirm an expired notice is not joined to a new pickup.
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
   after handoff must defer reconciliation to the saved inventory on join. Exercise both
   `keepInventory=true` and `keepInventory=false`; death must retain tagged receipts through the
   death keep-list, and respawn/restart must resolve `RETURN_PENDING`, `SECURED`, and
   `CLEAR_PENDING` exactly once.
6. Attempt to move or use a tagged receipt through normal click, number-key hotbar swap,
   off-hand swap, drag, pickup, drop, place, dispense, craft, Crafter, item-frame, and every
   entity-interaction route, including `PlayerInteractAtEntityEvent` and
   `PlayerArmorStandManipulateEvent`. Test both directions of number-key/off-hand swaps and both
   hands on ordinary entities, ArmorStands, and ItemFrames. Every tagged route must be cancelled;
   ordinary untagged items must remain usable.
7. Fill the storage inventory so a partial stack split has no compatible capacity, then attempt a
   core repair for each material and a tower upgrade requiring both materials. Confirm the
   operation is rejected before inventory or receipt mutation. Repeat with one safe compatible
   stack/slot and stop between the receipt replacement and remainder return; after restart, the
   remainder and receipt must reconcile without a ground drop or duplicate payment.
   After a receipt-bearing team is restored, verify wallet credit, receipt state, and physical
   stacks are unchanged by a restart.

## 7. Portable point vouchers

1. Outside a defense and with no prepared core placement, open the resource-vault GUI as the team
   owner. Confirm the defense-point buttons withdraw 10P, 100P, or the current full balance, and
   the enhancement-point buttons withdraw 1P, 10P, or the current full balance. Confirm a member
   can view the vault but cannot withdraw, and that withdrawal debits the wallet once even when
   the same click/operation is retried.
2. Confirm each withdrawal produces exactly one `PRISMARINE_CRYSTALS` item named
   `携帯ポイント証票`, amount 1 with max stack size 1, and lore matching the DB voucher. The
   delivery recipient must remain the original withdrawing owner. A full inventory must leave the
   voucher `PENDING_DELIVERY` for login/retry; it must not be dropped to the ground or silently
   reissued.
3. Stop the server or disconnect at each delivery boundary: delivery `PREPARED`, after the
   receipt item enters the inventory, after DB `AVAILABLE`, and before receipt stripping. On
   restart/login, confirm the same voucher UUID is reconciled at most once, a tagged delivery
   receipt is stripped only after DB apply, and an `AVAILABLE` voucher is never duplicated.
4. Hold a voucher from the same team and click the matching resource row in the vault. Confirm a
   team member (not only the owner) can start a deposit, while another team's voucher, a forged
   PDC quantity, a missing voucher row, an active defense, or a prepared core placement is
   rejected. Stop at `RESERVED`, after redeem receipt tagging, after wallet credit, and before
   physical removal. Quit while `prepareRedeem` is queued or committing, then rejoin immediately.
   Because `DatabaseExecutor` is single-threaded, the quit-held source binding must cancel click,
   held-slot, drop, and off-hand swaps until join reconciliation completes; the matching operation
   must resume exactly once without requiring a second join. Reconnect after the
   receipt-tagged/DB-ACK-before-removal boundary and confirm the matching redeem operation credits
   exactly once, reaches `REDEEMED`, removes every physical copy, and releases the player's pending
   hold for a subsequent voucher operation. A missing physical item must remain an auditable hold
   and must not mint points.
5. Duplicate a voucher item in a disposable test inventory and attempt two deposits. Confirm only
   the first valid copy can credit the wallet, the voucher reaches `REDEEMED` once, and remaining
   copies are invalidated. Verify operation UUID retries do not debit or credit twice.
6. Exercise voucher delivery/redeem receipts through both hands, number-key swaps in both
   directions, off-hand swaps in both directions, drag, hopper movement, pickup, drop, death with
   `keepInventory` on and off, respawn, item-frame/entity/interact-at/ArmorStand interaction,
   crafting, Crafter, anvil, grindstone, smithing (including the initial cursor insertion), consume,
   place, and dispense. Receipt stacks and a voucher reserved before receipt tagging must remain
   protected; ordinary untagged items must keep their normal behavior. After a server restart, test
   the join/reconcile guard before it completes: click, drag, drop, held-slot, and off-hand actions
   must be blocked, then ordinary actions must resume immediately after a no-open-recovery join.
   Repeat the same boundary immediately after respawn; the guard must start in the respawn event,
   before the next-tick inventory-aware reconcile.
   For an untagged ordinary voucher already in an anvil, grindstone, or smithing input, reject
   cursor placement, shift-click, number-key, and off-hand insertion, but allow clicking the top
   input back out to the player's inventory. Repeat with a receipt and an ordinary non-voucher.
   Preload an ItemFrame with a voucher and verify empty-hand right-click rotation/removal and
   player, explosion, and other hanging-break paths are cancelled; placing a voucher into an empty
   ItemFrame must also be cancelled. Repeat with an ordinary ItemFrame item to confirm normal
   interaction and break behavior. Force a redeem operation to `ROLLED_BACK` after its receipt was
   tagged, then rejoin and confirm the matching redeem operation is loaded, only that redeem PDC is
   stripped from inventory/cursor/armor/off-hand copies, the available voucher remains, and the
   redeem hold is released. A different operation's receipt must remain protected, and the rolled-
   back operation must never credit the wallet on retry.
7. While a team has a non-zero wallet or a `PENDING_DELIVERY`/`AVAILABLE`/`RESERVED` voucher,
   confirm disband and sole-owner leave are rejected. After all vouchers are redeemed and balances
   are intentionally spent, confirm the normal team lifecycle remains available. Move and rebuild
   the core and verify the wallet and voucher team binding do not change.

## 8. Research-crystal inventory redemption

1. Outside a defense, issue or obtain a valid `研究結晶` for the player's team and open the core
   management GUI. Leave the main hand empty, then place valid crystals in an unselected hotbar
   slot, an ordinary main-inventory slot, and the off-hand. Click the existing research deposit
   action and confirm all eligible stacks are found and the success message reports the converted
   quantity and resulting research points.
2. Put an ordinary `AMETHYST_SHARD`, another team's crystal, a crystal with a forged team/batch/
   issued-quantity PDC, and a crystal already carrying a redemption receipt in storage. Confirm
   none is consumed or credited. Put crystals in an open chest, crafting grid, cursor, or armor
   slot and confirm those locations are not candidates.
3. Repeat with multiple valid stacks whose total is larger than one stack. Confirm the database
   batch redeemed quantity and team research points increase by the exact total, with no unrelated
   item removed. Click the deposit action again after the batch is exhausted and confirm the clear
   no-item message. Duplicate one delivered stack in a disposable inventory and try both copies;
   only the first redemption for that issued segment may credit or consume, while the duplicate
   remains unconsumed. The other legitimately issued segment must remain redeemable.
4. On a disposable copy, stop the server or plugin at each boundary: receipt tagging, `PREPARED`,
   database `APPLIED`, and physical removal. Reconnect or re-enable the plugin and confirm the
   operation UUID is reconciled exactly once: an applied operation consumes only its matching
   receipt, a prepared operation rolls back without credit, and a rolled-back operation clears its
   matching receipt. A database failure must not remove the crystal without a corresponding point
   credit, and retrying the same operation must not credit twice.
5. Exercise click, drag, off-hand swap, number-key swap, drop, hopper movement, pickup, death with
   both `keepInventory` settings, and respawn while a receipt is present. Confirm the tagged
   crystal cannot leave the player's own inventory before reconciliation, the next-tick respawn
   reconciliation sees the restored storage/off-hand contents, and ordinary untagged items retain
   normal behavior.

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
Portable voucher withdrawal / delivery / redeem / duplicate evidence:
Research-crystal inventory scan / PDC validation / receipt recovery evidence:
Action Bar pickup priority and 40-tick evidence:
Later-player-edit conflict evidence:
Event IDs and operation UUIDs:
Reviewed by:
Gate decision (remain false / approved for this profile):
```
