---
title: "Kotlin B40 tower research GUI manifest"
tags: [kotlin, java, migration, paper, gui, research]
status: active
created: 2026-08-14
---

# Kotlin B40 tower research GUI

- Base: `21d01670bc337bc4398d871bb00a3e935d61dba8` (B39 final).
- Verified code HEAD: `fb1cade2569035cd1e8402869243f5ed87bc4b67`.
- Scope: migrate `TowerResearchGui` from Java to Kotlin and add `TowerResearchGuiKotlinBoundaryAbiTest`.
- Removed source: `src/main/java/io/github/takenoha/towerdefense/paper/TowerResearchGui.java`.
- Added source: `src/main/kotlin/io/github/takenoha/towerdefense/paper/TowerResearchGui.kt`.

## Preserved boundaries

- Public final utility class, private no-arg constructor, public static constants `SIZE=27`, `RESEARCH_START_SLOT=10`, `CLOSE_SLOT=22`, and static `create`/`towerTypeAt` descriptors remain Java-compatible.
- `create` keeps the core/progress/research/settings null guards, TowerResearchInventoryHolder attachment, title, research-point display, per-tower slot mapping, cost/overflow purchase decision, material/name/lore/color choices, close item, and metadata guard.
- `towerTypeAt` keeps the research-slot offset and range behavior. Lore component lists remain unmodifiable as in the old Java stream terminal operation. Existing `CoreManagementListener`, `TowerManager`, and GUI callers were not changed.

## Verification

- Command: `gradlew.bat clean test --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain`.
- Result: `BUILD SUCCESSFUL`; 109 XML test suites, 342 tests, failures 0, errors 0, skipped 0.
- Fat JAR SHA-256: `6BAA2E14B88A5EEEC51015F672436F3DBD0B6FD024FC1CB8FFDEE47F515F8A69`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Kotlin GUI class and Companion present, JVM major 69; Java duplicate: absent.
- Bundled Kotlin runtime entries: 1045; kotlin-reflect artifact entries: 0.
- `git diff --check`: clean; worktree: clean after verification.

Paper server GUI/start/restart/win-loss/abort/technical-recovery and live research-GUI acceptance remain a separate real-server gate.
