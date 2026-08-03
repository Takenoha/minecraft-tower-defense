package io.github.takenoha.towerdefense.persistence;

/** Durable state of the single-use challenge token. */
public enum RaidSealStatus {
    AVAILABLE,
    RESERVED,
    CONSUMED,
    REFUNDED
}
