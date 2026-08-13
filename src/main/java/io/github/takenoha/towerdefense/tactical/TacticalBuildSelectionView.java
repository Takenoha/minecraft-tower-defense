package io.github.takenoha.towerdefense.tactical;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only selected-build view shared with the defense runtime. */
public record TacticalBuildSelectionView(
        UUID tacticalSessionId,
        UUID teamId,
        int stage,
        String buildId,
        int buildVersion,
        int highestUnlockedTier,
        Optional<String> selectedBranchId,
        Set<String> unlockedNodeIds,
        List<TacticalSkillNodeSnapshot> nodes) {
    public TacticalBuildSelectionView {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(buildId, "buildId");
        if (buildId.isBlank()) {
            throw new IllegalArgumentException("buildId must not be blank");
        }
        if (stage <= 0 || buildVersion <= 0) {
            throw new IllegalArgumentException("stage and buildVersion must be positive");
        }
        if (highestUnlockedTier < 0 || highestUnlockedTier > 6) {
            throw new IllegalArgumentException("highestUnlockedTier must be between 0 and 6");
        }
        selectedBranchId = Objects.requireNonNull(selectedBranchId, "selectedBranchId")
                .map(value -> requireText(value, "selectedBranchId"));
        unlockedNodeIds = Objects.requireNonNull(unlockedNodeIds, "unlockedNodeIds").stream()
                .map(value -> requireText(value, "unlockedNodeId"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Objects.requireNonNull(nodes, "nodes");
        nodes = List.copyOf(nodes);
    }

    /** Backward-compatible constructor for linear tactical builds. */
    public TacticalBuildSelectionView(
            UUID tacticalSessionId,
            UUID teamId,
            int stage,
            String buildId,
            int buildVersion,
            int highestUnlockedTier,
            List<TacticalSkillNodeSnapshot> nodes) {
        this(
                tacticalSessionId,
                teamId,
                stage,
                buildId,
                buildVersion,
                highestUnlockedTier,
                Optional.empty(),
                Set.of(),
                nodes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
