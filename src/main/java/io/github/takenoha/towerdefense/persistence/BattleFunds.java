package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable team-shared currency account scoped to one defense event. */
public record BattleFunds(
        UUID eventId,
        UUID teamId,
        long balance,
        long totalEarned,
        long totalSpent,
        BattleFundsState state,
        Instant updatedAt) {
    public BattleFunds {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (balance < 0L || totalEarned < 0L || totalSpent < 0L) {
            throw new IllegalArgumentException("battle funds totals must not be negative");
        }
        if (totalSpent > totalEarned) {
            throw new IllegalArgumentException("totalSpent cannot exceed totalEarned");
        }
        if (state == BattleFundsState.SETTLED && balance != 0L) {
            throw new IllegalArgumentException("settled battle funds must have zero balance");
        }
    }
}
