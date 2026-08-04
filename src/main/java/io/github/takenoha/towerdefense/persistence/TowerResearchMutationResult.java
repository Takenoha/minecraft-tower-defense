package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.domain.TowerResearch;
import java.util.Objects;

/** Result of an idempotent, team-scoped tower research purchase. */
public record TowerResearchMutationResult(
        OperationOutcome outcome,
        TeamProgress progress,
        TowerResearch research) {
    public TowerResearchMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(research, "research");
    }
}
