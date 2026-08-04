# First implementation scope

This repository starts with an administrator-only walking skeleton. It proves the lifecycle and recovery boundaries before any event is allowed to modify a survival world.

## Included

- Exact Paper 26.2 / build 87 / Java 25 build target.
- Validated combat, core, and enemy settings.
- Persisted solo test teams and one core per team.
- Minimum core separation and core HP persistence.
- A database-backed, server-global active-event lock.
- Deterministic `COUNTDOWN`, `PREPARATION`, `WAVE_ACTIVE`, `INTERMISSION`, `VICTORY`, `DEFEAT`, `ABORTED`, and `RECOVERY` transitions.
- Fixed registered participants and a separate effective-participant set.
- Five playable level-one waves, including a final boss.
- Logical enemy IDs, an alive cap, a pending spawn queue, and idempotent death accounting.
- Victory, core-destruction defeat, and registered-participant absence defeat.
- Startup cleanup and recovery instead of attempting to resume a partial battle.

## PR #2 persistence boundary

PR #2 adds the durable prerequisite for world mutation and rewards without enabling either in the
Paper simulation:

- write-ahead block-change records with generation, expected-after comparison, reverse recovery,
  and conflict preservation;
- virtual event-drop escrow and registered-participant claim records;
- normal-terminal reward queue issuance and technical-recovery invalidation;
- single-use raid-seal reservation/consumption and new-UUID technical refunds;
- terminal/recovery transactions that settle or invalidate these records atomically.

Live Bukkit/Paper block listeners, escrow entity listeners, inventory reconciliation, and physical
queue delivery remain disabled until the next adapter milestone. See
`docs/ROLLBACK_ESCROW_SCOPE.md` for the exact API boundary and remaining integration work.

## PR #3 core and team persistence boundary

PR #3 adds durable ownership and core lifecycle mutations without enabling public crafting or GUI
flows:

- operation-UUID protected team member add/remove, ownership transfer, leave, and disband;
- actor membership checks and server-global event-lock checks on core/team mutations;
- full-health core relocation, repair, destroyed-core rebuild, and distance conflict checks;
- a core registry replacement path that stops protecting a destroyed core after a main-thread caller
  refreshes it.

The administrator test command remains the only enabled placement path. Physical block replacement,
world-border and protected-region validation, repair costs, public team management, and GUI
confirmation remain future Paper adapter work. See `docs/CORE_TEAM_SCOPE.md`.

## PR #4 Paper recovery adapter boundary

PR #4 connects the PR2 ledger to the Paper main thread without enabling enemy terrain mutation:

- schema v5 persists a prepared rollback decision for the physical-restore/database-acknowledgement
  stop window;
- Paper captures canonical BlockData and block type, refuses existing tile-entity mutation sources,
  applies with physics disabled, verifies the result, and only then acknowledges the ledger row;
- startup and shutdown recovery run reverse-generation planning before technical event recovery,
  preserving later player edits as durable conflicts.

Entity-enemy break/place actions, tile-container NBT, escrow entities, hopper/container/death
protection, protected-region checks, and reward delivery remain disabled. See
`docs/PAPER_RECOVERY_ADAPTER_SCOPE.md`.

## PR #5 guarded enemy terrain action boundary

PR #5 adds the first single-block enemy action path and keeps it disabled in production:

- `TerrainMutationPolicy` code-owns mandatory protection for cores, inventory-like blocks, beds,
  redstone machinery, portals, administrative blocks, and dangerous materials;
- the Paper event handler validates the tagged enemy and combat area, cancels the vanilla event, and
  routes an allowed action through the PR4 WAL adapter with a durable coordinate generation;
- destruction and placement are recorded as `EVENT_BLOCK` and `TEMPORARY_BLOCK` respectively so
  later normal-end settlement can apply different cleanup rules.

Normal-end settlement is not yet wired, so the production listener continues to cancel all tagged
enemy block changes. Tile NBT, block-drop escrow, hopper/container/death protection,
protected-region validation, and role-specific AI remain future work. See
`docs/ENEMY_TERRAIN_ACTION_SCOPE.md`.

## PR #6 normal terminal terrain settlement boundary

PR #6 adds the normal terminal terrain lifecycle without enabling enemy mutation in production:

- schema v6 records `SETTLED` event-destruction rows and migrates the v5 ledger;
- `EVENT_BLOCK` rows remain destroyed after normal victory, defeat, or abort, and are settled only
  after their physical apply acknowledgement;
- `TEMPORARY_BLOCK` rows are removed in reverse generation order through the existing conflict-safe
  rollback planner;
- Paper terminal handling completes terrain settlement before asynchronous event-lock release and
  retries while retaining the lock if settlement fails.

Technical recovery still rolls back every unresolved row. PR7 supplies the physical block-drop
escrow and display/pickup/container/death protection boundary; see
`docs/BLOCK_DROP_ESCROW_SCOPE.md`.

## PR #7 physical block-drop escrow boundary

PR #7 adds the Paper-side physical representation of ordinary event-block drops while keeping
the production terrain policy disabled:

- no-tool block drops are serialized into the existing held escrow rows before the block apply;
- PDC-tagged Item entities are spawned only after the WAL apply acknowledgement and cannot be
  picked up as ordinary inventory items;
- registered participant claims cross the asynchronous persistence boundary, while pickup,
  inventory, crafting, placement, dispensing, item-frame, merge, despawn, damage, portal, and
  death paths are blocked;
- normal terminal settlement clears the physical display and technical recovery removes it while
  voiding held escrow rows.

Reward queue delivery, Tile NBT, protected-region validation, and role-specific terrain AI remain
future work. See `docs/BLOCK_DROP_ESCROW_SCOPE.md`.

## PR #8 reward queue delivery boundary

PR #8 connects terminal queue rows to Paper inventory delivery while keeping terrain mutation
disabled:

- schema v8 records a prepared/applied, idempotent delivery operation per queue row;
- original personal recipients and registered current team members are the only eligible readers;
- a shared TEAM row is durably reserved for one eligible player during its inventory handoff;
- online players are retried after normal terminal persistence and at login;
- the Paper main-thread inventory handoff uses a queue/operation receipt, and full inventories or
  failures leave the row pending for a later retry.

Technical recovery still voids the queue atomically and does not schedule delivery. Custom reward
catalogs, research progression, protected-region checks, role-specific terrain AI, and production
terrain activation remain future work. PR #9 adds the
Tile NBT API projection and protected-target synchronization. See
`docs/REWARD_QUEUE_DELIVERY_SCOPE.md`.

## PR #9 tile payload and protected-target synchronization boundary

PR #9 adds schema v9 tile payload columns to the block WAL and includes the payload in recovery
comparisons. The Paper codec persists persistent-data bytes, snapshot inventory bytes, lock state,
and custom name through a versioned API projection, then applies it on the main thread before the
physical restore is acknowledged. Tile states are now mandatory protected targets for enemy
terrain actions. A main-thread listener also blocks indirect break, place, piston, explosion,
fluid, physics, growth, and entity-change paths for protected cores and protected targets inside
an active combat area. See `docs/TILE_NBT_PROTECTION_SCOPE.md`.

## Safety boundary

Until the remaining activation gates exist, event enemies cannot break or place blocks and the
simulation creates no custom or vanilla rewards. PR #7 supplies database-owned block-drop capture
and a non-usable physical display; PR #8 only delivers rows that already exist in the durable
queue and does not create new reward sources. PR #9 keeps the terrain policy disabled and only
adds the protection and recovery boundaries needed before activation.
Towers, research, start-item reservation, public core crafting, physical core replacement, and GUI
team management remain disabled. PR6 has the normal terrain settlement path, but PR5's production
policy remains disabled.

## PR #10 combat-area protection boundary

PR #10 adds an explicit configuration deny-list for forbidden worlds and horizontal rectangles,
plus a WorldBorder check that requires the complete combat circle to fit inside the loaded world's
square border. Core registration and defense start run the check before the asynchronous database
start transaction, while the session manager repeats it before activation as a defense-in-depth
guard. Third-party region-plugin integration, role-specific terrain AI, Paper load testing, and
production terrain activation remain future work. See
`docs/PROTECTION_BOUNDARIES_SCOPE.md`.

## PR #11 TEAM reward retention and owner fallback boundary

PR #11 adds schema v10's nullable TEAM claim deadline and the validated
`rewards.team-queue-retention-seconds` setting. Registered event participants remain eligible for
TEAM rows, while the current team owner becomes an additional eligible recipient only after the
persisted retention deadline. Legacy rows without a deadline remain participant-only. Existing
delivery reservations, PDC receipts, and technical-recovery voiding are unchanged. See
`docs/TEAM_REWARD_FALLBACK_SCOPE.md`.

## PR #12 third-party region protection boundary

PR #12 adds an optional reflection-based WorldGuard adapter with `softdepend` ordering. Before
core placement and defense start, it rejects combat circles that conservatively overlap any
non-global WorldGuard region; an installed but unavailable integration fails closed. Servers
without WorldGuard retain the explicit forbidden-world, forbidden-rectangle, and WorldBorder
checks. The domain validator exposes a Paper-independent probe so other claim systems can be
integrated later. See `docs/THIRD_PARTY_REGION_SCOPE.md`.

## PR #13 role-specific enemy AI boundary

PR #13 adds optional destroyer/builder ratios, deterministic role composition, final-wave boss
allocation, role PDC metadata, role-specific navigation speed, and a Paper-independent path
planner. The terrain gate separates destroyer breaking, builder placement, and the explicitly
proven normal-enemy fallback while keeping mandatory block protection first. See
`docs/ROLE_SPECIFIC_ENEMY_AI_SCOPE.md`.

## PR #14 world-aware obstacle boundary

PR #14 adds a Paper-independent, fail-closed obstacle classifier and a Paper main-thread adapter
for candidate block, target BlockData, and support-block facts. It protects current cores,
inventory/tile blocks, required materials, target tile states, and unsafe/out-of-area support;
only ordinary breakable blocks and verified replaceable gaps are exposed to the role planner. The
Paper event action rejects classification/action mismatches before the existing WAL adapter.
`TerrainMutationPolicy(false)` remains the production setting, so this is read-only world-state
classification rather than terrain activation. Actual path-controller wiring, bridge placement,
destroyer operations, custom Mob types, and activation remain future work. See
`docs/OBSTACLE_CLASSIFICATION_SCOPE.md`.

## Acceptance mapping

The first implementation targets these requirement checks directly:

- `AC-21.1-1`, `AC-21.1-2`, `AC-21.1-4`, `AC-21.1-5`
- `AC-21.2-1`, `AC-21.2-5`, `AC-21.2-6`, `AC-21.2-9`, `AC-21.2-10`
- `AC-21.3-5`
- `AC-21.7-1`, `AC-21.7-8`

The HP, enemy cleanup, stage-wave, and configuration checks are only partially satisfied where the full requirement also depends on later repair, economy, terrain, AI, or tower work.

## PR #17 Paper path integration and load measurement boundary

PR #17 routes live path inspection through a main-thread Paper integration seam. A Paper read
failure becomes an unavailable obstacle, and in-memory per-event metrics capture inspection
latency, path decisions, and builder bridge acknowledgements for load testing. The production
terrain policy remains disabled; a real Paper test server is still required before claiming
runtime movement or tick-load validation. See `docs/PAPER_PATH_INTEGRATION_SCOPE.md`.

## PR #18 destroyer block-action boundary

PR #18 routes a destroyer's `BREAK_OBSTACLE` decision through a read-only candidate snapshot and
an activation-ready `EVENT_BLOCK` mutation boundary. The action rechecks `BREAKABLE` facts,
protected-block policy, role authorization, and the observed before-state, then reuses the block
WAL and ordinary block-drop escrow. The production `TerrainMutationPolicy(false)` remains
disabled, so no live terrain mutation or real Paper server load-test result is claimed. See
`docs/DESTROYER_BLOCK_ACTION_SCOPE.md`.

## PR #19 Paper load-test observability boundary

PR #19 exposes the immutable per-event `EnemyPathMetrics.Snapshot` through the administrator
status command while a defense is active and documents a repeatable Paper-server observation
procedure. It remains read-only: `TerrainMutationPolicy(false)` and tagged event cancellation
are unchanged, and production activation still requires a reviewed real-server result. See
`docs/PAPER_LOAD_TEST_RUNBOOK.md`.

## PR #20 explicit terrain activation gate

PR #20 replaces the hard-coded production-disabled constructor input with an explicit, fail-closed
configuration gate. An operator request, reviewed Paper integration evidence, and reviewed
recovery evidence are read independently; terrain mutation is enabled only when all three values
are true. Missing values remain false, malformed values reject configuration loading, and the
mandatory code-owned protected-material policy is unchanged. The checked-in defaults remain
disabled because a real Paper server and tick/recovery test are still required. See
`docs/TERRAIN_MUTATION_ACTIVATION_SCOPE.md`.

## Public core physical-placement boundary

The public core slice connects a unique shaped-recipe item to a protected Paper block replacement.
It validates the ordinary Overworld target through the existing combat-area and third-party region
checks, requires the team owner, rejects an active event, tags the resulting beacon, and records a
schema-v11 `PREPARED`/`APPLIED`/`ROLLED_BACK` operation so startup recovery cannot leave an
unaccounted physical replacement. A zero-health persisted core can be rebuilt with the same core
UUID at a new valid position. Bound-core itemization/relocation, repair costs, towers, research,
start items, and team GUI remain future slices. See `docs/PUBLIC_CORE_PLACEMENT_SCOPE.md`.
