package io.github.takenoha.towerdefense.runtime;

import java.util.Objects;
import java.util.UUID;

/** Exact world/block coordinate used to prevent two towers sharing one physical position. */
public record TowerBlockKey(UUID worldId, int x, int y, int z) {
    public TowerBlockKey {
        Objects.requireNonNull(worldId, "worldId");
    }
}
