package io.github.takenoha.towerdefense.runtime

import java.util.Locale
import java.util.Objects
import kotlin.jvm.JvmRecord

/** One conservative, one-block builder placement selected from a world snapshot. */
@JvmRecord
data class EnemyBridgePlan(
    val targetMaterialKey: String,
) {
    init {
        Objects.requireNonNull(targetMaterialKey, "targetMaterialKey")
        if (targetMaterialKey.isBlank()) {
            throw IllegalArgumentException("targetMaterialKey must not be blank")
        }
        val normalized = targetMaterialKey.lowercase(Locale.ROOT)
        if (normalized == "minecraft:air" ||
            normalized == "minecraft:cave_air" ||
            normalized == "minecraft:void_air"
        ) {
            throw IllegalArgumentException("a bridge target must not be air")
        }
        if (TerrainMutationPolicy.isRequiredMaterial(targetMaterialKey)) {
            throw IllegalArgumentException(
                "a bridge target must not be a required protected material",
            )
        }
    }
}
