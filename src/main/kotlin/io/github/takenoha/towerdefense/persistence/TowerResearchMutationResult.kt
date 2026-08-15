package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.TeamProgress
import io.github.takenoha.towerdefense.domain.TowerResearch
import java.util.Objects
import kotlin.jvm.JvmRecord

/** Result of an idempotent, team-scoped tower research purchase. */
@JvmRecord
data class TowerResearchMutationResult(
    val outcome: OperationOutcome,
    val progress: TeamProgress,
    val research: TowerResearch,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(progress, "progress")
        Objects.requireNonNull(research, "research")
    }
}
