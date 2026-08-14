---
title: "Kotlin B13 defense repository manifest"
tags: [kotlin, java, migration, persistence, defense, repository]
status: active
created: 2026-08-14
---

# Kotlin B13 defense repository manifest

## Scope

- Base: `feat/kotlin-b12-tactical-build-repository-abo` at `7f1b86758106bcd9d4a3c56428f46e3055120d56`
- Implementation commit: `00959b3aac61b9f8c7d2db59917dc88b9611718b`
- Migrated boundary: `DefenseRepository`
- Java source removed: `src/main/java/io/github/takenoha/towerdefense/persistence/DefenseRepository.java`
- Kotlin source added: `src/main/kotlin/io/github/takenoha/towerdefense/persistence/DefenseRepository.kt`
- Java ABI test added: `DefenseRepositoryKotlinBoundaryAbiTest.java`
- Schema, `Database`, Paper wiring, and domain records were not changed.

## Boundary and invariants

- The four public constructors, public constants, and 79 public method descriptors remain Java-compatible, including all overloads for research-crystal redemption, core repair, core placement, snapshot/transition/terminal persistence, and recovery.
- Team and invitation mutations retain owner/member authorization, active-event exclusion, operation UUID fingerprints, compare-and-set state transitions, and exact-once behavior.
- Research-crystal issuance/redemption preserves segment validation, wallet debit/credit, redeemed-quantity accounting, rollback, and recovery boundaries.
- Core placement, repair, relocation, rebuild, receipt handoff, battle funds, tower damage/repair, and defense-event lifecycle preserve their existing guards, idempotency, overflow checks, and terminal/recovery semantics.
- Writes remain inside the same 42 `Database.inImmediateTransaction` call sites; the read-side `Database.openConnection` boundary is retained.

## Static migration checks

Against the B12 Java implementation, normalized SQL text blocks match 108/108. The prepared-statement and bind call counts also match: `prepareStatement` 112, `executeQuery` 47, `executeUpdate` 61, `setString` 290, `setInt` 50, `setLong` 44, and `setDouble` 4. `git diff --check` is clean.

## Build and artifact verification

The full verification build completed successfully after the ABI and lifecycle fixes:

- `BUILD SUCCESSFUL`
- 82 XML test reports / 301 tests
- failures 0 / errors 0 / skipped 0
- Kotlin production class present; Java duplicate class absent
- JVM class major 69
- packaged JAR SHA-256 and `plugin.yml` SHA-256 are recorded from the final clean verification build
- embedded Kotlin runtime entries are retained; `kotlin-reflect` remains absent

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
