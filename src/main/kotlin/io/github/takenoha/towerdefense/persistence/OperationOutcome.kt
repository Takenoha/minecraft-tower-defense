package io.github.takenoha.towerdefense.persistence

/** Result of an operation-UUID protected state mutation. */
enum class OperationOutcome {
    APPLIED,
    ALREADY_APPLIED,
    ALREADY_TERMINAL,
    STATE_MISMATCH,
}
