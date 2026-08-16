package io.github.takenoha.towerdefense.config;

import io.github.takenoha.towerdefense.domain.WaveMutation;
import io.github.takenoha.towerdefense.domain.WaveMutationSnapshot;
import java.util.List;
import java.util.Objects;

/** Validated configuration for the three selectable wave mutations. */
public record WaveMutationSettings(
        boolean enabled,
        WaveMutationSnapshot swift,
        WaveMutationSnapshot fortified,
        WaveMutationSnapshot reinforcements) {

    public WaveMutationSettings {
        swift = requireProfile("swift", swift, WaveMutation.SWIFT);
        fortified = requireProfile("fortified", fortified, WaveMutation.FORTIFIED);
        reinforcements = requireProfile(
                "reinforcements", reinforcements, WaveMutation.REINFORCEMENTS);
    }

    /** Defaults make the feature available while preserving a neutral legacy API path. */
    public static WaveMutationSettings defaults() {
        return new WaveMutationSettings(
                true,
                new WaveMutationSnapshot(WaveMutation.SWIFT, 1.25d, 1.0d, 1.0d, 1.20d),
                new WaveMutationSnapshot(WaveMutation.FORTIFIED, 1.0d, 1.35d, 1.0d, 1.35d),
                new WaveMutationSnapshot(
                        WaveMutation.REINFORCEMENTS, 1.0d, 1.0d, 1.30d, 1.25d));
    }

    public static List<WaveMutation> candidates() {
        return List.of(
                WaveMutation.SWIFT,
                WaveMutation.FORTIFIED,
                WaveMutation.REINFORCEMENTS);
    }

    /** Resolves a requested selection into the immutable coefficients for one event start. */
    public WaveMutationSnapshot snapshotFor(WaveMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (mutation == WaveMutation.NONE) {
            return WaveMutationSnapshot.none();
        }
        if (!enabled) {
            throw new IllegalArgumentException("wave mutations are disabled");
        }
        return switch (mutation) {
            case SWIFT -> swift;
            case FORTIFIED -> fortified;
            case REINFORCEMENTS -> reinforcements;
            case NONE -> throw new AssertionError("NONE was handled above");
        };
    }

    private static WaveMutationSnapshot requireProfile(
            String name,
            WaveMutationSnapshot profile,
            WaveMutation expectedMutation) {
        Objects.requireNonNull(profile, name);
        if (profile.mutation() != expectedMutation) {
            throw new IllegalArgumentException(
                    name + " profile must use mutation " + expectedMutation);
        }
        return profile;
    }
}
