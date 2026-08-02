package io.github.takenoha.towerdefense.persistence;

/** Durable lifecycle states for an event-owned enemy. */
public enum EnemyStatus {
    ALLOCATED,
    SPAWNED,
    DEAD,
    DESPAWNED,
    RECOVERY_REMOVED
}
