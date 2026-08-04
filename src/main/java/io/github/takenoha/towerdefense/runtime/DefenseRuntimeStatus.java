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
        int coreAttackers,
        long coreAttackCount,
        boolean ending,
        String persistenceFailure,
        EnemyPathMetrics.Snapshot pathMetrics) {
    /** Keeps status construction source-compatible before core attack observation was exposed. */
    public DefenseRuntimeStatus(
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
            String persistenceFailure,
            EnemyPathMetrics.Snapshot pathMetrics) {
        this(
                eventId,
                teamId,
                stageLevel,
                phase,
                currentWave,
                totalWaves,
                pendingEnemies,
                aliveEnemies,
                coreHitPoints,
                coreMaximumHitPoints,
                0,
                0L,
                ending,
                persistenceFailure,
                pathMetrics);
    }

    public DefenseRuntimeStatus {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(pathMetrics, "pathMetrics");
        if (coreAttackers < 0) {
            throw new IllegalArgumentException("coreAttackers must not be negative");
        }
        if (coreAttackCount < 0L) {
            throw new IllegalArgumentException("coreAttackCount must not be negative");
        }
    }
}
