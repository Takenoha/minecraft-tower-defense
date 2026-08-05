package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.TeamProgress;
import java.util.Objects;

/** Result of applying a crystal redemption, including the new team balance. */
public record ResearchCrystalRedemptionResult(
        OperationOutcome outcome,
        TeamProgress progress,
        ResearchCrystalBatch batch) {
    public ResearchCrystalRedemptionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(batch, "batch");
    }
}
