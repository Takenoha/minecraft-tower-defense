package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One append-only, operation-UUID protected lifecycle transition. */
public record EventTransitionRecord(
        long sequence,
        UUID eventId,
        UUID operationId,
        DefensePhase fromPhase,
        DefensePhase toPhase,
        int waveIndex,
        long pendingEnemies,
        long aliveEnemies,
        Instant occurredAt) {
    public EventTransitionRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(fromPhase, "fromPhase");
        Objects.requireNonNull(toPhase, "toPhase");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (waveIndex < 0 || pendingEnemies < 0L || aliveEnemies < 0L) {
            throw new IllegalArgumentException("Transition counters must not be negative");
        }
    }
}
