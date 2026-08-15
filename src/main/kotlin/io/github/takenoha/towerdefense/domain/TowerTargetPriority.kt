package io.github.takenoha.towerdefense.domain

import java.util.Locale

/** Stable target-selection modes persisted with each installed tower. */
enum class TowerTargetPriority(
    private val idValue: String,
    private val displayNameValue: String,
) {
    CORE_NEAREST("core_nearest", "コアに近い"),
    NEAREST("nearest", "距離が近い"),
    HEALTH_HIGH("health_high", "HPが高い"),
    HEALTH_LOW("health_low", "HPが低い"),
    BOSS("boss", "ボス優先"),
    ;

    fun id(): String = idValue

    fun displayName(): String = displayNameValue

    companion object {
        @JvmStatic
        fun fromId(id: String): TowerTargetPriority {
            values().firstOrNull { it.idValue == id.lowercase(Locale.ROOT) }?.let { return it }
            throw IllegalArgumentException("Unknown tower target priority: $id")
        }
    }
}
