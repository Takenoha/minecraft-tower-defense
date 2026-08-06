package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CoreAttackScheduleTest {
    @Test
    void firstAttackIsImmediateAndLaterAttacksRespectTheInterval() {
        CoreAttackSchedule schedule = new CoreAttackSchedule(20L);

        assertTrue(schedule.tryClaim(5L));
        assertFalse(schedule.tryClaim(24L));
        assertTrue(schedule.tryClaim(25L));
        assertFalse(schedule.tryClaim(44L));
        assertTrue(schedule.tryClaim(45L));
    }

    @Test
    void delayedTicksDoNotCauseCatchUpBursts() {
        CoreAttackSchedule schedule = new CoreAttackSchedule(20L);

        assertTrue(schedule.tryClaim(5L));
        assertTrue(schedule.tryClaim(100L));
        assertFalse(schedule.tryClaim(119L));
        assertTrue(schedule.tryClaim(120L));
    }

    @Test
    void retimingKeepsAlreadyDueAttacksDueWithoutASecondClaim() {
        CoreAttackSchedule schedule = new CoreAttackSchedule(20L);

        assertTrue(schedule.tryClaim(5L));
        schedule.updateInterval(5L, 10L);
        assertFalse(schedule.tryClaim(14L));
        assertTrue(schedule.tryClaim(15L));

        schedule.updateInterval(50L, 16L);
        assertTrue(schedule.tryClaim(20L));
        assertFalse(schedule.tryClaim(69L));
        assertTrue(schedule.tryClaim(70L));
    }

    @Test
    void rejectsInvalidIntervalsAndTicks() {
        assertThrows(IllegalArgumentException.class, () -> new CoreAttackSchedule(0L));
        assertThrows(IllegalArgumentException.class, () -> new CoreAttackSchedule(-1L));

        CoreAttackSchedule schedule = new CoreAttackSchedule(1L);
        assertThrows(IllegalArgumentException.class, () -> schedule.tryClaim(-1L));
    }
}
