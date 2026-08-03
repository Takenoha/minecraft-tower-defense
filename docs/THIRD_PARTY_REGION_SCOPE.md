# PR #12 third-party region protection boundary

PR #12 connects the existing combat-area safety gate to an optional WorldGuard installation while
keeping WorldGuard out of the compile-time and packaged dependencies.

## Included

- `softdepend: [WorldGuard]` lets WorldGuard initialize before the plugin when it is installed.
- A reflection-based adapter discovers WorldGuard at startup, queries the loaded world's region
  manager on the Paper main thread, and projects every non-global region's bounding box.
- A combat circle that intersects a projected region is rejected before core placement and before
  the asynchronous defense start transaction. Bounding-box projection is deliberately conservative
  for polygon regions so a false positive cannot expose a protected area to event mutation.
- An installed-but-disabled or incompatible WorldGuard integration returns a fail-closed violation.
- A server without WorldGuard keeps the existing explicit forbidden-world, forbidden-rectangle,
  and WorldBorder checks unchanged.
- The Paper-independent validator accepts a small probe interface, allowing claim/region plugins
  to be integrated later without coupling the domain module to Bukkit or WorldGuard classes.

## Deliberately not included

- Compile-time WorldGuard dependency or automatic support for arbitrary claim plugins.
- Region flag interpretation beyond conservative non-global-region overlap; the plugin does not
  attempt to infer whether a region's owner would permit a defense event.
- Role-specific enemy AI, real-server load/TPS acceptance, raw NBT access, custom item catalogs,
  towers, research, GUI, or production activation of enemy terrain mutation.

## Verification

Deterministic tests cover third-party violation composition. The no-op and fail-closed adapter
paths are explicit factory branches in the Paper integration. The full acceptance command remains:

```text
./gradlew.bat clean test build --rerun-tasks
```
