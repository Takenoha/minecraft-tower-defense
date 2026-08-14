package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.Objects
import org.bukkit.Particle
import kotlin.jvm.JvmRecord

/** Immutable vanilla-particle definition for one tower kind. */
@JvmRecord
data class TowerEffectDefinition(
    val type: TowerType,
    val trail: Particle,
    val hit: Particle,
    val buff: Particle,
    val trailCount: Int,
    val hitCount: Int,
    val buffCount: Int,
) {
    init {
        Objects.requireNonNull(type, "type")
        Objects.requireNonNull(trail, "trail")
        Objects.requireNonNull(hit, "hit")
        Objects.requireNonNull(buff, "buff")
        if (trailCount <= 0 || hitCount <= 0 || buffCount <= 0) {
            throw IllegalArgumentException("particle counts must be positive")
        }
    }
}
