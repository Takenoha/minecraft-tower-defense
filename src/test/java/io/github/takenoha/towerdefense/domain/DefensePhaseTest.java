package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DefensePhaseTest {
    @Test
    void onlyEndStatesAreTerminal() {
        assertFalse(DefensePhase.COUNTDOWN.isTerminal());
        assertFalse(DefensePhase.PREPARATION.isTerminal());
        assertFalse(DefensePhase.WAVE_ACTIVE.isTerminal());
        assertFalse(DefensePhase.INTERMISSION.isTerminal());
        assertTrue(DefensePhase.VICTORY.isTerminal());
        assertTrue(DefensePhase.DEFEAT.isTerminal());
        assertTrue(DefensePhase.ABORTED.isTerminal());
        assertTrue(DefensePhase.RECOVERY.isTerminal());
    }

    @Test
    void transitionMatrixRejectsSkippedAndReversedPhases() {
        assertTrue(DefensePhase.COUNTDOWN.canTransitionTo(DefensePhase.PREPARATION));
        assertTrue(DefensePhase.COUNTDOWN.canTransitionTo(DefensePhase.DEFEAT));
        assertFalse(DefensePhase.COUNTDOWN.canTransitionTo(DefensePhase.WAVE_ACTIVE));

        assertTrue(DefensePhase.PREPARATION.canTransitionTo(DefensePhase.WAVE_ACTIVE));
        assertFalse(DefensePhase.PREPARATION.canTransitionTo(DefensePhase.INTERMISSION));

        assertTrue(DefensePhase.WAVE_ACTIVE.canTransitionTo(DefensePhase.INTERMISSION));
        assertTrue(DefensePhase.WAVE_ACTIVE.canTransitionTo(DefensePhase.VICTORY));
        assertFalse(DefensePhase.WAVE_ACTIVE.canTransitionTo(DefensePhase.PREPARATION));

        assertTrue(DefensePhase.INTERMISSION.canTransitionTo(DefensePhase.WAVE_ACTIVE));
        assertFalse(DefensePhase.INTERMISSION.canTransitionTo(DefensePhase.VICTORY));

        assertTrue(DefensePhase.VICTORY.canTransitionTo(DefensePhase.VICTORY));
        assertFalse(DefensePhase.VICTORY.canTransitionTo(DefensePhase.RECOVERY));
        assertFalse(DefensePhase.DEFEAT.canTransitionTo(DefensePhase.ABORTED));
        assertFalse(DefensePhase.COUNTDOWN.canTransitionTo(DefensePhase.COUNTDOWN));
    }
}
