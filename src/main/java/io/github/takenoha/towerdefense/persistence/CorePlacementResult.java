package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;

/** Result of applying a durable public core placement. */
public record CorePlacementResult(CorePlacement placement, CoreRecord core) {
    public CorePlacementResult {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(core, "core");
    }
}
