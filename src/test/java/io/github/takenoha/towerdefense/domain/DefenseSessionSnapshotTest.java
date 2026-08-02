package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefenseSessionSnapshotTest {
    private static final UUID EVENT_ID = new UUID(0L, 1L);
    private static final UUID TEAM_ID = new UUID(0L, 2L);
    private static final UUID PLAYER_ID = new UUID(0L, 3L);

    @Test
    void rejectsParticipantAndScheduleCorruption() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                1,
                                DefensePhase.PREPARATION,
                                0,
                                Set.of(PLAYER_ID),
                                Set.of(),
                                true,
                                0L,
                                0L,
                                CoreState.intact(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                1,
                                DefensePhase.PREPARATION,
                                0,
                                Set.of(),
                                Set.of(),
                                true,
                                0L,
                                0L,
                                CoreState.intact(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshotWithTotalWaves(99)));
    }

    @Test
    void rejectsPhaseSpecificCorruption() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.COUNTDOWN,
                                0,
                                Set.of(PLAYER_ID),
                                Set.of(PLAYER_ID),
                                true,
                                0L,
                                0L,
                                CoreState.intact(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.WAVE_ACTIVE,
                                1,
                                Set.of(PLAYER_ID),
                                Set.of(PLAYER_ID),
                                true,
                                0L,
                                0L,
                                CoreState.intact(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.INTERMISSION,
                                5,
                                Set.of(PLAYER_ID),
                                Set.of(PLAYER_ID),
                                true,
                                0L,
                                0L,
                                CoreState.intact(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.VICTORY,
                                4,
                                Set.of(PLAYER_ID),
                                Set.of(PLAYER_ID),
                                true,
                                0L,
                                0L,
                                CoreState.intact(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.PREPARATION,
                                0,
                                Set.of(PLAYER_ID),
                                Set.of(PLAYER_ID),
                                true,
                                0L,
                                0L,
                                CoreState.destroyed(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.RECOVERY,
                                1,
                                Set.of(),
                                Set.of(),
                                false,
                                1L,
                                0L,
                                CoreState.intact(100L))));
    }

    @Test
    void rejectsNegativeAndOverflowingLogicalEnemyCounts() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.WAVE_ACTIVE,
                                1,
                                Set.of(PLAYER_ID),
                                Set.of(PLAYER_ID),
                                true,
                                -1L,
                                1L,
                                CoreState.intact(100L))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot(
                                2,
                                DefensePhase.WAVE_ACTIVE,
                                1,
                                Set.of(PLAYER_ID),
                                Set.of(PLAYER_ID),
                                true,
                                Long.MAX_VALUE,
                                1L,
                                CoreState.intact(100L))));
    }

    private static DefenseSessionSnapshot snapshot(
            int participantLimit,
            DefensePhase phase,
            int currentWave,
            Set<UUID> registered,
            Set<UUID> effective,
            boolean frozen,
            long pending,
            long alive,
            CoreState coreState) {
        return new DefenseSessionSnapshot(
                EVENT_ID,
                TEAM_ID,
                1L,
                5,
                participantLimit,
                phase,
                currentWave,
                registered,
                effective,
                frozen,
                pending,
                alive,
                coreState);
    }

    private static DefenseSessionSnapshot snapshotWithTotalWaves(int totalWaves) {
        return new DefenseSessionSnapshot(
                EVENT_ID,
                TEAM_ID,
                1L,
                totalWaves,
                1,
                DefensePhase.COUNTDOWN,
                0,
                Set.of(),
                Set.of(),
                false,
                0L,
                0L,
                CoreState.intact(100L));
    }
}
