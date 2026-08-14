package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** The stable identity carried by one physical "襲撃の印" item. */
@JvmRecord
data class RaidSealItemIdentity(
    val sealId: UUID,
    val stageLevel: Long,
) {
    init {
        Objects.requireNonNull(sealId, "sealId")
        require(stageLevel > 0L) { "stageLevel must be positive" }
    }
}
