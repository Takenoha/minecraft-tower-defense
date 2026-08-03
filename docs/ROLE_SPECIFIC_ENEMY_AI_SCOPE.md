# PR #13: role-specific enemy composition and action gate

PR #13 adds the Paper-independent role boundary needed before enemy terrain mutation can be
enabled. It does not enable terrain mutation in the production plugin.

## Included

- Optional `enemies.destroyer-ratio` and `enemies.builder-ratio` settings with non-negative,
  finite values whose sum cannot exceed one. Existing configurations keep the 0.15/0.10 defaults.
- A deterministic wave role schedule for normal enemies, destroyers, builders, and a single final
  wave boss slot. Special-role allocation grows by a bounded stage/wave multiplier and never
  changes the total logical enemy count.
- Role metadata in `TaggedEnemy` and the event-enemy PDC. Legacy tags without the role key remain
  readable as normal enemies; malformed role values are rejected.
- Role-specific navigation speed and persisted ledger types for spawned event enemies.
- A Paper-independent path planner that distinguishes advance, protected-obstacle recalculation,
  destroyer breaking, builder support, and bounded recovery.
- A terrain authorization gate: destroyers may break, builders may build, and normal enemies may
  break only when a caller explicitly proves the fallback condition. Mandatory block protection
  still runs first.

## Deliberate boundary

The production plugin still constructs `TerrainMutationPolicy(false)`. World-aware obstacle
classification, actual builder bridge placement, destroyer block/tower attacks, custom Mob types,
and enabling terrain mutation remain later Paper integration work. The role schedule and PDC
metadata are therefore safe to ship independently of world mutation.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
