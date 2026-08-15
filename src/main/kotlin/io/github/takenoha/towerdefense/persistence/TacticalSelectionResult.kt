package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.tactical.TacticalBuildSelectionView
import java.util.Objects
import kotlin.jvm.JvmRecord

/** Result of an operation-UUID protected owner selection. */
@JvmRecord
data class TacticalSelectionResult(
    val outcome: OperationOutcome,
    val selection: TacticalBuildSelectionView,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(selection, "selection")
    }
}
