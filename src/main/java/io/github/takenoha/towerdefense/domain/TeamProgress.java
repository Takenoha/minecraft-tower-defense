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
        long minimumUnlock = highestClearedLevel >= StageWaveSchedule.MAX_STAGE_LEVEL
                ? StageWaveSchedule.MAX_STAGE_LEVEL
                : highestClearedLevel + 1L;
        if (unlockedLevel < minimumUnlock) {
            throw new IllegalArgumentException(
                    "unlockedLevel must include the next level after the highest clear");
        }
    }

    public static TeamProgress initial(UUID teamId) {
        return new TeamProgress(teamId, 0L, 1L, 0L);
    }

    /**
     * Returns the monotonic progression snapshot produced by a stage victory.
     *
     * <p>Defeat and technical recovery deliberately do not call this method. The next-stage
     * unlock is derived from the highest clear and is therefore safe to replay inside the
     * terminal transaction.</p>
     */
    public TeamProgress afterVictory(long stageLevel) {
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
        long nextUnlocked = StageWaveSchedule.nextStageLevel(stageLevel)
                .orElse(stageLevel);
        return new TeamProgress(
                teamId,
                Math.max(highestClearedLevel, stageLevel),
                Math.max(unlockedLevel, nextUnlocked),
                researchPoints);
    }
}
