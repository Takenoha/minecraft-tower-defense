package io.github.takenoha.towerdefense.tactical

import java.util.Objects
import kotlin.jvm.JvmRecord

/** Read-only combat facts used when evaluating conditional tactical effects. */
@JvmRecord
data class TacticalTargetContext(
    val targetHealthFraction: Double,
    val coreHealthFraction: Double,
    val boss: Boolean,
    val slowed: Boolean,
    val burning: Boolean,
) {
    init {
        requireFraction(targetHealthFraction, "targetHealthFraction")
        requireFraction(coreHealthFraction, "coreHealthFraction")
    }

    fun targetHasHighHealth(): Boolean = targetHealthFraction >= 0.75

    fun targetHasLowHealth(): Boolean = targetHealthFraction <= 0.30

    fun coreBelowHalf(): Boolean = coreHealthFraction < 0.50

    fun coreBelowThirtyPercent(): Boolean = coreHealthFraction < 0.30

    companion object {
        @JvmStatic
        fun neutral(): TacticalTargetContext =
            TacticalTargetContext(1.0, 1.0, false, false, false)

        private fun requireFraction(value: Double, name: String) {
            Objects.requireNonNull(name, "name")
            if (!value.isFinite() || value < 0.0 || value > 1.0) {
                throw IllegalArgumentException("$name must be between 0 and 1")
            }
        }
    }
}
