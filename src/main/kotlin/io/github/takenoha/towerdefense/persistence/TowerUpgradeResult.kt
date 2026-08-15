package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import java.util.Optional
import kotlin.jvm.JvmRecord

/** Result of applying one idempotent tower upgrade operation. */
@JvmRecord
data class TowerUpgradeResult(
    val outcome: OperationOutcome,
    val tower: Optional<TowerRecord>,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(tower, "tower")
    }
}
