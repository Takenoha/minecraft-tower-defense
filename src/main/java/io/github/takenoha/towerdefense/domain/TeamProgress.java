package io.github.takenoha.towerdefense.domain;

import java.util.Objects;
import java.util.UUID;

/** Durable team-wide progression values used by the economy and future research slices. */
public record TeamProgress(
        UUID teamId,
        long highestClearedLevel,
        long unlockedLevel,
        long researchPoints) {
    public TeamProgress {
        Objects.requireNonNull(teamId, "teamId");
        if (highestClearedLevel < 0L) {
            throw new IllegalArgumentException("highestClearedLevel must be non-negative");
        }
        if (unlockedLevel <= 0L) {
            throw new IllegalArgumentException("unlockedLevel must be positive");
        }
        if (researchPoints < 0L) {
            throw new IllegalArgumentException("researchPoints must be non-negative");
        }
        if (unlockedLevel < highestClearedLevel + 1L
                && highestClearedLevel < Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "unlockedLevel must include the next level after the highest clear");
        }
    }

    public static TeamProgress initial(UUID teamId) {
        return new TeamProgress(teamId, 0L, 1L, 0L);
    }
}
