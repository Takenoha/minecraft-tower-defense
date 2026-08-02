package io.github.takenoha.towerdefense.config;

/**
 * Spatial, participation, and phase timing limits for one defense encounter.
 * Distances are measured horizontally in blocks and times are measured in seconds.
 */
public record CombatSettings(
        double radius,
        double spawnInner,
        double spawnOuter,
        double minimumCoreDistance,
        double coreGap,
        int maxParticipants,
        int countdownSeconds,
        int preparationSeconds,
        int intermissionSeconds,
        int absenceGraceSeconds) {
}
