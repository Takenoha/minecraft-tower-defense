package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.TeamProgress
import java.util.Objects
import kotlin.jvm.JvmRecord

/** Result of applying a crystal redemption, including the new team balance. */
@JvmRecord
data class ResearchCrystalRedemptionResult(
    val outcome: OperationOutcome,
    val progress: TeamProgress,
    val batch: ResearchCrystalBatch,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(progress, "progress")
        Objects.requireNonNull(batch, "batch")
    }
}
