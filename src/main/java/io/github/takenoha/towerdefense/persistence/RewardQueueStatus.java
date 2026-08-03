package io.github.takenoha.towerdefense.persistence;

/** Delivery state of a durable reward queue row. */
public enum RewardQueueStatus {
    PENDING,
    DELIVERED,
    VOIDED
}
