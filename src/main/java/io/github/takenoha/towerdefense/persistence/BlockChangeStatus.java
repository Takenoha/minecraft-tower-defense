package io.github.takenoha.towerdefense.persistence;

/** Durable state of one write-ahead block mutation. */
public enum BlockChangeStatus {
    PREPARED,
    APPLIED,
    ROLLED_BACK,
    CONFLICT
}
