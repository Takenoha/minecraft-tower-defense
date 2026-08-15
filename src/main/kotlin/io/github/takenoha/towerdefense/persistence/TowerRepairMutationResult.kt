package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import kotlin.jvm.JvmRecord

/** Atomic result of a battle-funds tower repair. */
@JvmRecord
data class TowerRepairMutationResult(
    val outcome: OperationOutcome,
    val durability: TowerDurability,
    val funds: BattleFunds,
) {
    init {
        Objects.requireNonNull(outcome, "outcome")
        Objects.requireNonNull(durability, "durability")
        Objects.requireNonNull(funds, "funds")
    }
}
