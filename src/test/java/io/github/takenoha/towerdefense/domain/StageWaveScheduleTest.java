package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StageWaveScheduleTest {
    @ParameterizedTest
    @CsvSource({
        "1, 5",
        "2, 8",
        "3, 10",
        "4, 12",
        "5, 15",
        "6, 18",
        "7, 21",
        "8, 24",
        "9, 27",
        "10, 30",
        "11, 30",
        "999999999999, 30"
    })
    void returnsSpecifiedWaveCounts(long stageLevel, int expectedWaves) {
        assertEquals(expectedWaves, StageWaveSchedule.wavesFor(stageLevel));
    }

    @Test
    void acceptsTechnicalCeilingWithoutOverflow() {
        assertEquals(30, StageWaveSchedule.wavesFor(StageWaveSchedule.MAX_STAGE_LEVEL));
        assertEquals(
                OptionalLong.empty(),
                StageWaveSchedule.nextStageLevel(StageWaveSchedule.MAX_STAGE_LEVEL));
    }

    @Test
    void safelyUnlocksTheLevelImmediatelyBelowTheCeiling() {
        OptionalLong next = StageWaveSchedule.nextStageLevel(
                StageWaveSchedule.MAX_STAGE_LEVEL - 1L);
        assertTrue(next.isPresent());
        assertEquals(StageWaveSchedule.MAX_STAGE_LEVEL, next.getAsLong());
    }

    @ParameterizedTest
    @CsvSource({"0", "-1", "-9223372036854775808", "9223372036854775807"})
    void rejectsNonPositiveAndUnsupportedStageLevels(long stageLevel) {
        assertThrows(
                IllegalArgumentException.class,
                () -> StageWaveSchedule.wavesFor(stageLevel));
        assertThrows(
                IllegalArgumentException.class,
                () -> StageWaveSchedule.nextStageLevel(stageLevel));
    }
}
