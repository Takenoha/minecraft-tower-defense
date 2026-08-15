package io.github.takenoha.towerdefense.domain

import java.util.Locale

/** Role assigned to one logical event enemy for movement and terrain decisions. */
enum class EnemyRole(
    private val ledgerTypeValue: String,
    private val speedMultiplier: Double,
) {
    NORMAL("FOUNDATION_NORMAL", 1.0),
    DESTROYER("FOUNDATION_DESTROYER", 1.15),
    BUILDER("FOUNDATION_BUILDER", 0.9),
    BOSS("FOUNDATION_BOSS", 0.85),
    ;

    fun ledgerType(): String = ledgerTypeValue

    /** Returns the PDC-safe role identifier. */
    fun id(): String = name

    /** Applies the bounded role speed multiplier to a validated base speed. */
    fun navigationSpeed(baseSpeed: Double): Double {
        require(baseSpeed.isFinite() && baseSpeed > 0.0) {
            "baseSpeed must be finite and positive"
        }
        val result = baseSpeed * speedMultiplier
        require(result.isFinite() && result > 0.0) {
            "role navigation speed is not finite"
        }
        return result
    }

    /** Whether the role may perform the supplied terrain action when explicitly selected. */
    fun allowsTerrainAction(action: EnemyTerrainActionKind, fallbackEligible: Boolean): Boolean = when (this) {
        NORMAL -> action == EnemyTerrainActionKind.BREAK && fallbackEligible
        DESTROYER -> action == EnemyTerrainActionKind.BREAK
        BUILDER -> action == EnemyTerrainActionKind.BUILD
        BOSS -> false
    }

    companion object {
        /** Converts a persisted role identifier and rejects unknown values. */
        @JvmStatic
        fun fromId(value: String): EnemyRole = try {
            valueOf(value.uppercase(Locale.ROOT))
        } catch (invalidRole: IllegalArgumentException) {
            throw IllegalArgumentException("unknown enemy role: $value", invalidRole)
        }
    }
}
