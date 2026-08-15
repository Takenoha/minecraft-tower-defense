package io.github.takenoha.towerdefense.runtime

import java.util.Objects
import kotlin.jvm.JvmRecord

/** Paper-independent facts used to authorize one event-enemy block action. */
@JvmRecord
data class TerrainMutationInput(
    val currentMaterialKey: String,
    val currentInventoryHolder: Boolean,
    val currentCore: Boolean,
    val currentTileState: Boolean,
    val targetMaterialKey: String,
) {
    /** Keeps the original four-fact constructor source-compatible for non-tile callers. */
    constructor(
        currentMaterialKey: String,
        currentInventoryHolder: Boolean,
        currentCore: Boolean,
        targetMaterialKey: String,
    ) : this(currentMaterialKey, currentInventoryHolder, currentCore, false, targetMaterialKey)

    init {
        requireMaterialKey(currentMaterialKey, "currentMaterialKey")
        requireMaterialKey(targetMaterialKey, "targetMaterialKey")
    }

    companion object {
        private fun requireMaterialKey(value: String, name: String): String {
            Objects.requireNonNull(value, name)
            if (value.isBlank()) {
                throw IllegalArgumentException("$name must not be blank")
            }
            return value
        }
    }
}
