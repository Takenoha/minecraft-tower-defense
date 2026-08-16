# Support enemy

This increment adds a `SUPPORT` event-enemy role backed by a vanilla Witch.

## Behavior

- Support enemies are allocated by the same deterministic wave role scheduler as the existing
  normal, destroyer, builder, speedster, ranged, heavy, and boss roles.
- The default support ratio is `0.05`; all six non-boss role ratios must remain at or below `1.0`.
- Every support pass selects the most damaged non-support event enemy in the same active session
  and within the configured radius.
- The selected ally receives a bounded health increase and a temporary movement-speed multiplier.
- The pass is driven by the central main-thread defense tick. It never scans or affects players,
  natural mobs, or another event's enemies.

## Safety and lifecycle

- The Witch is tagged with the normal event/logical enemy PDC identity and `FOUNDATION_SUPPORT`
  ledger type.
- Event-targeting and projectile-launch listeners cancel all vanilla Witch targeting and potion
  launches. Support effects are the only special behavior.
- The role uses the existing access policy, no-loot death listener, health bar, path controller,
  core-reach handling, terminal cleanup, and reward paths.
- Support cooldowns and temporary speed state are in-memory runtime state derived from the active
  event. Technical recovery removes the physical enemy and its logical lifecycle row through the
  existing recovery boundary; no support effect can survive an event terminal state.

## Configuration

```yaml
enemies:
  support-ratio: 0.05
  support-radius: 8.0
  support-heal-amount: 4.0
  support-cooldown-ticks: 100
  support-speed-multiplier: 1.15
  support-speed-duration-ticks: 60
```

All values are validated at startup/reload. Radius and heal amount are finite and positive,
cooldowns and duration are positive, speed multiplier is finite and at least `1.0`, and the
support ratio participates in the global non-boss role-ratio cap.
