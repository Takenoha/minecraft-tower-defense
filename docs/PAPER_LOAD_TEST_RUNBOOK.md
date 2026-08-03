# Paper runtime integration and load-test runbook

PR #19 exposes the live path-inspection counters through `/td admin status` so a Paper test
server can be measured while a defense event is active. The terminal log still prints the same
snapshot after the event ends.

## Prerequisites

- Paper 26.2 build 87 on Java 25
- an isolated test world and an operator account
- the plugin JAR built from the PR under test
- `TerrainMutationPolicy(false)` left unchanged; this runbook does not authorize production
  terrain mutation

## Procedure

1. Install `build/libs/minecraft-tower-defense-0.1.0-SNAPSHOT.jar` and start the isolated Paper
   server.
2. Look at a safe solid block and run `/td admin core`.
3. Run `/td admin simulate 1` and keep the test player inside the configured combat area.
4. During each active wave, record `/td admin status`. The status includes event phase, enemy
   counts, `pathInspections`, `pathFailures`, `pathAvgNanos`, `pathMaxNanos`, builder bridge
   attempts/placements, destroyer break attempts/successes, and `coreAttackers`/`coreAttacks`.
   An enemy that reaches the core remains alive and contributes one `coreAttacks` increment every
   `core.attack-interval-ticks` ticks; the default interval is 20 ticks (one second).
5. After normal completion or `/td admin abort`, save the server log line beginning with
   `Enemy path metrics for event`. It contains the terminal snapshot for comparison.
6. Repeat with the chosen `enemies.max-alive` and `enemies.spawn-per-tick` profile. Keep the
   profile and server version beside each recorded result.

## Evidence checklist

- the status and terminal log refer to the tested event ID;
- Paper read failures and persistence errors are zero unless deliberately injected;
- average/max inspection latency and enemy counts are recorded per wave;
- tagged enemy events remain cancelled and no terrain changes occur;
- temporary bridge cleanup and event-owned drops are absent or settled according to the existing
  WAL/escrow rules;
- `/td admin abort` completes technical recovery from the same test profile.

This runbook records observations; it does not invent a latency threshold. Production activation
requires a reviewed Paper-server result, recovery evidence, and a separate change to the explicit
terrain-mutation gate.

## Local code verification

```text
./gradlew.bat clean test build --rerun-tasks --no-daemon
```
