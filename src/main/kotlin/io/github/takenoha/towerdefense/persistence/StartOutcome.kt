package io.github.takenoha.towerdefense.persistence

/** Result of the atomic global event-lock acquisition and session insert. */
enum class StartOutcome {
    STARTED,
    LOCKED,
}
