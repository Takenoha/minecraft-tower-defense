package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import java.util.Objects;
import java.util.UUID;

/** Read-only status exposed to administrator commands. */
public record DefenseRuntimeStatus(
        UUID eventId,
        UUID teamId,
        long stageLevel,
        DefensePhase phase,
        int currentWave,
        int totalWaves,
        long pendingEnemies,
        long aliveEnemies,
        long coreHitPoints,
        long coreMaximumHitPoints,
        boolean ending,
        String persistenceFailure) {
    public DefenseRuntimeStatus {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(phase, "phase");
    }
}

