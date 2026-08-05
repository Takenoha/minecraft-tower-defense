package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A durable invitation which can be accepted after the inviter or invitee reconnects. */
public record TeamInvitation(
        UUID id,
        UUID teamId,
        UUID inviterId,
        UUID inviteeId,
        TeamInvitationState state,
        Instant createdAt,
        Instant expiresAt,
        Instant resolvedAt) {
    public TeamInvitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Invitation expiration must be after creation");
        }
        if ((state == TeamInvitationState.PENDING) != (resolvedAt == null)) {
            throw new IllegalArgumentException(
                    "Pending invitations have no resolution timestamp and resolved invitations do");
        }
    }

    public boolean isPendingAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return state == TeamInvitationState.PENDING && now.isBefore(expiresAt);
    }
}
