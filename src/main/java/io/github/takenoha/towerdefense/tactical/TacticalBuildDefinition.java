package io.github.takenoha.towerdefense.tactical;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Immutable, versioned tactical build definition. */
public record TacticalBuildDefinition(
        String id,
        int version,
        String displayName,
        String description,
        TacticalBuildCategory category,
        TacticalBuildRarity rarity,
        boolean enabled,
        int weight,
        String iconMaterial,
        Set<TowerType> targetTowerTypes,
        Optional<String> requiredUnlockId,
        List<TacticalSkillNodeDefinition> nodes) {
    public TacticalBuildDefinition {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        description = requireText(description, "description");
        iconMaterial = requireText(iconMaterial, "iconMaterial");
        category = Objects.requireNonNull(category, "category");
        rarity = Objects.requireNonNull(rarity, "rarity");
        requiredUnlockId = Objects.requireNonNull(requiredUnlockId, "requiredUnlockId")
                .map(value -> requireText(value, "requiredUnlockId"));
        if (version <= 0 || weight < 0) {
            throw new IllegalArgumentException("version must be positive and weight non-negative");
        }
        Objects.requireNonNull(targetTowerTypes, "targetTowerTypes");
        EnumSet<TowerType> targetCopy = targetTowerTypes.isEmpty()
                ? EnumSet.noneOf(TowerType.class)
                : EnumSet.copyOf(targetTowerTypes);
        targetTowerTypes = Collections.unmodifiableSet(targetCopy);
        Objects.requireNonNull(nodes, "nodes");
        nodes = List.copyOf(nodes);
    }

    public List<TacticalSkillNodeSnapshot> nodeSnapshots() {
        return nodes.stream().map(TacticalSkillNodeDefinition::snapshot).toList();
    }

    /** Returns the stable branch choices exposed by this definition. */
    public List<String> branchIds() {
        return nodes.stream()
                .map(TacticalSkillNodeDefinition::branchId)
                .flatMap(Optional::stream)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(TreeSet::new),
                        List::copyOf));
    }

    public boolean isBranched() {
        return nodes.stream().anyMatch(node -> node.branchId().isPresent());
    }

    public TacticalBuildSelectionView selectionView(
            java.util.UUID tacticalSessionId,
            java.util.UUID teamId,
            int stage,
            int highestUnlockedTier) {
        return selectionView(
                tacticalSessionId,
                teamId,
                stage,
                highestUnlockedTier,
                Optional.empty(),
                Set.of());
    }

    public TacticalBuildSelectionView selectionView(
            java.util.UUID tacticalSessionId,
            java.util.UUID teamId,
            int stage,
            int highestUnlockedTier,
            Optional<String> selectedBranchId,
            Set<String> unlockedNodeIds) {
        return new TacticalBuildSelectionView(
                tacticalSessionId,
                teamId,
                stage,
                id,
                version,
                highestUnlockedTier,
                selectedBranchId,
                unlockedNodeIds,
                nodeSnapshots());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
