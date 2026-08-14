package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerTargetPriority
import io.github.takenoha.towerdefense.domain.TowerType
import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Stable identity carried by one uninstalled tower item. */
@JvmRecord
data class TowerItemIdentity(
    val towerId: UUID,
    val type: TowerType,
    val individualLevel: Int,
    val targetPriority: TowerTargetPriority,
) {
    init {
        Objects.requireNonNull(towerId, "towerId")
        Objects.requireNonNull(type, "type")
        Objects.requireNonNull(targetPriority, "targetPriority")
        require(individualLevel > 0) { "individualLevel must be positive" }
    }

    /** Backward-compatible constructor for items using the default target priority. */
    constructor(towerId: UUID, type: TowerType, individualLevel: Int) :
        this(towerId, type, individualLevel, TowerTargetPriority.CORE_NEAREST)
}
