package io.github.takenoha.towerdefense.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread domain aggregate for one defense event. It owns lifecycle,
 * participant, core, wave, and logical-enemy invariants and has no Paper dependency.
 */
public final class DefenseSession {
    private final UUID eventId;
    private final UUID teamId;
    private final long stageLevel;
    private final int totalWaves;
    private final int participantLimit;
    private final LinkedHashSet<UUID> registeredParticipants;
    private final LinkedHashSet<UUID> effectiveParticipants;

    private DefensePhase phase;
    private int currentWave;
    private boolean participantsFrozen;
    private long pendingEnemies;
    private long aliveEnemies;
    private CoreState coreState;

    /** Creates a session in COUNTDOWN with no participant set frozen yet. */
    public DefenseSession(
            UUID eventId,
            UUID teamId,
            long stageLevel,
            int participantLimit,
            CoreState coreState) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.stageLevel = StageWaveSchedule.requireValidStageLevel(stageLevel);
        if (participantLimit <= 0) {
            throw new IllegalArgumentException("participantLimit must be positive");
        }
        this.participantLimit = participantLimit;
        this.coreState = Objects.requireNonNull(coreState, "coreState");
        if (coreState.isDestroyed()) {
            throw new IllegalArgumentException("a defense session requires a present core");
        }
        totalWaves = StageWaveSchedule.wavesFor(stageLevel);
        registeredParticipants = new LinkedHashSet<>();
        effectiveParticipants = new LinkedHashSet<>();
        phase = DefensePhase.COUNTDOWN;
    }

    private DefenseSession(DefenseSessionSnapshot snapshot) {
        eventId = snapshot.eventId();
        teamId = snapshot.teamId();
        stageLevel = snapshot.stageLevel();
        totalWaves = snapshot.totalWaves();
        participantLimit = snapshot.participantLimit();
        phase = snapshot.phase();
        currentWave = snapshot.currentWave();
        registeredParticipants = new LinkedHashSet<>(snapshot.registeredParticipants());
        effectiveParticipants = new LinkedHashSet<>(snapshot.effectiveParticipants());
        participantsFrozen = snapshot.participantsFrozen();
        pendingEnemies = snapshot.pendingEnemies();
        aliveEnemies = snapshot.aliveEnemies();
        coreState = snapshot.coreState();
    }

    /** Restores an aggregate from a fully validated immutable snapshot. */
    public static DefenseSession restore(DefenseSessionSnapshot snapshot) {
        return new DefenseSession(Objects.requireNonNull(snapshot, "snapshot"));
    }

    public UUID eventId() {
        return eventId;
    }

    public UUID teamId() {
        return teamId;
    }

    public long stageLevel() {
        return stageLevel;
    }

    public int totalWaves() {
        return totalWaves;
    }

    public int participantLimit() {
        return participantLimit;
    }

    public DefensePhase phase() {
        return phase;
    }

    public int currentWave() {
        return currentWave;
    }

    public boolean participantsFrozen() {
        return participantsFrozen;
    }

    public long pendingEnemies() {
        return pendingEnemies;
    }

    public long aliveEnemies() {
        return aliveEnemies;
    }

    public long remainingLogicalEnemies() {
        return Math.addExact(pendingEnemies, aliveEnemies);
    }

    public CoreState coreState() {
        return coreState;
    }

    public boolean isTerminal() {
        return phase.isTerminal();
    }

    public Set<UUID> registeredParticipants() {
        return immutableCopy(registeredParticipants);
    }

    public Set<UUID> effectiveParticipants() {
        return immutableCopy(effectiveParticipants);
    }

    public boolean isRegisteredParticipant(UUID playerId) {
        return registeredParticipants.contains(Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean isEffectiveParticipant(UUID playerId) {
        return effectiveParticipants.contains(Objects.requireNonNull(playerId, "playerId"));
    }

    /**
     * Freezes reward-eligible participants exactly once and enters first-wave
     * preparation. Registered participants are also the initial effective set.
     */
    public void completeCountdown(Collection<UUID> participants) {
        requirePhase(DefensePhase.COUNTDOWN);
        LinkedHashSet<UUID> selected = uuidSet("participants", participants);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("at least one participant is required");
        }
        if (selected.size() > participantLimit) {
            throw new IllegalArgumentException("participant count exceeds participantLimit");
        }

        registeredParticipants.addAll(selected);
        effectiveParticipants.addAll(selected);
        participantsFrozen = true;
        transitionTo(DefensePhase.PREPARATION);
    }

    /** Starts the first or next wave with all logical enemies initially pending. */
    public void startWave(long logicalEnemyCount) {
        requirePositive("logicalEnemyCount", logicalEnemyCount);
        if (phase != DefensePhase.PREPARATION && phase != DefensePhase.INTERMISSION) {
            throw illegalPhase("start a wave");
        }
        if (!participantsFrozen) {
            throw new IllegalStateException("participants must be frozen before a wave starts");
        }
        if (pendingEnemies != 0L || aliveEnemies != 0L) {
            throw new IllegalStateException("the previous wave still has logical enemies");
        }

        int nextWave = currentWave + 1;
        if (nextWave > totalWaves) {
            throw new IllegalStateException("all configured waves have already run");
        }
        currentWave = nextWave;
        pendingEnemies = logicalEnemyCount;
        transitionTo(DefensePhase.WAVE_ACTIVE);
    }

    /** Moves queued logical enemies into the alive count without changing their total. */
    public void spawnPendingEnemies(long count) {
        requirePositive("count", count);
        requirePhase(DefensePhase.WAVE_ACTIVE);
        if (count > pendingEnemies) {
            throw new IllegalArgumentException("count exceeds pendingEnemies");
        }
        long nextAlive = addEnemyCounts(aliveEnemies, count);
        pendingEnemies -= count;
        aliveEnemies = nextAlive;
    }

    /**
     * Records actual logical enemy defeats. The cleared wave advances to
     * INTERMISSION or VICTORY only when both pending and alive counts reach zero.
     *
     * @return true when this call cleared the wave
     */
    public boolean recordEnemyDefeated(long count) {
        requirePositive("count", count);
        if (phase == DefensePhase.VICTORY) {
            return false;
        }
        requirePhase(DefensePhase.WAVE_ACTIVE);
        if (count > aliveEnemies) {
            throw new IllegalArgumentException("count exceeds aliveEnemies");
        }
        aliveEnemies -= count;
        if (pendingEnemies != 0L || aliveEnemies != 0L) {
            return false;
        }

        transitionTo(currentWave == totalWaves
                ? DefensePhase.VICTORY
                : DefensePhase.INTERMISSION);
        return true;
    }

    /**
     * Requeues vanished, stuck, or out-of-bounds physical entities as the same
     * logical enemies so they cannot shorten the wave or issue another reward.
     */
    public void returnAliveEnemiesToPending(long count) {
        requirePositive("count", count);
        requirePhase(DefensePhase.WAVE_ACTIVE);
        if (count > aliveEnemies) {
            throw new IllegalArgumentException("count exceeds aliveEnemies");
        }
        long nextPending = addEnemyCounts(pendingEnemies, count);
        aliveEnemies -= count;
        pendingEnemies = nextPending;
    }

    /** Adds upward difficulty scaling to the active wave's pending queue. */
    public void addPendingEnemies(long count) {
        requirePositive("count", count);
        requirePhase(DefensePhase.WAVE_ACTIVE);
        pendingEnemies = addEnemyCounts(pendingEnemies, count);
    }

    /**
     * Permanently adds a late team member to difficulty scaling without granting
     * reward eligibility. Replaying the same addition is idempotent.
     *
     * @param additionalEnemiesForActiveWave immediate upward count adjustment;
     *     must be zero outside WAVE_ACTIVE
     * @return true if the effective set grew
     */
    public boolean addEffectiveParticipant(
            UUID playerId, long additionalEnemiesForActiveWave) {
        Objects.requireNonNull(playerId, "playerId");
        if (additionalEnemiesForActiveWave < 0L) {
            throw new IllegalArgumentException(
                    "additionalEnemiesForActiveWave must not be negative");
        }
        requireParticipantMutationPhase();
        if (effectiveParticipants.contains(playerId)) {
            return false;
        }
        if (effectiveParticipants.size() >= participantLimit) {
            throw new IllegalStateException("effective participant limit reached");
        }

        long adjustedPending = pendingEnemies;
        if (phase == DefensePhase.WAVE_ACTIVE) {
            adjustedPending = addEnemyCounts(pendingEnemies, additionalEnemiesForActiveWave);
        } else if (additionalEnemiesForActiveWave != 0L) {
            throw new IllegalArgumentException(
                    "an immediate enemy adjustment is only valid during WAVE_ACTIVE");
        }

        effectiveParticipants.add(playerId);
        pendingEnemies = adjustedPending;
        return true;
    }

    /**
     * Applies event-enemy damage. Replaying damage after the same zero-HP defeat is
     * a no-op, preventing duplicate core destruction and defeat handling.
     *
     * @return true if this call destroyed the core and ended the session
     */
    public boolean damageCore(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (phase == DefensePhase.DEFEAT && coreState.isDestroyed()) {
            return false;
        }
        requirePhase(DefensePhase.WAVE_ACTIVE);
        CoreState damaged = coreState.damage(amount);
        if (damaged == coreState) {
            return false;
        }
        coreState = damaged;
        if (!damaged.isDestroyed()) {
            return false;
        }
        transitionTo(DefensePhase.DEFEAT);
        return true;
    }

    /**
     * Ends a post-registration session only when none of the supplied in-range,
     * online players are registered participants. Effective-only helpers do not
     * prevent defeat.
     */
    public boolean defeatIfNoRegisteredParticipantsPresent(
            Collection<UUID> presentPlayers) {
        LinkedHashSet<UUID> present = uuidSet("presentPlayers", presentPlayers);
        if (phase == DefensePhase.DEFEAT) {
            return false;
        }
        if (!participantsFrozen) {
            throw new IllegalStateException("registered participants are not frozen yet");
        }
        requireAbsenceCheckPhase();
        for (UUID registered : registeredParticipants) {
            if (present.contains(registered)) {
                return false;
            }
        }
        transitionTo(DefensePhase.DEFEAT);
        return true;
    }

    /** Ends COUNTDOWN after the runtime's absence grace expires before registration. */
    public boolean defeatCountdownForNoCandidates() {
        if (phase == DefensePhase.DEFEAT) {
            return false;
        }
        requirePhase(DefensePhase.COUNTDOWN);
        transitionTo(DefensePhase.DEFEAT);
        return true;
    }

    /** Voluntarily aborts an in-progress session; replaying the operation is a no-op. */
    public boolean abort() {
        return terminate(DefensePhase.ABORTED);
    }

    /** Marks an in-progress session for technical recovery and rollback. */
    public boolean enterRecovery() {
        return terminate(DefensePhase.RECOVERY);
    }

    /** Creates a deeply immutable, validated persistence snapshot. */
    public DefenseSessionSnapshot snapshot() {
        return new DefenseSessionSnapshot(
                eventId,
                teamId,
                stageLevel,
                totalWaves,
                participantLimit,
                phase,
                currentWave,
                registeredParticipants,
                effectiveParticipants,
                participantsFrozen,
                pendingEnemies,
                aliveEnemies,
                coreState);
    }

    private boolean terminate(DefensePhase terminalPhase) {
        if (!terminalPhase.isTerminal()) {
            throw new IllegalArgumentException("terminalPhase must be terminal");
        }
        if (phase == terminalPhase) {
            return false;
        }
        if (phase.isTerminal()) {
            throw new IllegalStateException(
                    "session already ended as " + phase + "; cannot change to " + terminalPhase);
        }
        transitionTo(terminalPhase);
        return true;
    }

    private void requireParticipantMutationPhase() {
        if (!participantsFrozen) {
            throw new IllegalStateException("participants are not frozen yet");
        }
        if (phase != DefensePhase.PREPARATION
                && phase != DefensePhase.WAVE_ACTIVE
                && phase != DefensePhase.INTERMISSION) {
            throw illegalPhase("add an effective participant");
        }
    }

    private void requireAbsenceCheckPhase() {
        if (phase != DefensePhase.PREPARATION
                && phase != DefensePhase.WAVE_ACTIVE
                && phase != DefensePhase.INTERMISSION) {
            throw illegalPhase("resolve registered-participant absence");
        }
    }

    private void requirePhase(DefensePhase required) {
        if (phase != required) {
            throw illegalPhase("perform an operation requiring " + required);
        }
    }

    private IllegalStateException illegalPhase(String operation) {
        return new IllegalStateException("cannot " + operation + " while phase is " + phase);
    }

    private void transitionTo(DefensePhase next) {
        if (!phase.canTransitionTo(next)) {
            throw new IllegalStateException("illegal defense transition: " + phase + " -> " + next);
        }
        phase = next;
    }

    private static long addEnemyCounts(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("logical enemy count overflow", exception);
        }
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static LinkedHashSet<UUID> uuidSet(
            String name, Collection<UUID> participants) {
        Objects.requireNonNull(participants, name);
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (UUID participant : participants) {
            result.add(Objects.requireNonNull(participant, name + " contains null"));
        }
        return result;
    }

    private static Set<UUID> immutableCopy(Set<UUID> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
