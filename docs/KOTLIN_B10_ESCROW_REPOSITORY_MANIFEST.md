---
title: "Kotlin B10 escrow repository manifest"
tags: [kotlin, java, migration, persistence, escrow, repository]
status: active
created: 2026-08-14
---

# Kotlin B10 EscrowRepository migration

## Scope

- Base: `a19b8e35eba47c301695f2616ae91f84abf37ec6` (B9 final)
- Branch: `feat/kotlin-b10-escrow-repository-abo`
- Implementation commit: `7333d365e1791a7406a1db0990dbd9f387896bb8`
- Production boundary migrated: `EscrowRepository.java` to `EscrowRepository.kt`
- Java ABI coverage: `EscrowRepositoryKotlinBoundaryAbiTest.java`
- Toolchain: Kotlin/JVM, Gradle 9.6, Java 25, plugin version `0.1.0-SNAPSHOT`

## Boundary preserved

The public Java-facing constructor and 18 instance methods remain available with the same argument and return descriptors. The three terminal/recovery static hooks remain Java-callable `public static` methods with `SQLException` declarations:

- `settleForTerminal(Connection, UUID, UUID, DefensePhase, Instant)`
- `settleForTerminal(Connection, UUID, UUID, DefensePhase, Instant, Duration)`
- `voidForRecovery(Connection, UUID, UUID, Instant)`

The migration does not change schema definitions, migrations, Paper wiring, plugin entry points, or the persistence transaction helper.

## Persistence invariants checked

- `Database.inImmediateTransaction` remains the write transaction boundary; read methods continue to use an opened read connection.
- Escrow drop preparation, display-entity repair, pre-terminal voiding, claim preparation/application, and terminal settlement retain their SQL columns, bind order, active-event/participant guards, and operation UUID fingerprints.
- Wallet-resource settlement remains delegated through `ResourceRepository`; terminal settlement and recovery preserve exact-once operation application and zero-credit recovery behavior.
- Player/team reward queue issuance preserves claim deadlines, deterministic operation IDs, idempotent queue rows, and pending/delivered/voided transitions.
- Reward delivery retains player authorization, reservation ownership, payload fingerprint, and atomic operation/queue status updates.

## Fixed implementation HEAD verification

The clean verification was run at implementation commit `7333d365e1791a7406a1db0990dbd9f387896bb8`:

- `clean test build --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`: `BUILD SUCCESSFUL`
- Test reports: 79 XML files, 298 tests, 0 failures, 0 errors, 0 skipped
- Generated production class: Kotlin class present; duplicate Java class absent
- JVM class major: 69
- Fat JAR SHA-256: `99E6EA4E617885027C5DB1546EE3BBC8E037ABEBE1589ADFC580131DA0FD8C59`
- `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- Embedded Kotlin runtime entries: 1045
- Kotlin-reflect artifact entries (`kotlin/reflect/jvm/internal/*`): 0
- `git diff --check`: clean

Independent fixed-HEAD review and Paper GUI/start, restart, outcome, abort, and technical-recovery acceptance remain separate follow-ups; this manifest does not claim those gates.
