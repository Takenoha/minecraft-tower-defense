package io.github.takenoha.towerdefense.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validates cross-field and scalar invariants without depending on Paper APIs. */
public final class PluginSettingsValidator {
    private PluginSettingsValidator() {
    }

    /**
     * Validates every setting and reports all violations in one exception.
     *
     * @param settings settings snapshot to validate
     * @return the same validated snapshot, for convenient use at construction boundaries
     * @throws InvalidPluginSettingsException when one or more invariants are violated
     */
    public static PluginSettings validate(PluginSettings settings) {
        List<String> violations = violations(settings, Set.of());
        if (!violations.isEmpty()) {
            throw new InvalidPluginSettingsException(violations);
        }
        return settings;
    }

    static List<String> violations(PluginSettings settings, Set<String> unreadablePaths) {
        List<String> violations = new ArrayList<>();
        if (settings == null) {
            violations.add("settings: must not be null");
            return List.copyOf(violations);
        }

        validateCombat(settings.combat(), unreadablePaths, violations);
        validateCore(settings.core(), unreadablePaths, violations);
        validateEnemies(settings.enemies(), unreadablePaths, violations);
        validateProtection(settings.protection(), unreadablePaths, violations);
        validateRewards(settings.rewards(), unreadablePaths, violations);
        validateTerrainMutation(settings.terrainMutation(), unreadablePaths, violations);
        return List.copyOf(violations);
    }

    private static void validateTerrainMutation(
            TerrainMutationSettings terrainMutation,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (terrainMutation == null && !unreadablePaths.contains("terrain-mutation")) {
            violations.add("terrain-mutation: section is required");
        }
    }

    private static void validateCombat(
            CombatSettings combat,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (combat == null) {
            if (!unreadablePaths.contains("combat")) {
                violations.add("combat: section is required");
            }
            return;
        }

        requireFinite("combat.radius", combat.radius(), unreadablePaths, violations);
        requireFinite("combat.spawn-inner", combat.spawnInner(), unreadablePaths, violations);
        requireFinite("combat.spawn-outer", combat.spawnOuter(), unreadablePaths, violations);
        requireFinite(
                "combat.minimum-core-distance",
                combat.minimumCoreDistance(),
                unreadablePaths,
                violations);
        requireFinite("combat.core-gap", combat.coreGap(), unreadablePaths, violations);

        if (readableAndFinite(
                unreadablePaths,
                combat.radius(),
                "combat.radius",
                combat.spawnInner(),
                "combat.spawn-inner",
                combat.spawnOuter(),
                "combat.spawn-outer")
                && !(0.0 <= combat.spawnInner()
                && combat.spawnInner() < combat.spawnOuter()
                && combat.spawnOuter() <= combat.radius())) {
            violations.add("combat: requires 0 <= spawn-inner < spawn-outer <= radius"
                    + " (spawn-inner=" + combat.spawnInner()
                    + ", spawn-outer=" + combat.spawnOuter()
                    + ", radius=" + combat.radius() + ")");
        }

        if (isReadable("combat.core-gap", unreadablePaths)
                && Double.isFinite(combat.coreGap())
                && combat.coreGap() < 0.0) {
            violations.add("combat.core-gap: must be >= 0 (was " + combat.coreGap() + ")");
        }

        if (readableAndFinite(
                unreadablePaths,
                combat.radius(),
                "combat.radius",
                combat.coreGap(),
                "combat.core-gap",
                combat.minimumCoreDistance(),
                "combat.minimum-core-distance")) {
            double requiredDistance = 2.0 * combat.radius() + combat.coreGap();
            if (!Double.isFinite(requiredDistance)) {
                violations.add("combat: 2 * radius + core-gap must be finite");
            } else if (combat.minimumCoreDistance() < requiredDistance) {
                violations.add("combat.minimum-core-distance: must be >= 2 * radius + core-gap"
                        + " (required >= " + requiredDistance
                        + ", was " + combat.minimumCoreDistance() + ")");
            }
        }

        requirePositive(
                "combat.max-participants",
                combat.maxParticipants(),
                unreadablePaths,
                violations);
        requirePositive(
                "combat.countdown-seconds",
                combat.countdownSeconds(),
                unreadablePaths,
                violations);
        requirePositive(
                "combat.preparation-seconds",
                combat.preparationSeconds(),
                unreadablePaths,
                violations);
        requirePositive(
                "combat.intermission-seconds",
                combat.intermissionSeconds(),
                unreadablePaths,
                violations);
        requirePositive(
                "combat.absence-grace-seconds",
                combat.absenceGraceSeconds(),
                unreadablePaths,
                violations);
    }

    private static void validateCore(
            CoreSettings core,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (core == null) {
            if (!unreadablePaths.contains("core")) {
                violations.add("core: section is required");
            }
            return;
        }

        requirePositive("core.max-health", core.maxHealth(), unreadablePaths, violations);
        requirePositive(
                "core.damage-per-enemy",
                core.damagePerEnemy(),
                unreadablePaths,
                violations);
        requirePositive(
                "core.attack-interval-ticks",
                core.attackIntervalTicks(),
                unreadablePaths,
                violations);
        if (isReadable("core.repair-material", unreadablePaths)
                && (core.repairMaterial() == null || core.repairMaterial().isBlank())) {
            violations.add("core.repair-material: must be a non-blank string");
        }
        requirePositive(
                "core.repair-health-per-unit",
                core.repairHealthPerUnit(),
                unreadablePaths,
                violations);
        requirePositive(
                "core.repair-material-base-cost",
                core.repairMaterialBaseCost(),
                unreadablePaths,
                violations);
        requirePositive(
                "core.repair-shard-base-cost",
                core.repairShardBaseCost(),
                unreadablePaths,
                violations);
        if (isReadable("core.repair-cost-per-clear-level", unreadablePaths)
                && core.repairCostPerClearLevel() < 0) {
            violations.add("core.repair-cost-per-clear-level: must be >= 0 (was "
                    + core.repairCostPerClearLevel() + ")");
        }
    }

    private static void validateEnemies(
            EnemySettings enemies,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (enemies == null) {
            if (!unreadablePaths.contains("enemies")) {
                violations.add("enemies: section is required");
            }
            return;
        }

        requirePositive("enemies.max-alive", enemies.maxAlive(), unreadablePaths, violations);
        requirePositive(
                "enemies.spawn-per-tick",
                enemies.spawnPerTick(),
                unreadablePaths,
                violations);
        requirePositive(
                "enemies.base-per-wave",
                enemies.basePerWave(),
                unreadablePaths,
                violations);
        if (isReadable("enemies.added-per-wave", unreadablePaths)
                && enemies.addedPerWave() < 0) {
            violations.add("enemies.added-per-wave: must be >= 0 (was "
                    + enemies.addedPerWave() + ")");
        }

        String bossPath = "enemies.boss-health-multiplier";
        if (isReadable(bossPath, unreadablePaths)) {
            if (!Double.isFinite(enemies.bossHealthMultiplier())) {
                violations.add(bossPath + ": must be finite (was "
                        + enemies.bossHealthMultiplier() + ")");
            } else if (enemies.bossHealthMultiplier() < 1.0) {
                violations.add(bossPath + ": must be >= 1 (was "
                        + enemies.bossHealthMultiplier() + ")");
            }
        }

        String speedPath = "enemies.move-speed";
        if (isReadable(speedPath, unreadablePaths)) {
            if (!Double.isFinite(enemies.moveSpeed())) {
                violations.add(speedPath + ": must be finite (was "
                        + enemies.moveSpeed() + ")");
            } else if (enemies.moveSpeed() <= 0.0) {
                violations.add(speedPath + ": must be > 0 (was "
                        + enemies.moveSpeed() + ")");
            }
        }

        String destroyerPath = "enemies.destroyer-ratio";
        String builderPath = "enemies.builder-ratio";
        validateRoleRatio(destroyerPath, enemies.destroyerRatio(), unreadablePaths, violations);
        validateRoleRatio(builderPath, enemies.builderRatio(), unreadablePaths, violations);
        if (isReadable(destroyerPath, unreadablePaths)
                && isReadable(builderPath, unreadablePaths)
                && Double.isFinite(enemies.destroyerRatio())
                && Double.isFinite(enemies.builderRatio())
                && enemies.destroyerRatio() >= 0.0d
                && enemies.builderRatio() >= 0.0d
                && enemies.destroyerRatio() + enemies.builderRatio() > 1.0d) {
            violations.add(
                    "enemies: destroyer-ratio + builder-ratio must be <= 1"
                            + " (was " + enemies.destroyerRatio()
                            + " + " + enemies.builderRatio() + ")");
        }
    }

    private static void validateRoleRatio(
            String path,
            double value,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (!isReadable(path, unreadablePaths)) {
            return;
        }
        if (!Double.isFinite(value)) {
            violations.add(path + ": must be finite (was " + value + ")");
        } else if (value < 0.0d) {
            violations.add(path + ": must be >= 0 (was " + value + ")");
        }
    }

    private static void validateProtection(
            ProtectionSettings protection,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (protection == null) {
            if (!unreadablePaths.contains("protection")) {
                violations.add("protection: section is required");
            }
            return;
        }

        if (protection.forbiddenWorlds() == null) {
            if (!unreadablePaths.contains("protection.forbidden-worlds")) {
                violations.add("protection.forbidden-worlds: must be a list");
            }
        } else {
            int index = 0;
            for (String world : protection.forbiddenWorlds()) {
                if (world == null || world.isBlank()) {
                    violations.add(
                            "protection.forbidden-worlds[" + index
                                    + "]: must be a non-blank string");
                }
                index++;
            }
        }

        if (protection.forbiddenRegions() == null) {
            if (!unreadablePaths.contains("protection.forbidden-regions")) {
                violations.add("protection.forbidden-regions: must be a list");
            }
            return;
        }

        for (int index = 0; index < protection.forbiddenRegions().size(); index++) {
            ForbiddenRegion region = protection.forbiddenRegions().get(index);
            String path = "protection.forbidden-regions[" + index + "]";
            if (region == null) {
                violations.add(path + ": must be a map");
                continue;
            }
            if (region.worldName() == null || region.worldName().isBlank()) {
                violations.add(path + ".world: must be a non-blank string");
            }
            requireFinite(path + ".min-x", region.minX(), unreadablePaths, violations);
            requireFinite(path + ".min-z", region.minZ(), unreadablePaths, violations);
            requireFinite(path + ".max-x", region.maxX(), unreadablePaths, violations);
            requireFinite(path + ".max-z", region.maxZ(), unreadablePaths, violations);
            if (Double.isFinite(region.minX())
                    && Double.isFinite(region.minZ())
                    && Double.isFinite(region.maxX())
                    && Double.isFinite(region.maxZ())
                    && (region.minX() > region.maxX() || region.minZ() > region.maxZ())) {
                violations.add(path + ": requires min-x <= max-x and min-z <= max-z");
            }
        }
    }

    private static void validateRewards(
            RewardSettings rewards,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (rewards == null) {
            if (!unreadablePaths.contains("rewards")) {
                violations.add("rewards: section is required");
            }
            return;
        }
        requirePositive(
                "rewards.team-queue-retention-seconds",
                rewards.teamQueueRetentionSeconds(),
                unreadablePaths,
                violations);
    }

    private static void requirePositive(
            String path,
            int value,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (isReadable(path, unreadablePaths) && value <= 0) {
            violations.add(path + ": must be > 0 (was " + value + ")");
        }
    }

    private static void requireFinite(
            String path,
            double value,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (isReadable(path, unreadablePaths) && !Double.isFinite(value)) {
            violations.add(path + ": must be finite (was " + value + ")");
        }
    }

    private static boolean readableAndFinite(Set<String> unreadablePaths, Object... pathValues) {
        for (int index = 0; index < pathValues.length; index += 2) {
            double value = (double) pathValues[index];
            String path = (String) pathValues[index + 1];
            if (!isReadable(path, unreadablePaths) || !Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReadable(String path, Set<String> unreadablePaths) {
        return !unreadablePaths.contains(path);
    }
}
