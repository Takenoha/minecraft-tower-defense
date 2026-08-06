package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.Objects;
import org.bukkit.Particle;

/** Immutable vanilla-particle definition for one tower kind. */
public record TowerEffectDefinition(
        TowerType type,
        Particle trail,
        Particle hit,
        Particle buff,
        int trailCount,
        int hitCount,
        int buffCount) {
    public TowerEffectDefinition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trail, "trail");
        Objects.requireNonNull(hit, "hit");
        Objects.requireNonNull(buff, "buff");
        if (trailCount <= 0 || hitCount <= 0 || buffCount <= 0) {
            throw new IllegalArgumentException("particle counts must be positive");
        }
    }
}
