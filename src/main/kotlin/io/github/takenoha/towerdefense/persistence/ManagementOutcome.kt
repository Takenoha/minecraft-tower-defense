package io.github.takenoha.towerdefense.persistence

/** Result of a UUID-protected team or core management mutation. */
enum class ManagementOutcome {
    APPLIED,
    ALREADY_APPLIED,
}
