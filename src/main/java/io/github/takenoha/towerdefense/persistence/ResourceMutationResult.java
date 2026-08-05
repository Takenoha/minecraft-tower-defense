package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Result of an idempotent wallet mutation. */
public record ResourceMutationResult(
        OperationOutcome outcome,
        TeamResourceSnapshot resources) {
    public ResourceMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(resources, "resources");
    }
}
