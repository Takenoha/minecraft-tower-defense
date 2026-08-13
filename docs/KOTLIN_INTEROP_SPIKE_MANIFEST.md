---
title: "Kotlin JVM Interop Spike Manifest"
tags: [kotlin, java, gradle, interop, migration]
status: active
created: 2026-08-13
---

# Kotlin JVM interop spike manifest

This document records the compatibility spike for the Java-to-Kotlin migration. It is
production-code neutral: the Kotlin surface is test-only, and the plugin artifact must remain
unchanged.

## Baseline

PR #30 has now been merged into `main`. The formal `KOTLIN_BASE` is the merge commit
`f532f42605d686095244c36b0dd8e18c51623502`:

```text
f532f42605d686095244c36b0dd8e18c51623502
```

The spike branch was rebased from the verified candidate onto this merge commit and now
ends at `e475396a3562615dc95b153aae2d2de0c0aa0781`. The merge commit has the candidate's
production tree; the rebase only changes the parent of the test-only spike commit.

| Item | Baseline evidence |
| --- | --- |
| formal `KOTLIN_BASE` | `f532f42605d686095244c36b0dd8e18c51623502` (PR #30 merge commit) |
| Java | OpenJDK 25.0.3 |
| Gradle | wrapper 9.6.0 |
| schema | `SchemaMigrator.CURRENT_VERSION = 39` |
| plugin.yml SHA-256 | `50EEC0225EA57EEBB4CDFF6D877C7AC02A9E65E4A6986969156CBA83F13178A6` |
| plugin JAR SHA-256 | `A3CE9BDE3B47AA092F9DE4F7B0BC7F3E981B830373A28E6BCA6F1D5A77278B53` |
| regression suite | 268 tests, 0 failures, 0 errors |
| representative `tdb1` | `rapid-fire`; SHA-256 `bb837a470866bbadd3027615312332a4f84001a724cba8162ee8aad92ca28e04` |
| representative `tdb2` | `arrow-specialization`; SHA-256 `1616a4117b321c04244baa2c0388a8f6a69d4eb03a8c30e8fdd4279ef2f37040` |

The representative snapshots were generated from `TacticalBuildCatalog.defaults()` using
`TacticalDefinitionCodec.encode`. Their envelopes remain `{"format":"tdb1"...}` and
`{"format":"tdb2"...}` respectively.

## Spike changes

- Apply Kotlin JVM Gradle plugin `2.4.10`.
- Keep Java and Kotlin JVM targets at 25, with a Java 25 Kotlin toolchain.
- Disable the plugin's default main-source-set stdlib dependency with
  `kotlin.stdlib.default.dependency=false`.
- Add `kotlin-stdlib:2.4.10` to `testImplementation` only. The runtime dependency tree remains
  `org.xerial:sqlite-jdbc:3.50.3.0`; no Kotlin runtime is packaged in the plugin JAR.
- Add only test-source probes under `src/test/kotlin` and a Java JUnit caller under
  `src/test/java`. No `src/main/java` or `src/main/resources` production behavior was changed.
- Ignore the Kotlin plugin's `.kotlin/` project data directory.

## Interop rules proven by tests

- Kotlin reads Java record accessors through their explicit methods (`id()`, `branchId()`) and
  does not infer a nullable contract from `Optional`.
- `Optional<T>` and Kotlin `T?` are converted at an explicit boundary with `Optional.ofNullable`
  and `orElse(null)`.
- A Kotlin checked-exception boundary must use `@Throws(IOException::class)` for Java callers;
  the Java probe observes the declared `IOException`.
- Kotlin `List` is exposed as a Java `List`, but the probe's `listOf` result is immutable at
  runtime and rejects `add` with `UnsupportedOperationException`.
- Java production `TacticalDefinitionCodec` continues to round-trip `tdb1` and `tdb2`, and the
  Kotlin probe reads the schema-v39 constant without changing either contract.

## Verification after the spike

Command:

```text
.\gradlew.bat clean test build --rerun-tasks --no-build-cache --no-daemon
```

Result:

```text
BUILD SUCCESSFUL
274 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL on HEAD `e475396a3562615dc95b153aae2d2de0c0aa0781`
```

Additional checks:

- Java production and Kotlin test probe class files are both JVM major version 69 (Java 25).
- `plugin.yml` SHA-256 is unchanged from baseline.
- Plugin JAR SHA-256 is unchanged from baseline.
- The JAR contains `TowerDefensePlugin.class` and `plugin.yml`, but no `kotlin/`,
  `kotlin-reflect`, or `KotlinInteropProbe` entries.
- `gradlew.bat help --warning-mode=all` completed without a Kotlin/Gradle compatibility warning.
- `git diff --check` is clean.
- Paper GUI/start/restart/terminal/recovery acceptance was not run. This worktree contains no
  Paper server artifact or disposable `run/` environment; automatic Gradle success is not Paper
  acceptance evidence.

## Decision

The Java 25 / Gradle 9.6.0 / Kotlin 2.4.10 combination is buildable and the probe contract is
ready to anchor the next production slice. Kotlin's official compatibility table lists Gradle
9.5.0 as the maximum fully supported Gradle version for KGP 2.4.x; 9.6.0 passed this spike but
remains outside that fully supported range. Keep the wrapper unchanged for this isolated spike,
and decide whether to pin 9.5.x before production Kotlin migration if the next PR exposes a
Gradle compatibility warning.

The formal `KOTLIN_BASE` is now the PR #30 merge commit
`f532f42605d686095244c36b0dd8e18c51623502`. The branch is a ready-to-review child of that
merge commit, with the same production tree and the Kotlin surface restricted to test code.

## References

- [Kotlin Gradle project configuration](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Kotlin 2.4.0: Java/JVM and Gradle compatibility](https://kotlinlang.org/docs/whatsnew24.html)
- [Adding Kotlin to a Java project](https://kotlinlang.org/docs/mixing-java-kotlin-intellij.html)
- [Kotlin compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html)
