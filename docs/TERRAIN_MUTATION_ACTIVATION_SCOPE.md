# Terrain mutation activation gate

This milestone adds the explicit, fail-closed activation boundary for the enemy terrain-action
adapter. It does not claim that a Paper server has been tested, and the checked-in configuration
keeps every activation input `false`.

## Independent inputs

The `terrain-mutation` section contains three deliberately separate inputs:

- `requested`: an explicit operator request to enable the experimental path;
- `paper-integration-verified`: reviewed evidence that the target Paper build reads, applies, and
  verifies the intended block action on the main thread;
- `recovery-verified`: reviewed evidence that normal settlement, abort, shutdown, and startup
  recovery preserve later player edits and settle or roll back event-owned state correctly.

The runtime enables `TerrainMutationPolicy` only when all three inputs are `true`. A missing
section or missing key is treated as `false`; a malformed value rejects configuration loading.
The mandatory code-owned protected-material set remains in force after activation.

## Current safety boundary

The default `config.yml` sets all three inputs to `false`. No real Paper server or tick-load test
was available during this change, so no activation evidence is recorded and no production
configuration is changed to enable terrain mutation. The startup log and `/td admin status` expose
the gate state and its blocking inputs for operator review.

Setting the flags is an operational attestation, not an automated test result. An operator must
retain the tested Paper version, server profile, event IDs, terminal recovery evidence, and
observed WAL/escrow results alongside the configuration change. The flags must remain false until
that evidence has been reviewed.

## Future activation change

Before enabling the path on a production server, run the isolated Paper movement and recovery
procedure, review the results, and make the configuration change as a separately reviewed
operation. If any input is false, the shared terrain action returns `DISABLED` and the tagged enemy
block event remains cancelled.
