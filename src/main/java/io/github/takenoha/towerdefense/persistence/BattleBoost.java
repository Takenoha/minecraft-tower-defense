package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Cumulative temporary boost for one tower during one defense event. */
public record BattleBoost(
        UUID eventId,
        UUID teamId,
        UUID towerId,
        BattleBoostKind kind,
        int level,
        double multiplier,
        Instant updatedAt) {
    public BattleBoost {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (level <= 0 || !Double.isFinite(multiplier) || multiplier <= 0.0d) {
            throw new IllegalArgumentException("battle boost values are invalid");
        }
    }
}
