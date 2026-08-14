package io.github.takenoha.towerdefense.persistence

import java.util.Optional

/** Team-scoped point wallets backed by event drops and management payments. */
enum class ResourceType(
    private val itemIdValue: String,
    private val displayNameValue: String,
) {
    DEFENSE_POINTS("defense_shard", "防衛ポイント"),
    ENHANCEMENT_POINTS("enhancement_core", "強化ポイント"),
    ;

    fun itemId(): String = itemIdValue

    fun displayName(): String = displayNameValue

    companion object {
        @JvmStatic
        fun fromItemId(itemId: String?): Optional<ResourceType> =
            values().firstOrNull { it.itemIdValue == itemId }?.let { Optional.of(it) }
                ?: Optional.empty<ResourceType>()

        @JvmStatic
        fun require(value: ResourceType): ResourceType = value
    }
}
