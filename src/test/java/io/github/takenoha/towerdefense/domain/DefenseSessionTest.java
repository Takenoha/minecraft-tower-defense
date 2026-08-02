package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefenseSessionTest {
    private static final UUID REGISTERED = id(1L);
    private static final UUID SECOND_REGISTERED = id(2L);
    private static final UUID LATE_HELPER = id(3L);
    private static final UUID ANOTHER_HELPER = id(4L);

    @Test
    void startsAtCountdownWithStageDerivedWaveCount() {
        DefenseSession session = newSession(10L, 8);

        assertEquals(DefensePhase.COUNTDOWN, session.phase());
        assertEquals(30, session.totalWaves());
        assertEquals(0, session.currentWave());
        assertEquals(0L, session.remainingLogicalEnemies());
        assertFalse(session.participantsFrozen());
        assertTrue(session.registeredParticipants().isEmpty());
        assertTrue(session.effectiveParticipants().isEmpty());
    }

    @Test
    void freezesRegisteredParticipantsAndKeepsBothSetsEncapsulated() {
        DefenseSession session = newSession(1L, 3);
        ArrayList<UUID> selected = new ArrayList<>(List.of(REGISTERED, SECOND_REGISTERED));

        session.completeCountdown(selected);
        selected.clear();

        assertEquals(DefensePhase.PREPARATION, session.phase());
        assertTrue(session.participantsFrozen());
        assertEquals(Set.of(REGISTERED, SECOND_REGISTERED), session.registeredParticipants());
        assertEquals(Set.of(REGISTERED, SECOND_REGISTERED), session.effectiveParticipants());
        assertThrows(
                UnsupportedOperationException.class,
                () -> session.registeredParticipants().add(LATE_HELPER));
        assertThrows(
                IllegalStateException.class,
                () -> session.completeCountdown(List.of(REGISTERED)));
    }

    @Test
    void invalidCountdownCompletionIsAtomic() {
        DefenseSession session = newSession(1L, 1);
        DefenseSessionSnapshot before = session.snapshot();

        assertThrows(
                IllegalArgumentException.class,
                () -> session.completeCountdown(List.of()));
        assertEquals(before, session.snapshot());

        assertThrows(
                IllegalArgumentException.class,
                () -> session.completeCountdown(List.of(REGISTERED, SECOND_REGISTERED)));
        assertEquals(before, session.snapshot());
    }

    @Test
    void enforcesWaveTransitionsAndNeverClearsPendingLogicalEnemies() {
        DefenseSession session = preparedSession(1L, 2, REGISTERED);
        session.startWave(3L);

        assertEquals(1, session.currentWave());
        assertEquals(3L, session.pendingEnemies());
        assertEquals(0L, session.aliveEnemies());

        session.spawnPendingEnemies(2L);
        assertFalse(session.recordEnemyDefeated(2L));
        assertEquals(DefensePhase.WAVE_ACTIVE, session.phase());
        assertEquals(1L, session.pendingEnemies());

        session.spawnPendingEnemies(1L);
        session.returnAliveEnemiesToPending(1L);
        assertEquals(1L, session.remainingLogicalEnemies());
        assertEquals(1L, session.pendingEnemies());
        assertEquals(0L, session.aliveEnemies());

        session.spawnPendingEnemies(1L);
        assertTrue(session.recordEnemyDefeated(1L));
        assertEquals(DefensePhase.INTERMISSION, session.phase());
        assertThrows(
                IllegalStateException.class,
                () -> session.recordEnemyDefeated(1L));
    }

    @Test
    void progressesEveryWaveAndEndsInIdempotentVictory() {
        DefenseSession session = preparedSession(1L, 2, REGISTERED);

        for (int wave = 1; wave <= session.totalWaves(); wave++) {
            session.startWave(1L);
            assertEquals(wave, session.currentWave());
            session.spawnPendingEnemies(1L);
            assertTrue(session.recordEnemyDefeated(1L));
            DefensePhase expected = wave == session.totalWaves()
                    ? DefensePhase.VICTORY
                    : DefensePhase.INTERMISSION;
            assertEquals(expected, session.phase());
        }

        DefenseSessionSnapshot victory = session.snapshot();
        assertFalse(session.recordEnemyDefeated(1L));
        assertEquals(victory, session.snapshot());
        assertThrows(IllegalStateException.class, session::abort);
        assertThrows(IllegalStateException.class, () -> session.startWave(1L));
    }

    @Test
    void rejectsIllegalWaveOperationsWithoutChangingState() {
        DefenseSession countdown = newSession(1L, 2);
        assertThrows(IllegalStateException.class, () -> countdown.startWave(1L));

        DefenseSession session = preparedSession(1L, 2, REGISTERED);
        assertThrows(IllegalArgumentException.class, () -> session.startWave(0L));
        session.startWave(2L);
        DefenseSessionSnapshot active = session.snapshot();

        assertThrows(IllegalStateException.class, () -> session.startWave(1L));
        assertThrows(IllegalArgumentException.class, () -> session.spawnPendingEnemies(3L));
        assertThrows(IllegalArgumentException.class, () -> session.recordEnemyDefeated(1L));
        assertThrows(IllegalArgumentException.class, () -> session.returnAliveEnemiesToPending(1L));
        assertEquals(active, session.snapshot());
    }

    @Test
    void enemyCountOverflowIsRejectedAtomically() {
        DefenseSession session = preparedSession(1L, 2, REGISTERED);
        session.startWave(Long.MAX_VALUE);
        DefenseSessionSnapshot before = session.snapshot();

        assertThrows(IllegalArgumentException.class, () -> session.addPendingEnemies(1L));
        assertEquals(before, session.snapshot());

        assertThrows(
                IllegalArgumentException.class,
                () -> session.addEffectiveParticipant(LATE_HELPER, 1L));
        assertEquals(before, session.snapshot());
        assertFalse(session.isEffectiveParticipant(LATE_HELPER));
    }

    @Test
    void lateHelpersIncreaseDifficultyButNeverRewardEligibility() {
        DefenseSession session = preparedSession(1L, 3, REGISTERED);

        assertTrue(session.addEffectiveParticipant(LATE_HELPER, 0L));
        assertFalse(session.isRegisteredParticipant(LATE_HELPER));
        assertTrue(session.isEffectiveParticipant(LATE_HELPER));

        session.startWave(4L);
        assertTrue(session.addEffectiveParticipant(ANOTHER_HELPER, 2L));
        assertEquals(6L, session.pendingEnemies());
        assertFalse(session.addEffectiveParticipant(ANOTHER_HELPER, 2L));
        assertEquals(6L, session.pendingEnemies());
        assertEquals(Set.of(REGISTERED), session.registeredParticipants());
        assertEquals(
                Set.of(REGISTERED, LATE_HELPER, ANOTHER_HELPER),
                session.effectiveParticipants());

        assertThrows(
                IllegalStateException.class,
                () -> session.addEffectiveParticipant(id(99L), 0L));
    }

    @Test
    void effectiveAdjustmentOutsideActiveWaveMustBeZero() {
        DefenseSession session = preparedSession(1L, 2, REGISTERED);
        DefenseSessionSnapshot before = session.snapshot();

        assertThrows(
                IllegalArgumentException.class,
                () -> session.addEffectiveParticipant(LATE_HELPER, 1L));
        assertEquals(before, session.snapshot());
    }

    @Test
    void absenceChecksOnlyTheFrozenRegisteredSet() {
        DefenseSession session = preparedSession(1L, 2, REGISTERED);
        session.addEffectiveParticipant(LATE_HELPER, 0L);

        assertTrue(session.defeatIfNoRegisteredParticipantsPresent(Set.of(LATE_HELPER)));
        assertEquals(DefensePhase.DEFEAT, session.phase());
        assertFalse(session.defeatIfNoRegisteredParticipantsPresent(Set.of()));
        assertThrows(IllegalStateException.class, session::abort);
    }

    @Test
    void registeredParticipantPresencePreventsAbsenceDefeat() {
        DefenseSession session = preparedSession(1L, 2, REGISTERED);

        assertFalse(session.defeatIfNoRegisteredParticipantsPresent(Set.of(REGISTERED)));
        assertEquals(DefensePhase.PREPARATION, session.phase());
    }

    @Test
    void countdownAbsenceDefeatIsExplicitAndIdempotent() {
        DefenseSession session = newSession(1L, 2);

        assertTrue(session.defeatCountdownForNoCandidates());
        DefenseSessionSnapshot defeat = session.snapshot();
        assertFalse(session.defeatCountdownForNoCandidates());
        assertEquals(defeat, session.snapshot());
        assertThrows(
                IllegalStateException.class,
                () -> session.completeCountdown(List.of(REGISTERED)));
    }

    @Test
    void zeroHpDestroysCoreAndEndsInIdempotentDefeat() {
        DefenseSession session = preparedSession(1L, 2, REGISTERED);
        session.startWave(1L);

        assertFalse(session.damageCore(99L));
        assertEquals(1L, session.coreState().currentHitPoints());
        assertTrue(session.damageCore(1L));
        assertEquals(DefensePhase.DEFEAT, session.phase());
        assertTrue(session.coreState().isDestroyed());
        assertEquals(1L, session.pendingEnemies());

        DefenseSessionSnapshot defeat = session.snapshot();
        assertFalse(session.damageCore(1L));
        assertEquals(defeat, session.snapshot());
        assertThrows(IllegalStateException.class, session::enterRecovery);
    }

    @Test
    void abortAndRecoveryAreIndividuallyIdempotentAndMutuallyExclusive() {
        DefenseSession aborted = preparedSession(1L, 2, REGISTERED);
        assertTrue(aborted.abort());
        assertFalse(aborted.abort());
        assertThrows(IllegalStateException.class, aborted::enterRecovery);

        DefenseSession recovery = preparedSession(1L, 2, REGISTERED);
        recovery.startWave(2L);
        recovery.spawnPendingEnemies(1L);
        assertTrue(recovery.enterRecovery());
        assertEquals(1L, recovery.pendingEnemies());
        assertEquals(1L, recovery.aliveEnemies());
        assertFalse(recovery.enterRecovery());
        assertThrows(IllegalStateException.class, recovery::abort);
    }

    @Test
    void recoveryCanCaptureAStillUnregisteredCountdown() {
        DefenseSession session = newSession(1L, 2);

        assertTrue(session.enterRecovery());
        DefenseSessionSnapshot snapshot = session.snapshot();
        assertEquals(DefensePhase.RECOVERY, snapshot.phase());
        assertFalse(snapshot.participantsFrozen());
        assertTrue(snapshot.registeredParticipants().isEmpty());
        assertEquals(snapshot, DefenseSession.restore(snapshot).snapshot());
    }

    @Test
    void snapshotRoundTripIsDeeplyImmutableAndRestoresIndependentState() {
        DefenseSession original = preparedSession(1L, 3, REGISTERED);
        original.startWave(3L);
        original.spawnPendingEnemies(1L);
        original.addEffectiveParticipant(LATE_HELPER, 2L);
        DefenseSessionSnapshot snapshot = original.snapshot();

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.effectiveParticipants().add(ANOTHER_HELPER));

        DefenseSession restored = DefenseSession.restore(snapshot);
        assertEquals(snapshot, restored.snapshot());
        restored.spawnPendingEnemies(1L);

        assertEquals(1L, original.aliveEnemies());
        assertEquals(2L, restored.aliveEnemies());
        assertEquals(snapshot, original.snapshot());
    }

    private static DefenseSession newSession(long stageLevel, int participantLimit) {
        return new DefenseSession(
                id(100L),
                id(200L),
                stageLevel,
                participantLimit,
                CoreState.intact(100L));
    }

    private static DefenseSession preparedSession(
            long stageLevel, int participantLimit, UUID... registered) {
        DefenseSession session = newSession(stageLevel, participantLimit);
        session.completeCountdown(List.of(registered));
        return session;
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
