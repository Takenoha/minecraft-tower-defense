package io.github.takenoha.towerdefense.config

/** Operator-controlled evidence flags for the experimental terrain-mutation path. */
@JvmRecord
data class TerrainMutationSettings(
    val requested: Boolean,
    val paperIntegrationVerified: Boolean,
    val recoveryVerified: Boolean,
) {
    companion object {
        /** Returns the fail-closed default used by older configurations and direct constructors. */
        @JvmStatic
        fun disabled(): TerrainMutationSettings = TerrainMutationSettings(false, false, false)
    }
}
