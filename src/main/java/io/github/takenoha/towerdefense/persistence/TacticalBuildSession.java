package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalTerminalResult;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Complete durable tactical session state used for restart recovery. */
public record TacticalBuildSession(
        UUID tacticalSessionId,
        UUID startOperationId,
        Optional<UUID> defenseId,
        UUID teamId,
        int stage,
        long seed,
        int generatorVersion,
        TacticalBuildSessionState state,
        Optional<TacticalBuildDefinition> selectedDefinition,
        int highestUnlockedTier,
        Optional<TacticalTerminalResult> terminalResult,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> terminalAt) {
    public TacticalBuildSession {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        Objects.requireNonNull(startOperationId, "startOperationId");
        defenseId = Objects.requireNonNull(defenseId, "defenseId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(state, "state");
        selectedDefinition = Objects.requireNonNull(selectedDefinition, "selectedDefinition");
        terminalResult = Objects.requireNonNull(terminalResult, "terminalResult");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        terminalAt = Objects.requireNonNull(terminalAt, "terminalAt");
        if (stage <= 0 || generatorVersion <= 0) {
            throw new IllegalArgumentException("stage and generatorVersion must be positive");
        }
        if (highestUnlockedTier < 0 || highestUnlockedTier > 6) {
            throw new IllegalArgumentException("highestUnlockedTier must be between 0 and 6");
        }
        if (state == TacticalBuildSessionState.TERMINAL != terminalResult.isPresent()) {
            throw new IllegalArgumentException("terminal result must match session state");
        }
        if (terminalAt.isPresent() != terminalResult.isPresent()) {
            throw new IllegalArgumentException("terminal timestamp must match terminal result");
        }
        if ((state == TacticalBuildSessionState.SELECTED
                || state == TacticalBuildSessionState.ACTIVE
                || state == TacticalBuildSessionState.TERMINAL)
                && selectedDefinition.isEmpty()) {
            throw new IllegalArgumentException("selected state requires a definition snapshot");
        }
    }
}
