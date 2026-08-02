package io.github.takenoha.towerdefense.domain;

import java.util.OptionalLong;

/** The initial release's stage-to-wave schedule and technical stage boundary. */
public final class StageWaveSchedule {
    /** Highest selectable stage. Its completion intentionally unlocks no next stage. */
    public static final long MAX_STAGE_LEVEL = Long.MAX_VALUE - 1L;

    private static final int[] FIRST_TEN_WAVE_COUNTS = {
        5, 8, 10, 12, 15, 18, 21, 24, 27, 30
    };
    private static final int LATER_STAGE_WAVE_COUNT = 30;

    private StageWaveSchedule() {
    }

    /** Returns the configured number of waves for a valid stage level. */
    public static int wavesFor(long stageLevel) {
        requireValidStageLevel(stageLevel);
        if (stageLevel <= FIRST_TEN_WAVE_COUNTS.length) {
            return FIRST_TEN_WAVE_COUNTS[(int) stageLevel - 1];
        }
        return LATER_STAGE_WAVE_COUNT;
    }

    /**
     * Returns the next unlockable stage, or empty at the explicitly supported
     * technical ceiling. No overflowing addition is performed.
     */
    public static OptionalLong nextStageLevel(long completedStageLevel) {
        requireValidStageLevel(completedStageLevel);
        if (completedStageLevel == MAX_STAGE_LEVEL) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(completedStageLevel + 1L);
    }

    /** Validates and returns a stage level for use at public API boundaries. */
    public static long requireValidStageLevel(long stageLevel) {
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
        if (stageLevel > MAX_STAGE_LEVEL) {
            throw new IllegalArgumentException(
                    "stageLevel exceeds the technical limit " + MAX_STAGE_LEVEL);
        }
        return stageLevel;
    }
}
