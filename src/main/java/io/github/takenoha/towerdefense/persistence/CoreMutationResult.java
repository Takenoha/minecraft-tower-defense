package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.Optional;

/** Result of a UUID-protected core repair, move, or replacement. */
public record CoreMutationResult(
        ManagementOutcome outcome,
        Optional<CoreRecord> core) {
    public CoreMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(core, "core");
    }
}
