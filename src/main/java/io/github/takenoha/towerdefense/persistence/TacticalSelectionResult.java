package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.tactical.TacticalBuildSelectionView;
import java.util.Objects;

/** Result of an operation-UUID protected owner selection. */
public record TacticalSelectionResult(
        OperationOutcome outcome,
        TacticalBuildSelectionView selection) {
    public TacticalSelectionResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(selection, "selection");
    }
}
