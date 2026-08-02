package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Complete durable event record, including immutable start and configuration data. */
public record StoredDefenseEvent(
        DefenseSessionSnapshot session,
        UUID coreId,
        UUID coreWorldId,
        int coreBlockX,
        int coreBlockY,
        int coreBlockZ,
        long startCoreHitPoints,
        long startCoreMaximumHitPoints,
        String configSnapshot,
        int configVersion,
        Instant startedAt,
        Instant updatedAt,
        long revision,
        Optional<UUID> terminalOperationId,
        Optional<Instant> terminalAt) {
    public StoredDefenseEvent {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(coreWorldId, "coreWorldId");
        Objects.requireNonNull(configSnapshot, "configSnapshot");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        terminalOperationId = Objects.requireNonNull(
                terminalOperationId, "terminalOperationId");
        terminalAt = Objects.requireNonNull(terminalAt, "terminalAt");
        if (startCoreMaximumHitPoints <= 0L
                || startCoreHitPoints <= 0L
                || startCoreHitPoints > startCoreMaximumHitPoints) {
            throw new IllegalArgumentException("The starting core HP snapshot is invalid");
        }
        if (configVersion <= 0) {
            throw new IllegalArgumentException("configVersion must be positive");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (terminalOperationId.isPresent() != terminalAt.isPresent()) {
            throw new IllegalArgumentException(
                    "terminalOperationId and terminalAt must either both exist or both be absent");
        }
    }
}
