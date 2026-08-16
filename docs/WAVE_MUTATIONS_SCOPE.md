# Wave mutations

Each defense can start with one of three optional wave mutations:

- `SWIFT`: enemy navigation speed is multiplied by `1.25`.
- `FORTIFIED`: enemy health is multiplied by `1.35`.
- `REINFORCEMENTS`: the logical enemy count is multiplied by `1.30`.

Every candidate also has a positive reward multiplier in `config.yml`. The checked-in defaults are
`1.20`, `1.35`, and `1.25` respectively. Integral enemy and reward quantities are rounded up,
and the existing enemy-count safety cap remains authoritative.

The start path stores the selected mutation and all four coefficients in
`DefenseSessionSnapshot`. The SQLite event row stores the same values (schema version 40), while
the existing `StartRequest.configSnapshot` stores the complete settings record. Existing callers
of `DefenseSession` and `TowerDefenseCommand.startWithSeal` use the neutral `NONE` compatibility
path. Administrators can exercise an explicit choice with:

```text
/td admin simulate <stage> <swift|fortified|reinforcements>
```

The active event's status command and BossBar display the snapshotted choice. No live GUI chooser
was added in this slice; the player-facing start methods retain the old neutral overload and expose
an explicit overload for a future GUI selection flow. Paper real-server acceptance remains a
separate gate.
