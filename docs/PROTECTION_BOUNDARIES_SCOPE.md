# PR #10: combat-area protection boundaries

This milestone prevents a core and its complete horizontal combat circle from being placed or
started in a configured forbidden world, across a configured forbidden rectangle, or beyond the
loaded world's WorldBorder.

## Included

- `protection.forbidden-worlds` accepts case-insensitive world names.
- `protection.forbidden-regions` accepts inclusive horizontal rectangles with `world`, `min-x`,
  `min-z`, `max-x`, and `max-z` fields.
- A rectangle is rejected when any part of the full combat circle intersects it, including edge
  and corner contact.
- A combat circle is rejected unless its four axis-aligned extents fit inside the square
  WorldBorder.
- Core registration performs the same check as event start. The command performs it before the
  asynchronous `tryStart` call, and the Paper session manager repeats it defensively before
  activating an already locked session.
- The safety calculation is Paper-independent and covered by deterministic unit tests; the Paper
  adapter only projects the loaded world's border center and size.

## Configuration

```yaml
protection:
  forbidden-worlds: []
  forbidden-regions: []
```

Example rectangle:

```yaml
protection:
  forbidden-worlds:
    - world_nether
  forbidden-regions:
    - world: world
      min-x: -256
      min-z: -256
      max-x: 256
      max-z: 256
```

## Deliberate boundary

This milestone does not integrate third-party region plugins or infer protected areas from
WorldGuard/claims. It also does not perform public core crafting, physical core replacement,
role-specific pathing, load testing, or production activation of enemy terrain mutation. The
configured rectangles are an explicit deny-list and are evaluated only at core placement and
defense start.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
