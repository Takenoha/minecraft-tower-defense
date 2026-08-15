package io.github.takenoha.towerdefense.domain

import kotlin.jvm.JvmRecord

/** Paper-independent facts supplied to the role-aware navigation planner. */
@JvmRecord
data class EnemyPathContext(
    val directPathAvailable: Boolean,
    val protectedObstacle: Boolean,
    val breakableObstacle: Boolean,
    val buildableGap: Boolean,
    val consecutivePathFailures: Int,
) {
    init {
        if (consecutivePathFailures < 0) {
            throw IllegalArgumentException("consecutivePathFailures must not be negative")
        }
    }
}
