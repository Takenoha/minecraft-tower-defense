package io.github.takenoha.towerdefense.domain

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Durable team-wide progression values used by the economy and future research slices. */
@JvmRecord
data class TeamProgress(
    val teamId: UUID,
    val highestClearedLevel: Long,
    val unlockedLevel: Long,
    val researchPoints: Long,
) {
    init {
        Objects.requireNonNull(teamId, "teamId")
        if (highestClearedLevel < 0L) {
            throw IllegalArgumentException("highestClearedLevel must be non-negative")
        }
        if (unlockedLevel <= 0L) {
            throw IllegalArgumentException("unlockedLevel must be positive")
        }
        if (researchPoints < 0L) {
            throw IllegalArgumentException("researchPoints must be non-negative")
        }
        val minimumUnlock = if (highestClearedLevel >= StageWaveSchedule.MAX_STAGE_LEVEL) {
            StageWaveSchedule.MAX_STAGE_LEVEL
        } else {
            highestClearedLevel + 1L
        }
        if (unlockedLevel < minimumUnlock) {
            throw IllegalArgumentException(
                "unlockedLevel must include the next level after the highest clear",
            )
        }
    }

    companion object {
        @JvmStatic
        fun initial(teamId: UUID): TeamProgress = TeamProgress(teamId, 0L, 1L, 0L)
    }

    /** Returns the monotonic progression snapshot produced by a stage victory. */
    fun afterVictory(stageLevel: Long): TeamProgress {
        if (stageLevel <= 0L) {
            throw IllegalArgumentException("stageLevel must be positive")
        }
        val nextUnlocked = StageWaveSchedule.nextStageLevel(stageLevel)
            .orElse(stageLevel)
        return TeamProgress(
            teamId,
            maxOf(highestClearedLevel, stageLevel),
            maxOf(unlockedLevel, nextUnlocked),
            researchPoints,
        )
    }
}
