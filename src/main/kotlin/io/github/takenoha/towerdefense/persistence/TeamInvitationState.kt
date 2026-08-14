package io.github.takenoha.towerdefense.persistence

/** Durable lifecycle state of a team invitation. */
enum class TeamInvitationState {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED,
}
