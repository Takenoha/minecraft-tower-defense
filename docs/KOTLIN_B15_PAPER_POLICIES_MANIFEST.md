---
title: "Kotlin B15 Paper policies manifest"
tags: [kotlin, java, migration, paper, policies]
status: active
created: 2026-08-14
---

# Kotlin B15 Paper policies manifest

## Scope

- Base: `feat/kotlin-b14-paper-foundation-abo` at `b60d62f1bdcfce66f5e4d06a111dff62f46f9ed1`
- Implementation commit: `a93b54f`
- Migrated boundaries: `PlayerRecoveryGuard`, `VoucherEntityPolicy`, `VoucherContainerPolicy`, `RaidSealMaterialPolicy`, and `RaidSealAutomationPolicy`
- Java sources removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperPoliciesKotlinBoundaryAbiTest.java`
- Paper listeners, managers, GUI wiring, schema, and repository code were not changed.
- Corrected B13/B14 base follow-up commits were propagated before final verification.

## Boundary and invariants

- `PlayerRecoveryGuard` retains its public constructor and `begin(UUID)`, `complete(UUID)`, and `isGuarded(UUID)` lifecycle methods with the same state semantics.
- Voucher entity policy retains the interaction and hanging-break guards; container policy preserves the forbidden-inventory, top-target, cursor, number-key, offhand, shift-click, and clicked-item decision matrix.
- Raid-seal material policy preserves the `ECHO_SHARD` and legacy `ENDER_EYE` constants and both compatibility predicates with null validation.
- Raid-seal automation policy preserves the right-click and crafter cancellation predicates, including plugin-recipe, template-result, current-ingredient, and legacy-ingredient checks.
- Utility policies retain private constructors and Java-visible static method/constant boundaries through `@JvmStatic` and companion constants.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `71d7addc4a6839c85ee5ba1c5593d67fa0130d0e`:

- `BUILD SUCCESSFUL`
- 84 XML test reports / 305 tests
- failures 0 / errors 0 / skipped 0
- Kotlin policy classes present; Java policy duplicates absent
- JVM class major: 69
- packaged JAR SHA-256: `A8FF2547FDDF43EC60B690642A1E78E2A801F83670A383ECA24DFE32F5CD7259`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
