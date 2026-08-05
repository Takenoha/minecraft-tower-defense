package io.github.takenoha.towerdefense.persistence;

/** Durable lifecycle of one team-bound research-crystal issuance batch. */
public enum ResearchCrystalBatchStatus {
    ISSUED,
    EXHAUSTED,
    VOIDED
}
