package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import java.util.Objects;
import java.util.UUID;

/** Immutable terminal totals used for the player-facing settlement message. */
public record TeamResourceSettlement(
        UUID eventId,
        UUID teamId,
        DefensePhase phase,
        long defensePoints,
        long enhancementPoints) {
    public TeamResourceSettlement {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(phase, "phase");
        if (defensePoints < 0L || enhancementPoints < 0L) {
            throw new IllegalArgumentException("settlement quantities must not be negative");
        }
    }
}
