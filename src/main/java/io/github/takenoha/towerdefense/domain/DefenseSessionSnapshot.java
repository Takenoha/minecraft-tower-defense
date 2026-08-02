package io.github.takenoha.towerdefense.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A deeply immutable persistence boundary for {@link DefenseSession}. The record
 * validates its complete state so invalid rows cannot enter the domain aggregate.
 */
public record DefenseSessionSnapshot(
        UUID eventId,
        UUID teamId,
        long stageLevel,
        int totalWaves,
        int participantLimit,
        DefensePhase phase,
        int currentWave,
        Set<UUID> registeredParticipants,
        Set<UUID> effectiveParticipants,
        boolean participantsFrozen,
        long pendingEnemies,
        long aliveEnemies,
        CoreState coreState) {

    public DefenseSessionSnapshot {
        eventId = Objects.requireNonNull(eventId, "eventId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        phase = Objects.requireNonNull(phase, "phase");
        coreState = Objects.requireNonNull(coreState, "coreState");
        registeredParticipants = immutableUuidSet(
                "registeredParticipants", registeredParticipants);
        effectiveParticipants = immutableUuidSet(
                "effectiveParticipants", effectiveParticipants);

        StageWaveSchedule.requireValidStageLevel(stageLevel);
        if (totalWaves != StageWaveSchedule.wavesFor(stageLevel)) {
            throw new IllegalArgumentException(
                    "totalWaves does not match the stage wave schedule");
        }
        if (participantLimit <= 0) {
            throw new IllegalArgumentException("participantLimit must be positive");
        }
        if (registeredParticipants.size() > participantLimit
                || effectiveParticipants.size() > participantLimit) {
            throw new IllegalArgumentException("participant count exceeds participantLimit");
        }
        if (!effectiveParticipants.containsAll(registeredParticipants)) {
            throw new IllegalArgumentException(
                    "effectiveParticipants must include all registeredParticipants");
        }
        if (currentWave < 0 || currentWave > totalWaves) {
            throw new IllegalArgumentException("currentWave is outside the stage wave range");
        }
        if (pendingEnemies < 0L || aliveEnemies < 0L) {
            throw new IllegalArgumentException("logical enemy counts must not be negative");
        }
        try {
            Math.addExact(pendingEnemies, aliveEnemies);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("logical enemy count overflow", exception);
        }

        validateParticipantShape(
                phase,
                participantsFrozen,
                registeredParticipants,
                effectiveParticipants);
        validatePhaseShape(
                phase,
                currentWave,
                totalWaves,
                pendingEnemies,
                aliveEnemies,
                participantsFrozen,
                coreState);
    }

    private static Set<UUID> immutableUuidSet(String name, Set<UUID> source) {
        Objects.requireNonNull(source, name);
        LinkedHashSet<UUID> copy = new LinkedHashSet<>();
        for (UUID participant : source) {
            copy.add(Objects.requireNonNull(participant, name + " contains null"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void validateParticipantShape(
            DefensePhase phase,
            boolean participantsFrozen,
            Set<UUID> registeredParticipants,
            Set<UUID> effectiveParticipants) {
        if (participantsFrozen) {
            if (registeredParticipants.isEmpty()) {
                throw new IllegalArgumentException(
                        "a frozen participant set must contain at least one player");
            }
            return;
        }
        if (!registeredParticipants.isEmpty() || !effectiveParticipants.isEmpty()) {
            throw new IllegalArgumentException(
                    "participants cannot exist before registration is frozen");
        }
        if (phase != DefensePhase.COUNTDOWN
                && phase != DefensePhase.DEFEAT
                && phase != DefensePhase.ABORTED
                && phase != DefensePhase.RECOVERY) {
            throw new IllegalArgumentException("this phase requires frozen participants");
        }
    }

    private static void validatePhaseShape(
            DefensePhase phase,
            int currentWave,
            int totalWaves,
            long pendingEnemies,
            long aliveEnemies,
            boolean participantsFrozen,
            CoreState coreState) {
        if (!phase.isTerminal() && coreState.isDestroyed()) {
            throw new IllegalArgumentException("a non-terminal session requires a present core");
        }
        if (currentWave == 0 && (pendingEnemies != 0L || aliveEnemies != 0L)) {
            throw new IllegalArgumentException("enemy counts require an active or completed wave");
        }
        if (!participantsFrozen
                && (currentWave != 0 || pendingEnemies != 0L || aliveEnemies != 0L)) {
            throw new IllegalArgumentException(
                    "an unfrozen session cannot have wave or logical-enemy progress");
        }

        switch (phase) {
            case COUNTDOWN -> requireShape(
                    !participantsFrozen
                            && currentWave == 0
                            && pendingEnemies == 0L
                            && aliveEnemies == 0L,
                    "COUNTDOWN must precede participant and wave registration");
            case PREPARATION -> requireShape(
                    participantsFrozen
                            && currentWave == 0
                            && pendingEnemies == 0L
                            && aliveEnemies == 0L,
                    "PREPARATION must precede the first wave");
            case WAVE_ACTIVE -> requireShape(
                    participantsFrozen
                            && currentWave >= 1
                            && (pendingEnemies != 0L || aliveEnemies != 0L),
                    "WAVE_ACTIVE requires a current wave with logical enemies");
            case INTERMISSION -> requireShape(
                    participantsFrozen
                            && currentWave >= 1
                            && currentWave < totalWaves
                            && pendingEnemies == 0L
                            && aliveEnemies == 0L,
                    "INTERMISSION requires a cleared non-final wave");
            case VICTORY -> requireShape(
                    participantsFrozen
                            && currentWave == totalWaves
                            && pendingEnemies == 0L
                            && aliveEnemies == 0L
                            && !coreState.isDestroyed(),
                    "VICTORY requires a cleared final wave and a surviving core");
            case DEFEAT, ABORTED, RECOVERY -> {
                // End states retain in-flight counts for deterministic cleanup/recovery.
            }
        }
    }

    private static void requireShape(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
