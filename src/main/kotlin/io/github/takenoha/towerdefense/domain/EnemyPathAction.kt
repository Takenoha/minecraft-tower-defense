package io.github.takenoha.towerdefense.domain

/** Safe next-step choice for the role-aware enemy navigation planner. */
enum class EnemyPathAction {
    ADVANCE,
    BREAK_OBSTACLE,
    BUILD_SUPPORT,
    RECALCULATE_PATH,
    RECOVER,
}
