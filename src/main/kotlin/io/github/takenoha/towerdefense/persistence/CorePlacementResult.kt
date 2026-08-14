package io.github.takenoha.towerdefense.persistence

import kotlin.jvm.JvmRecord

/** Result of applying a durable public core placement. */
@JvmRecord
data class CorePlacementResult(
    val placement: CorePlacement,
    val core: CoreRecord,
)
