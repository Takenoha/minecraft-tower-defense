package io.github.takenoha.towerdefense.tactical

import java.util.Objects
import kotlin.jvm.JvmRecord

/** One stable candidate slot shown before a defense starts. */
@JvmRecord
data class TacticalCandidate(
    val slot: Int,
    val definition: TacticalBuildDefinition,
) {
    init {
        if (slot < 0 || slot > 2) {
            throw IllegalArgumentException("candidate slot must be between 0 and 2")
        }
        Objects.requireNonNull(definition, "definition")
    }
}
