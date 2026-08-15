---
title: "Kotlin B75 Team Resource Snapshot Migration Manifest"
tags: [kotlin-migration, records, persistence, resources]
status: active
created: 2026-08-15
---

# Kotlin B75 Team Resource Snapshot Migration Manifest

## Scope

- Base: `b36f5366f5c9fb7002c8dfdd62693cb95d7f4b40` (B74 final)
- Implementation: `b7d2653ce8be286e78898585d80ea7e49e5cc1db`
- Migrated record: `TeamResourceSnapshot`
- ABI regression test: `src/test/java/io/github/takenoha/towerdefense/interop/TeamResourceSnapshotKotlinBoundaryAbiTest.java`

## Preserved contract

- `TeamResourceSnapshot` remains a public JVM Record with the original seven component names,
  order, types, public canonical constructor, and five-argument compatibility constructor.
- Non-null team identity and non-negative resource quantity validation retain the original
  exception message and behavior.
- `balance(ResourceType)`, `provisional(ResourceType)`, and
  `teamProvisional(ResourceType)` preserve the existing ResourceType mapping and require-boundary.
- Existing Paper GUI and persistence callers continue to use the same instance method names;
  Java-facing descriptors remain unchanged.

## Verification

The implementation commit was tested at a fixed HEAD with:

```text
.\gradlew.bat clean test
```

- Result: `BUILD SUCCESSFUL`; 144 XML test suites / 400 tests / 0 failures / 0 errors / 0 skipped.
- Fat JAR SHA-256: `043973D73B9DCA0FC39FCA518AE7EB790A04D6DC6490434E12EE379BAB1CE97D`.
- Embedded `plugin.yml` SHA-256: `F0A932C3FEA5393D48B42858BBD5DCCF92A03E76355BBD0A799C729FFABB043A`.
- Migrated record output is Kotlin-only with no Java duplicate; generated classes use JVM major 69.
- Packaged Kotlin runtime entries: 1045; `kotlin/reflect/jvm/internal/*`: 0.
- `git diff --check`: clean.
- Paper real-server acceptance remains a separate gate.

## Known additive Kotlin surface

The Kotlin `@JvmRecord` data class emits metadata, `componentN`/`copy` helpers, final accessors,
and a `WhenMappings` helper for ResourceType switches. These are additive interop differences;
the Java Record components, constructors, methods, validation, and caller-facing behavior are
preserved.
