package io.github.takenoha.towerdefense.persistence

/** Safe action selected by comparing the durable expectation with the live world. */
enum class BlockRollbackDecision {
    RESTORE,
    SKIP_ALREADY_BEFORE,
    CONFLICT,
}
