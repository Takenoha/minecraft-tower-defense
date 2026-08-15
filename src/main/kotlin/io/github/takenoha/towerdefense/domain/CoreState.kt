package io.github.takenoha.towerdefense.domain

import kotlin.jvm.JvmRecord

/** Immutable state of a team's core within a defense session. */
@JvmRecord
data class CoreState(
    val maximumHitPoints: Long,
    val currentHitPoints: Long,
    val present: Boolean,
) {
    init {
        if (maximumHitPoints <= 0L) {
            throw IllegalArgumentException("maximumHitPoints must be positive")
        }
        if (currentHitPoints < 0L || currentHitPoints > maximumHitPoints) {
            throw IllegalArgumentException(
                "currentHitPoints must be between zero and maximumHitPoints",
            )
        }
        if (present != (currentHitPoints > 0L)) {
            throw IllegalArgumentException(
                "a present core must have HP and a zero-HP core must be absent",
            )
        }
    }

    /** Creates a present core at full health. */
    companion object {
        @JvmStatic
        fun intact(maximumHitPoints: Long): CoreState =
            CoreState(maximumHitPoints, maximumHitPoints, true)

        /** Creates the persisted state of a core that has reached zero HP and disappeared. */
        @JvmStatic
        fun destroyed(maximumHitPoints: Long): CoreState =
            CoreState(maximumHitPoints, 0L, false)
    }

    fun isDestroyed(): Boolean = !present

    /** Applies non-negative damage, saturating at zero without arithmetic overflow. */
    fun damage(amount: Long): CoreState {
        requireNonNegative("amount", amount)
        if (amount == 0L || isDestroyed()) {
            return this
        }
        val remaining = if (amount >= currentHitPoints) 0L else currentHitPoints - amount
        return CoreState(maximumHitPoints, remaining, remaining > 0L)
    }

    /** Repairs a present core, saturating at maximum HP. Destroyed cores cannot be repaired. */
    fun repair(amount: Long): CoreState {
        requireNonNegative("amount", amount)
        if (isDestroyed()) {
            throw IllegalStateException("a destroyed core must be replaced, not repaired")
        }
        if (amount == 0L || currentHitPoints == maximumHitPoints) {
            return this
        }
        val missing = maximumHitPoints - currentHitPoints
        val restored = minOf(amount, missing)
        return CoreState(maximumHitPoints, currentHitPoints + restored, true)
    }

    private fun requireNonNegative(name: String, value: Long) {
        if (value < 0L) {
            throw IllegalArgumentException("$name must not be negative")
        }
    }
}
