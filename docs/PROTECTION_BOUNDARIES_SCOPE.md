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
- When WorldGuard is installed, the plugin uses a reflection-based soft-dependency adapter to
  reject circles whose conservative bounds intersect a non-global WorldGuard region. The global
  region is intentionally ignored because explicit `forbidden-worlds` and `forbidden-regions`
  remain the configuration-owned deny-list.
- If the installed WorldGuard API cannot be queried, the adapter fails closed and rejects the
  placement/start check rather than allowing an uncertain region boundary.
- Core registration performs the same check as event start. The command performs it before the
  asynchronous `tryStart` call, and the Paper session manager repeats it defensively before
  activating an already locked session.
- The safety calculation is Paper-independent and covered by deterministic unit tests; the Paper
  adapter projects the loaded world's border and delegates the optional region query on the main
  thread.

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

This milestone only integrates WorldGuard through the conservative soft-dependency adapter. It
does not infer claim protection from arbitrary plugins, perform public core crafting, physical core
replacement, role-specific pathing, load testing, or production activation of enemy terrain
mutation. The configured rectangles remain an explicit deny-list and all checks are evaluated at
core placement and defense start.

## Verification

The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
