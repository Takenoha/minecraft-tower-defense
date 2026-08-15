---
title: "Kotlin B32 Paper tower upgrade receipt manifest"
tags: [kotlin, java, migration, paper, receipts, tower]
status: active
created: 2026-08-14
---

# Kotlin B32 Paper tower upgrade receipt

- Base: `238983f863146a073d39ada58e60694160df9786` (B31 final).
- Verified code HEAD: `663b521b817811246c1a0ecf2950fc0411e9613e`.
- Scope: migrate `TowerUpgradeReceiptTagger` from Java to Kotlin and add `TowerUpgradeReceiptTaggerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/TowerUpgradeReceiptTagger.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TowerUpgradeReceiptTagger.kt`.

## Preserved boundaries

- Public final class, `Plugin` constructor, and tag/strip/operationId/material/isTagged/isFor Java descriptors remain compatible.
- The two plugin PDC keys, clone-before-mutate behavior, metadata-holder guard/message, receipt stripping, UUID parsing, Optional-empty cases, and receipt matching remain unchanged.
- `TowerManager` construction and receipt calls were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 101 XML test suites, 331 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `3B05E5D27E9C736B571756929D3BF9A880CA23D171CE57657EA0AA0B68DAAA44`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin tagger class present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and recipe/particle acceptance remain a separate real-server gate.
