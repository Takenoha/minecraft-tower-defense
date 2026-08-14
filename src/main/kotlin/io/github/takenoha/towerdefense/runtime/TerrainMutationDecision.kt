package io.github.takenoha.towerdefense.runtime

/** Result of the safety checks before an event enemy may mutate one block. */
enum class TerrainMutationDecision {
    ALLOW,
    DISABLED,
    PROTECTED,
    ROLE_REJECTED,
}
