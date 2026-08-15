package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import kotlin.jvm.JvmRecord

/** Atomic result of a battle-boost purchase and its battle-funds spend. */
@JvmRecord
data class BattleBoostMutationResult(
    val outcome: OperationOutcome,
    val boost: BattleBoost,
    val funds: BattleFunds,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(boost, "boost")
        Objects.requireNonNull(funds, "funds")
    }
}
