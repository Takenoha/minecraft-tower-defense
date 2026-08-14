---
title: "Kotlin B17 Paper policy remainder manifest"
tags: [kotlin, java, migration, paper, policies]
status: active
created: 2026-08-14
---

# Kotlin B17 Paper policy remainder manifest

## Scope

- Base: `feat/kotlin-b16-paper-identities-abo` at `880b8e3`
- Implementation commit: `552447f4078254a4cc47eec3ff21087800d91844`
- Migrated boundaries: `CoreMaterialPolicy`, `PaymentSelectionPolicy`, `ReceiptTransferPolicy`, and `VoucherReceiptRecoveryPolicy`
- Java policy sources removed from `src/main/java/io/github/takenoha/towerdefense/paper/`
- Kotlin policy sources added under `src/main/kotlin/io/github/takenoha/towerdefense/paper/`
- Java ABI test added: `PaperPolicyRemainderKotlinBoundaryAbiTest.java`
- Paper listeners, managers, GUI wiring, schema, and repository code were not changed.

## Boundary and invariants

- Core material constants and Java-visible static methods retain their names, descriptors, material values, private utility constructors, and explicit unsupported-material exception path.
- The four material predicates preserve the legacy nullable-input behavior; `requireCoreItemMaterial` retains its non-null requirement.
- Payment selection preserves explicit legacy precedence, wallet-first selection, legacy fallback, and disabled-payment exceptions.
- Both `ReceiptTransferPolicy.containsTagged` overloads retain generic erased signatures and inspect every transfer side; voucher recovery preserves voucher/operation matching and rolled-back/voided state rules.

## Build and artifact verification

The clean verification build completed successfully at verified code HEAD `552447f4078254a4cc47eec3ff21087800d91844`:

- `BUILD SUCCESSFUL`
- 86 XML test reports / 309 tests
- failures 0 / errors 0 / skipped 0
- JVM class major: 69
- four Kotlin policy classes present; Java duplicate classes absent
- packaged JAR SHA-256: `9CFBBD7F1F892616379E8F1349D0B8A26ABEAB99A8DC90A6A12A126A0548D937`
- packaged `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`
- embedded Kotlin runtime entries: 1045
- embedded `kotlin-reflect` artifact entries: 0
- `git diff --check` is clean

Independent fixed-HEAD review and Paper real-server acceptance remain separate gates.
