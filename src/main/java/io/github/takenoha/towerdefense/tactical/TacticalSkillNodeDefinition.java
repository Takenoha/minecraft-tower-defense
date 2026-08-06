package io.github.takenoha.towerdefense.tactical;

import java.util.List;
import java.util.Objects;

/** Immutable configured node in a tactical build. */
public record TacticalSkillNodeDefinition(
        String id,
        int version,
        int tier,
        String displayName,
        String description,
        List<TacticalEffectEntry> effects) {
    public TacticalSkillNodeDefinition {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        description = requireText(description, "description");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (tier < 1 || tier > 6) {
            throw new IllegalArgumentException("tier must be between 1 and 6");
        }
        Objects.requireNonNull(effects, "effects");
        effects = List.copyOf(effects);
    }

    public TacticalSkillNodeSnapshot snapshot() {
        return new TacticalSkillNodeSnapshot(id, version, tier, displayName, description, effects);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
