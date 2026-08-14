---
title: "Kotlin B35 Paper research crystal tagger manifest"
tags: [kotlin, java, migration, paper, research-crystal, pdc]
status: active
created: 2026-08-14
---

# Kotlin B35 Paper research crystal tagger

- Base: `3ecb096fdf1d4d27b27184e690e8c5348a94155f` (B34 final).
- Verified code HEAD: `b02aaec30b7bf6a56d34199768807f7ebdd36904`.
- Scope: migrate `ResearchCrystalTagger` from Java to Kotlin and add `ResearchCrystalTaggerKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/ResearchCrystalTagger.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/ResearchCrystalTagger.kt`.

## Preserved boundaries

- Public final class, `Plugin` constructor, constants `ITEM_VERSION=1` and `STACK_LIMIT=64`, create overloads, nullable read/receipt methods, and `ResearchCrystalItemIdentity` Optional descriptors remain Java-compatible.
- The old namespace constructor and package-private redemption helper methods are publicized by the Kotlin boundary; existing callers retain their descriptors and the additive visibility is recorded here.
- The research-crystal PDC keys, AMETHYST_SHARD material, marker/version/UUID/quantity validation, segment pairing/positive/stack-limit/batch-boundary guards, Japanese display/lore, and amount-one creation remain unchanged.
- Redemption receipt read suppression, operation UUID parsing, tag/clear mutation, null/metadata guards, and `research crystal metadata` null failure message remain unchanged. `ResearchCrystalTagger` callers and `ResearchCrystalInventoryPolicy` were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 104 XML test suites, 337 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `714517C2882969E7D357E71DA07DCF2BF7005A1219EFB978C7DD6FB93B671BD7`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin tagger class and Companion present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and receipt/recipe acceptance remain a separate real-server gate.
