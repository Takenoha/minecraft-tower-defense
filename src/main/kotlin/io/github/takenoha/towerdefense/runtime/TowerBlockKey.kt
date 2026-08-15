package io.github.takenoha.towerdefense.runtime

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Exact world/block coordinate used to prevent two towers sharing one physical position. */
@JvmRecord
data class TowerBlockKey(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        Objects.requireNonNull(worldId, "worldId")
    }
}
