package io.github.takenoha.towerdefense.tactical;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable configured node in a tactical build. */
public record TacticalSkillNodeDefinition(
        String id,
        int version,
        int tier,
        String displayName,
        String description,
        List<TacticalEffectEntry> effects,
        List<String> prerequisiteNodeIds,
        Optional<String> exclusiveBranchGroup,
        Optional<String> branchId) {
    /**
     * Source-compatible constructor for the original linear node shape.
     *
     * <p>Old definitions have no graph metadata and therefore decode to this same neutral
     * shape. Semantic graph validation remains the responsibility of
     * {@link TacticalBuildDefinitionValidator}.
     */
    public TacticalSkillNodeDefinition(
            String id,
            int version,
            int tier,
            String displayName,
            String description,
            List<TacticalEffectEntry> effects) {
        this(
                id,
                version,
                tier,
                displayName,
                description,
                effects,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

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
        Objects.requireNonNull(prerequisiteNodeIds, "prerequisiteNodeIds");
        prerequisiteNodeIds = List.copyOf(prerequisiteNodeIds);
        exclusiveBranchGroup = requireOptionalText(exclusiveBranchGroup, "exclusiveBranchGroup");
        branchId = requireOptionalText(branchId, "branchId");
    }

    public TacticalSkillNodeSnapshot snapshot() {
        return new TacticalSkillNodeSnapshot(
                id,
                version,
                tier,
                displayName,
                description,
                effects,
                prerequisiteNodeIds,
                exclusiveBranchGroup,
                branchId);
    }

    private static Optional<String> requireOptionalText(
            Optional<String> value,
            String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> requireText(text, name));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
