# Tactical branch graph scope

This slice adds the first true branching tactical build while preserving the existing linear
builds and their legacy snapshots.

## Included

- Branch node metadata: prerequisites, exclusive branch group, and branch ID.
- Fail-closed validation for references, duplicate/self/cyclic prerequisites, tier order, and branch
  topology.
- Versioned `tdb2` snapshots for branch metadata; `tdb1` linear snapshots remain readable.
- `arrow-specialization` with two mutually exclusive three-tier paths: `rapid-fire` and `range`.
- Paper candidate-selection buttons for choosing one path before the defense starts.
- Schema v39 persistence for the selected branch and individual unlocked node IDs. Existing v38
  linear unlock rows are copied into the new node-unlock table.
- Runtime prerequisite resolution and effect compilation from the explicitly unlocked node set.
- Restart recovery coverage for the selected path and unlocked nodes.

## Deliberate boundary

The branch choice is scoped to one tactical defense run. It is not the permanent tower research
tree and does not introduce research-point costs or permanent player unlocks. Permanent research
progress remains covered by the tower-research slice.

Paper client acceptance of the branch buttons, plus the full start/restart/terminal lifecycle, still
belongs in `docs/PAPER_ACCEPTANCE_RUNBOOK.md` on a disposable server and database.
