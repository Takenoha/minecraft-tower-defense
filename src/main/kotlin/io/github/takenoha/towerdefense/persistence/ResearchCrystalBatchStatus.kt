package io.github.takenoha.towerdefense.persistence

/** Durable lifecycle of one team-bound research-crystal issuance batch. */
enum class ResearchCrystalBatchStatus {
    ISSUED,
    EXHAUSTED,
    VOIDED,
}
