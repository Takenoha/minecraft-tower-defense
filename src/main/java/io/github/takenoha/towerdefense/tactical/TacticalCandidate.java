package io.github.takenoha.towerdefense.tactical;

import java.util.Objects;

/** One stable candidate slot shown before a defense starts. */
public record TacticalCandidate(int slot, TacticalBuildDefinition definition) {
    public TacticalCandidate {
        if (slot < 0 || slot > 2) {
            throw new IllegalArgumentException("candidate slot must be between 0 and 2");
        }
        Objects.requireNonNull(definition, "definition");
    }
}
