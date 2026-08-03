package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.Optional;

/** Result of a team mutation, including the post-operation durable team when it still exists. */
public record TeamMutationResult(
        ManagementOutcome outcome,
        Optional<TeamRecord> team) {
    public TeamMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(team, "team");
    }
}
