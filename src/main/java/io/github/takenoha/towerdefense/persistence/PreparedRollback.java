package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.UUID;

/** A rollback decision whose physical/database completion was interrupted. */
public record PreparedRollback(UUID operationId, BlockRollbackDecision decision) {
    public PreparedRollback {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(decision, "decision");
    }
}
