package io.github.takenoha.towerdefense.runtime

import io.github.takenoha.towerdefense.domain.DefensePhase
import io.github.takenoha.towerdefense.domain.WaveMutationSnapshot
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Read-only status exposed to administrator commands. */
@JvmRecord
data class DefenseRuntimeStatus(
    val eventId: UUID,
    val teamId: UUID,
    val stageLevel: Long,
    val phase: DefensePhase,
    val currentWave: Int,
    val totalWaves: Int,
    val waveMutation: WaveMutationSnapshot,
    val pendingEnemies: Long,
    val aliveEnemies: Long,
    val coreHitPoints: Long,
    val coreMaximumHitPoints: Long,
    val coreAttackers: Int,
    val coreAttackCount: Long,
    val ending: Boolean,
    val persistenceFailure: String?,
    val pathMetrics: EnemyPathMetrics.Snapshot,
) {
    /** Keeps status construction source-compatible before core attack observation was exposed. */
    constructor(
        eventId: UUID,
        teamId: UUID,
        stageLevel: Long,
        phase: DefensePhase,
        currentWave: Int,
        totalWaves: Int,
        pendingEnemies: Long,
        aliveEnemies: Long,
        coreHitPoints: Long,
        coreMaximumHitPoints: Long,
        coreAttackers: Int,
        coreAttackCount: Long,
        ending: Boolean,
        persistenceFailure: String?,
        pathMetrics: EnemyPathMetrics.Snapshot,
    ) : this(
        eventId,
        teamId,
        stageLevel,
        phase,
        currentWave,
        totalWaves,
        WaveMutationSnapshot.none(),
        pendingEnemies,
        aliveEnemies,
        coreHitPoints,
        coreMaximumHitPoints,
        coreAttackers,
        coreAttackCount,
        ending,
        persistenceFailure,
        pathMetrics,
    )

    /** Keeps status construction source-compatible before core attack observation was exposed. */
    constructor(
        eventId: UUID,
        teamId: UUID,
        stageLevel: Long,
        phase: DefensePhase,
        currentWave: Int,
        totalWaves: Int,
        pendingEnemies: Long,
        aliveEnemies: Long,
        coreHitPoints: Long,
        coreMaximumHitPoints: Long,
        ending: Boolean,
        persistenceFailure: String?,
        pathMetrics: EnemyPathMetrics.Snapshot,
    ) : this(
        eventId,
        teamId,
        stageLevel,
        phase,
        currentWave,
        totalWaves,
        WaveMutationSnapshot.none(),
        pendingEnemies,
        aliveEnemies,
        coreHitPoints,
        coreMaximumHitPoints,
        0,
        0L,
        ending,
        persistenceFailure,
        pathMetrics,
    )

    init {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(phase, "phase")
        Objects.requireNonNull(waveMutation, "waveMutation")
        Objects.requireNonNull(pathMetrics, "pathMetrics")
        if (coreAttackers < 0) {
            throw IllegalArgumentException("coreAttackers must not be negative")
        }
        if (coreAttackCount < 0L) {
            throw IllegalArgumentException("coreAttackCount must not be negative")
        }
    }
}
