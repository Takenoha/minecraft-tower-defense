package io.github.takenoha.towerdefense.domain

import java.util.Locale

/** Stable tower kind identifiers stored in the item PDC and SQLite rows. */
enum class TowerType(
    private val idValue: String,
    private val displayNameValue: String,
) {
    ARROW("arrow", "アロー"),
    CANNON("cannon", "キャノン"),
    FROST("frost", "フロスト"),
    LIGHTNING("lightning", "ライトニング"),
    SUPPORT("support", "サポート"),
    SNIPER("sniper", "スナイパー"),
    FLAME("flame", "フレイム"),
    ;

    fun id(): String = idValue

    fun displayName(): String = displayNameValue

    companion object {
        @JvmStatic
        fun fromId(id: String): TowerType {
            values().firstOrNull { it.idValue == id.lowercase(Locale.ROOT) }?.let { return it }
            throw IllegalArgumentException("Unknown tower type: $id")
        }
    }
}
