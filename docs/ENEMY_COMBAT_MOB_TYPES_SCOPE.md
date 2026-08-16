# Enemy combat MOB types

This increment adds three role-backed vanilla MOB variants to the active defense wave:

| Role | Vanilla MOB | Movement | Health | Combat identity |
| --- | --- | ---: | ---: | --- |
| `SPEEDSTER` | Spider | 1.5x | 0.75x native | Fast approach and native spider climbing |
| `RANGED` | Skeleton | 0.8x | 0.9x native | Native skeleton bow attacks against valid participants |
| `HEAVY` | Ravager | 0.55x | 1.25x native | High native health and full knockback resistance |

The existing roles remain unchanged: normal enemies use Zombies, destroyers use Husks, builders
use Zombie Villagers, and bosses use Zombies with the configured boss health multiplier.

## Wave composition

The new roles use configurable baseline ratios in `config.yml`:

- `enemies.speedster-ratio: 0.10`
- `enemies.ranged-ratio: 0.10`
- `enemies.heavy-ratio: 0.05`

All five non-boss role ratios must sum to at most one. Stage/wave progression increases the
combined specialist allocation up to the bounded ratio budget, so the wave enemy count never
changes and each configured role remains eligible for selection.

## Lifecycle and safety

The new variants use the existing event enemy PDC role tag, ledger type, health-bar refresh,
damage gate, no-loot listener, and core-reach lifecycle. Skeleton targeting is still filtered by
the existing participant access policy. Terrain actions remain unavailable to the new roles, and
the production terrain mutation policy remains disabled.
