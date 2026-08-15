package io.github.takenoha.towerdefense.domain

/** Conservative result of classifying one candidate block in an enemy path. */
enum class EnemyObstacleClassification {
    CLEAR,
    PROTECTED,
    BREAKABLE,
    BUILDABLE_GAP,
    UNAVAILABLE,
}
