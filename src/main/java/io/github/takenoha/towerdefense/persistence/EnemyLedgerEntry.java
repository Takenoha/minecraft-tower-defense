package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted identity and implementation-owned snapshot for one event enemy. */
public record EnemyLedgerEntry(
        UUID eventId,
        UUID enemyId,
        UUID entityId,
        String enemyType,
        int waveIndex,
        EnemyStatus status,
        String snapshot,
        int snapshotVersion,
        Instant updatedAt) {
    public EnemyLedgerEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(enemyId, "enemyId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(enemyType, "enemyType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (enemyType.isBlank()) {
            throw new IllegalArgumentException("enemyType must not be blank");
        }
        if (waveIndex <= 0) {
            throw new IllegalArgumentException("waveIndex must be positive");
        }
        if (snapshotVersion <= 0) {
            throw new IllegalArgumentException("snapshotVersion must be positive");
        }
    }
}
