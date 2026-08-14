package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.EnumMap
import java.util.Objects
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World

/** Bounded vanilla-particle rendering for tower attacks and support pulses. */
class TowerAttackEffects private constructor() {
    companion object {
        const val MAX_EFFECTS_PER_ATTACK: Int = 32
        private const val MAX_TRACE_POINTS: Int = 12
        private const val TRACE_STEP: Double = 1.5
        private val DEFINITIONS: Map<TowerType, TowerEffectDefinition> = definitions()

        @JvmStatic
        fun definition(type: TowerType): TowerEffectDefinition {
            val requiredType = Objects.requireNonNull(type, "type")
            return Objects.requireNonNull(DEFINITIONS[requiredType], "definition")!!
        }

        @JvmStatic
        fun newBudget(): Budget = Budget.create(MAX_EFFECTS_PER_ATTACK)

        @JvmStatic
        fun renderAttack(
            type: TowerType,
            origin: Location,
            target: Location,
            budget: Budget,
        ) {
            Objects.requireNonNull(origin, "origin")
            Objects.requireNonNull(target, "target")
            Objects.requireNonNull(budget, "budget")
            if (!sameWorld(origin, target)) {
                return
            }
            val effect = definition(type)
            val world = origin.getWorld() ?: return
            val distance = origin.distance(target)
            val points = maxOf(1, minOf(MAX_TRACE_POINTS, kotlin.math.ceil(distance / TRACE_STEP).toInt()))
            for (index in 1..points) {
                val fraction = index.toDouble() / points
                val point = origin.clone().add(
                    (target.getX() - origin.getX()) * fraction,
                    (target.getY() - origin.getY()) * fraction,
                    (target.getZ() - origin.getZ()) * fraction,
                )
                if (!budget.claim()) {
                    return
                }
                spawnParticle(
                    world,
                    effect.trail,
                    point,
                    effect.trailCount,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                )
            }
        }

        @JvmStatic
        fun renderHit(type: TowerType, target: Location, budget: Budget) {
            Objects.requireNonNull(target, "target")
            Objects.requireNonNull(budget, "budget")
            val world = target.getWorld() ?: return
            if (!budget.claim()) {
                return
            }
            val effect = definition(type)
            spawnParticle(
                world,
                effect.hit,
                target,
                effect.hitCount,
                0.25,
                0.35,
                0.25,
                0.0,
            )
        }

        @JvmStatic
        fun renderBuff(
            type: TowerType,
            source: Location,
            target: Location,
            budget: Budget,
        ) {
            Objects.requireNonNull(source, "source")
            Objects.requireNonNull(target, "target")
            Objects.requireNonNull(budget, "budget")
            if (!sameWorld(source, target)) {
                return
            }
            val effect = definition(type)
            val world = source.getWorld() ?: return
            if (!budget.claim()) {
                return
            }
            spawnParticle(
                world,
                effect.buff,
                target,
                effect.buffCount,
                0.35,
                0.5,
                0.35,
                0.0,
            )
            if (!budget.claim()) {
                return
            }
            spawnParticle(
                world,
                effect.buff,
                source,
                maxOf(1, effect.buffCount / 2),
                0.2,
                0.35,
                0.2,
                0.0,
            )
        }

        /** Returns the payload required by Paper for particles that are not data-free. */
        @JvmStatic
        fun particleDataFor(particle: Particle): Any? {
            Objects.requireNonNull(particle, "particle")
            return if (particle == Particle.FLASH) Color.WHITE else null
        }

        private fun spawnParticle(
            world: World,
            particle: Particle,
            location: Location,
            count: Int,
            offsetX: Double,
            offsetY: Double,
            offsetZ: Double,
            extra: Double,
        ) {
            val data = particleDataFor(particle)
            if (data == null) {
                world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra)
            } else {
                world.spawnParticle(
                    particle,
                    location,
                    count,
                    offsetX,
                    offsetY,
                    offsetZ,
                    extra,
                    data,
                )
            }
        }

        private fun sameWorld(first: Location, second: Location): Boolean =
            first.getWorld() != null &&
                second.getWorld() != null &&
                first.getWorld() == second.getWorld()

        private fun definitions(): Map<TowerType, TowerEffectDefinition> {
            val definitions = EnumMap<TowerType, TowerEffectDefinition>(TowerType::class.java)
            definitions[TowerType.ARROW] = TowerEffectDefinition(
                TowerType.ARROW,
                Particle.CRIT,
                Particle.DAMAGE_INDICATOR,
                Particle.ENCHANTED_HIT,
                1,
                3,
                2,
            )
            definitions[TowerType.CANNON] = TowerEffectDefinition(
                TowerType.CANNON,
                Particle.SMOKE,
                Particle.EXPLOSION,
                Particle.CLOUD,
                2,
                1,
                2,
            )
            definitions[TowerType.FROST] = TowerEffectDefinition(
                TowerType.FROST,
                Particle.SNOWFLAKE,
                Particle.CLOUD,
                Particle.SNOWFLAKE,
                3,
                4,
                3,
            )
            definitions[TowerType.LIGHTNING] = TowerEffectDefinition(
                TowerType.LIGHTNING,
                Particle.ELECTRIC_SPARK,
                Particle.FLASH,
                Particle.ELECTRIC_SPARK,
                2,
                1,
                3,
            )
            definitions[TowerType.SUPPORT] = TowerEffectDefinition(
                TowerType.SUPPORT,
                Particle.ENCHANT,
                Particle.HEART,
                Particle.ENCHANT,
                2,
                1,
                5,
            )
            definitions[TowerType.SNIPER] = TowerEffectDefinition(
                TowerType.SNIPER,
                Particle.END_ROD,
                Particle.SONIC_BOOM,
                Particle.END_ROD,
                1,
                1,
                2,
            )
            definitions[TowerType.FLAME] = TowerEffectDefinition(
                TowerType.FLAME,
                Particle.FLAME,
                Particle.LAVA,
                Particle.FLAME,
                3,
                3,
                3,
            )
            return definitions.toMap()
        }
    }

    /** Per-attack cap shared by a tower's trail, hit effects, and support pulse. */
    class Budget private constructor(maximum: Int) {
        private var remainingCount: Int = maximum

        companion object {
            fun create(maximum: Int): Budget = Budget(maximum)
        }

        fun claim(): Boolean {
            if (remainingCount <= 0) {
                return false
            }
            remainingCount--
            return true
        }

        fun remaining(): Int = remainingCount
    }
}
