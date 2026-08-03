package io.github.takenoha.towerdefense.runtime;

/** Result of the safety checks before an event enemy may mutate one block. */
public enum TerrainMutationDecision {
    ALLOW,
    DISABLED,
    PROTECTED
}
