package io.github.takenoha.towerdefense.persistence;

/** Durable lifecycle state of a team invitation. */
public enum TeamInvitationState {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED
}
