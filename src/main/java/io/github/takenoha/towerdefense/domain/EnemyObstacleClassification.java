package io.github.takenoha.towerdefense.domain;

/** Conservative result of classifying one candidate block in an enemy path. */
public enum EnemyObstacleClassification {
    /** The candidate does not require a terrain mutation. */
    CLEAR,
    /** The candidate is code-owned or stateful and must never be mutated by an enemy. */
    PROTECTED,
    /** The candidate is an ordinary block that an explicitly authorized breaker may remove. */
    BREAKABLE,
    /** The candidate is a verified replaceable gap with a safe solid support block. */
    BUILDABLE_GAP,
    /** The world state is insufficient or outside the active combat area. */
    UNAVAILABLE
}
