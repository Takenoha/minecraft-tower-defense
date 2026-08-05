package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.Optional;

/** Result of an idempotent team-invitation mutation. */
public record TeamInvitationMutationResult(
        ManagementOutcome outcome,
        TeamInvitation invitation,
        Optional<TeamRecord> team) {
    public TeamInvitationMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(invitation, "invitation");
        Objects.requireNonNull(team, "team");
    }
}
