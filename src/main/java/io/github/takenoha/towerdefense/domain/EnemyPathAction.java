package io.github.takenoha.towerdefense.domain;

/** Safe next-step choice for the role-aware enemy navigation planner. */
public enum EnemyPathAction {
    ADVANCE,
    BREAK_OBSTACLE,
    BUILD_SUPPORT,
    RECALCULATE_PATH,
    RECOVER
}
