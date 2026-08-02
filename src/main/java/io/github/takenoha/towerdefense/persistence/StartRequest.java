package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable input to the atomic session-create and global-lock operation. */
public record StartRequest(
        DefenseSessionSnapshot session,
        UUID coreId,
        String configSnapshot,
        int configVersion,
        Instant startedAt) {
    public StartRequest {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(configSnapshot, "configSnapshot");
        Objects.requireNonNull(startedAt, "startedAt");
        if (session.phase() != DefensePhase.COUNTDOWN) {
            throw new IllegalArgumentException("A new session must begin in COUNTDOWN");
        }
        if (!session.coreState().present()) {
            throw new IllegalArgumentException("A new session requires a present core");
        }
        if (configVersion <= 0) {
            throw new IllegalArgumentException("configVersion must be positive");
        }
    }
}
