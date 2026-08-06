package io.github.takenoha.towerdefense.persistence;

import java.util.Objects;
import java.util.UUID;

/** A database-derived team wallet view plus the viewer's active-event provisional claims. */
public record TeamResourceSnapshot(
        UUID teamId,
        long defensePoints,
        long enhancementPoints,
        long teamProvisionalDefensePoints,
        long teamProvisionalEnhancementPoints,
        long viewerProvisionalDefensePoints,
        long viewerProvisionalEnhancementPoints) {
    /** Compatibility constructor for callers that only supplied viewer provisional totals. */
    public TeamResourceSnapshot(
            UUID teamId,
            long defensePoints,
            long enhancementPoints,
            long provisionalDefensePoints,
            long provisionalEnhancementPoints) {
        this(
                teamId,
                defensePoints,
                enhancementPoints,
                provisionalDefensePoints,
                provisionalEnhancementPoints,
                provisionalDefensePoints,
                provisionalEnhancementPoints);
    }

    public TeamResourceSnapshot {
        Objects.requireNonNull(teamId, "teamId");
        if (defensePoints < 0 || enhancementPoints < 0
                || teamProvisionalDefensePoints < 0 || teamProvisionalEnhancementPoints < 0
                || viewerProvisionalDefensePoints < 0 || viewerProvisionalEnhancementPoints < 0) {
            throw new IllegalArgumentException("resource quantities must not be negative");
        }
    }

    public long balance(ResourceType type) {
        return switch (ResourceType.require(type)) {
            case DEFENSE_POINTS -> defensePoints;
            case ENHANCEMENT_POINTS -> enhancementPoints;
        };
    }

    public long provisional(ResourceType type) {
        return switch (ResourceType.require(type)) {
            case DEFENSE_POINTS -> viewerProvisionalDefensePoints;
            case ENHANCEMENT_POINTS -> viewerProvisionalEnhancementPoints;
        };
    }

    public long teamProvisional(ResourceType type) {
        return switch (ResourceType.require(type)) {
            case DEFENSE_POINTS -> teamProvisionalDefensePoints;
            case ENHANCEMENT_POINTS -> teamProvisionalEnhancementPoints;
        };
    }
}
