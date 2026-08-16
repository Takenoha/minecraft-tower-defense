package io.github.takenoha.towerdefense.config;

import io.github.takenoha.towerdefense.domain.TowerType;
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
        validateTowers(settings.towers(), unreadablePaths, violations);
        return List.copyOf(violations);
    }

    private static void validateTowers(
            TowerSettings towers,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (towers == null) {
            if (!unreadablePaths.contains("towers")) {
                violations.add("towers: section is required");
            }
            return;
        }
        requirePositive("towers.base-limit", towers.baseLimit(), unreadablePaths, violations);
        if (isReadable("towers.limit-increment", unreadablePaths)
                && towers.limitIncrement() < 0) {
            violations.add(
                    "towers.limit-increment: must be >= 0 (was "
                            + towers.limitIncrement() + ")");
        }
        requirePositive("towers.hard-cap", towers.hardCap(), unreadablePaths, violations);
        requirePositive(
                "towers.max-health",
                towers.towerMaximumHitPoints(),
                unreadablePaths,
                violations);
        if (isReadable("towers.base-limit", unreadablePaths)
                && isReadable("towers.hard-cap", unreadablePaths)
                && towers.hardCap() < towers.baseLimit()) {
            violations.add(
                    "towers.hard-cap: must be >= towers.base-limit (was "
                            + towers.hardCap() + " < " + towers.baseLimit() + ")");
        }
        requirePositive("towers.arrow.damage", towers.arrowDamage(), unreadablePaths, violations);
        requireFinite("towers.arrow.range", towers.arrowRange(), unreadablePaths, violations);
        if (isReadable("towers.arrow.range", unreadablePaths)
                && Double.isFinite(towers.arrowRange())
                && towers.arrowRange() <= 0.0d) {
            violations.add(
                    "towers.arrow.range: must be > 0 (was " + towers.arrowRange() + ")");
        }
        requirePositive(
                "towers.arrow.attack-interval-ticks",
                towers.arrowAttackIntervalTicks(),
                unreadablePaths,
                violations);
        requirePositive("towers.cannon.damage", towers.cannonDamage(), unreadablePaths, violations);
        requireFinite("towers.cannon.range", towers.cannonRange(), unreadablePaths, violations);
        if (isReadable("towers.cannon.range", unreadablePaths)
                && Double.isFinite(towers.cannonRange())
                && towers.cannonRange() <= 0.0d) {
            violations.add(
                    "towers.cannon.range: must be > 0 (was " + towers.cannonRange() + ")");
        }
        requirePositive(
                "towers.cannon.attack-interval-ticks",
                towers.cannonAttackIntervalTicks(),
                unreadablePaths,
                violations);
        requireFinite(
                "towers.cannon.splash-radius",
                towers.cannonSplashRadius(),
                unreadablePaths,
                violations);
        if (isReadable("towers.cannon.splash-radius", unreadablePaths)
                && Double.isFinite(towers.cannonSplashRadius())
                && towers.cannonSplashRadius() <= 0.0d) {
            violations.add(
                    "towers.cannon.splash-radius: must be > 0 (was "
                            + towers.cannonSplashRadius() + ")");
        }
        requirePositive(
                "towers.upgrade.base-shard-cost",
                towers.individualUpgradeBaseShardCost(),
                unreadablePaths,
                violations);
        requirePositive(
                "towers.upgrade.base-core-cost",
                towers.individualUpgradeBaseCoreCost(),
                unreadablePaths,
                violations);
        if (isReadable("towers.upgrade.shard-cost-per-level", unreadablePaths)
                && towers.individualUpgradeShardCostPerLevel() < 0) {
            violations.add(
                    "towers.upgrade.shard-cost-per-level: must be >= 0 (was "
                            + towers.individualUpgradeShardCostPerLevel() + ")");
        }
        if (isReadable("towers.upgrade.core-cost-per-level", unreadablePaths)
                && towers.individualUpgradeCoreCostPerLevel() < 0) {
            violations.add(
                    "towers.upgrade.core-cost-per-level: must be >= 0 (was "
                            + towers.individualUpgradeCoreCostPerLevel() + ")");
        }
        requirePositive(
                "towers.upgrade.research-base-cost",
                towers.researchBaseCost(),
                unreadablePaths,
                violations);
        if (isReadable("towers.upgrade.research-cost-per-level", unreadablePaths)
                && towers.researchCostPerLevel() < 0) {
            violations.add(
                    "towers.upgrade.research-cost-per-level: must be >= 0 (was "
                            + towers.researchCostPerLevel() + ")");
        }
        requirePositive(
                "towers.battle-boost.base-cost",
                towers.battleBoostBaseCost(),
                unreadablePaths,
                violations);
        if (isReadable("towers.battle-boost.cost-per-level", unreadablePaths)
                && towers.battleBoostCostPerLevel() < 0) {
            violations.add(
                    "towers.battle-boost.cost-per-level: must be >= 0 (was "
                            + towers.battleBoostCostPerLevel() + ")");
        }
        requireAtLeastFinite(
                "towers.battle-boost.power-multiplier",
                towers.battleBoostPowerMultiplier(),
                1.0d,
                unreadablePaths,
                violations);
        requirePositiveFinite(
                "towers.battle-boost.speed-multiplier",
                towers.battleBoostSpeedMultiplier(),
                unreadablePaths,
                violations);
        requireAtLeastFinite(
                "towers.battle-boost.range-multiplier",
                towers.battleBoostRangeMultiplier(),
                1.0d,
                unreadablePaths,
                violations);
        requirePositive(
                "towers.battle-boost.stack-limit",
                towers.battleBoostStackLimit(),
                unreadablePaths,
                violations);
        requirePositive(
                "towers.battle-boost.funds-per-health",
                towers.battleRepairFundsPerHealth(),
                unreadablePaths,
                violations);
        requirePositive(
                "towers.battle-boost.health-per-purchase",
                towers.battleRepairHealthPerPurchase(),
                unreadablePaths,
                violations);
        for (TowerType type : TowerType.values()) {
            if (type == TowerType.ARROW || type == TowerType.CANNON) {
                continue;
            }
            TowerProfile profile = towers.specialistProfiles().get(type);
            if (profile == null) {
                violations.add("towers." + type.id() + ": profile is required");
                continue;
            }
            requirePositive(
                    "towers." + type.id() + ".damage",
                    profile.damage(),
                    unreadablePaths,
                    violations);
            requirePositive(
                    "towers." + type.id() + ".attack-interval-ticks",
                    profile.attackIntervalTicks(),
                    unreadablePaths,
                    violations);
            String path = "towers." + type.id();
            requirePositiveFinite(path + ".range", profile.range(), unreadablePaths, violations);
            requireNonNegativeFinite(
                    path + ".area-radius", profile.areaRadius(), unreadablePaths, violations);
            if (isReadable(path + ".slow-percent", unreadablePaths)
                    && (!Double.isFinite(profile.slowPercent())
                            || profile.slowPercent() < 0.0d
                            || profile.slowPercent() > 1.0d)) {
                violations.add(path + ".slow-percent: must be finite and between 0 and 1");
            }
            requireNonNegative(
                    path + ".slow-duration-ticks",
                    profile.slowDurationTicks(),
                    unreadablePaths,
                    violations);
            requireNonNegative(path + ".chain-count", profile.chainCount(), unreadablePaths, violations);
            requireNonNegativeFinite(
                    path + ".chain-radius", profile.chainRadius(), unreadablePaths, violations);
            requireNonNegativeFinite(
                    path + ".support-radius", profile.supportRadius(), unreadablePaths, violations);
            if (type == TowerType.SUPPORT) {
                requireAtLeastFinite(
                        path + ".support-damage-multiplier",
                        profile.supportDamageMultiplier(),
                        1.0d,
                        unreadablePaths,
                        violations);
                requirePositiveFinite(
                        path + ".support-speed-multiplier",
                        profile.supportSpeedMultiplier(),
                        unreadablePaths,
                        violations);
                requireAtLeastFinite(
                        path + ".support-range-multiplier",
                        profile.supportRangeMultiplier(),
                        1.0d,
                        unreadablePaths,
                        violations);
                requirePositive(
                        path + ".support-stack-limit",
                        profile.supportStackLimit(),
                        unreadablePaths,
                        violations);
            }
            requireNonNegative(
                    path + ".burn-duration-ticks",
                    profile.burnDurationTicks(),
                    unreadablePaths,
                    violations);
        }
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
        if (isReadable("core.warning-sound", unreadablePaths)
                && (core.warningSound() == null || core.warningSound().isBlank())) {
            violations.add("core.warning-sound: must be a non-blank string");
        }
        requirePositiveFinite(
                "core.warning-volume", core.warningVolume(), unreadablePaths, violations);
        requirePositiveFinite(
                "core.warning-pitch", core.warningPitch(), unreadablePaths, violations);
        requirePositive(
                "core.warning-min-interval-ticks",
                core.warningMinIntervalTicks(),
                unreadablePaths,
                violations);
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
        requirePositive(
                "enemies.tower-attack-damage",
                enemies.towerAttackDamage(),
                unreadablePaths,
                violations);
        requirePositive(
                "enemies.tower-attack-interval-ticks",
                enemies.towerAttackIntervalTicks(),
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

        String towerAttackRangePath = "enemies.tower-attack-range";
        if (isReadable(towerAttackRangePath, unreadablePaths)) {
            if (!Double.isFinite(enemies.towerAttackRange())) {
                violations.add(towerAttackRangePath + ": must be finite (was "
                        + enemies.towerAttackRange() + ")");
            } else if (enemies.towerAttackRange() <= 0.0d) {
                violations.add(towerAttackRangePath + ": must be > 0 (was "
                        + enemies.towerAttackRange() + ")");
            }
        }

        String destroyerPath = "enemies.destroyer-ratio";
        String builderPath = "enemies.builder-ratio";
        String speedsterPath = "enemies.speedster-ratio";
        String rangedPath = "enemies.ranged-ratio";
        String heavyPath = "enemies.heavy-ratio";
        validateRoleRatio(destroyerPath, enemies.destroyerRatio(), unreadablePaths, violations);
        validateRoleRatio(builderPath, enemies.builderRatio(), unreadablePaths, violations);
        validateRoleRatio(speedsterPath, enemies.speedsterRatio(), unreadablePaths, violations);
        validateRoleRatio(rangedPath, enemies.rangedRatio(), unreadablePaths, violations);
        validateRoleRatio(heavyPath, enemies.heavyRatio(), unreadablePaths, violations);
        double totalRoleRatio = enemies.destroyerRatio()
                + enemies.builderRatio()
                + enemies.speedsterRatio()
                + enemies.rangedRatio()
                + enemies.heavyRatio();
        if (isReadable(destroyerPath, unreadablePaths)
                && isReadable(builderPath, unreadablePaths)
                && isReadable(speedsterPath, unreadablePaths)
                && isReadable(rangedPath, unreadablePaths)
                && isReadable(heavyPath, unreadablePaths)
                && Double.isFinite(enemies.destroyerRatio())
                && Double.isFinite(enemies.builderRatio())
                && Double.isFinite(enemies.speedsterRatio())
                && Double.isFinite(enemies.rangedRatio())
                && Double.isFinite(enemies.heavyRatio())
                && enemies.destroyerRatio() >= 0.0d
                && enemies.builderRatio() >= 0.0d
                && enemies.speedsterRatio() >= 0.0d
                && enemies.rangedRatio() >= 0.0d
                && enemies.heavyRatio() >= 0.0d
                && totalRoleRatio > 1.0d) {
            violations.add(
                    "enemies: all enemy role ratios must sum to <= 1"
                            + " (was " + enemies.destroyerRatio()
                            + " + " + enemies.builderRatio()
                            + " + " + enemies.speedsterRatio()
                            + " + " + enemies.rangedRatio()
                            + " + " + enemies.heavyRatio() + ")");
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
        requirePositive(
                "rewards.research-crystal-base-per-stage",
                rewards.researchCrystalBasePerStage(),
                unreadablePaths,
                violations);
        if (isReadable("rewards.research-crystal-replay-percent", unreadablePaths)
                && (rewards.researchCrystalReplayPercent() < 0
                        || rewards.researchCrystalReplayPercent() > 100)) {
            violations.add(
                    "rewards.research-crystal-replay-percent: must be between 0 and 100"
                            + " (was " + rewards.researchCrystalReplayPercent() + ")");
        }
        if (isReadable("rewards.research-crystal-minimum-quantity", unreadablePaths)
                && rewards.researchCrystalMinimumQuantity() < 0) {
            violations.add(
                    "rewards.research-crystal-minimum-quantity: must be >= 0"
                            + " (was " + rewards.researchCrystalMinimumQuantity() + ")");
        }
        requirePositive(
                "rewards.battle-funds-normal-enemy",
                rewards.battleFundsNormalEnemy(),
                unreadablePaths,
                violations);
        requirePositive(
                "rewards.battle-funds-special-enemy",
                rewards.battleFundsSpecialEnemy(),
                unreadablePaths,
                violations);
        requirePositive(
                "rewards.battle-funds-boss-enemy",
                rewards.battleFundsBossEnemy(),
                unreadablePaths,
                violations);
        requirePositive(
                "rewards.battle-funds-per-wave",
                rewards.battleFundsPerWave(),
                unreadablePaths,
                violations);
        requirePositive(
                "rewards.defense-shards-normal-enemy",
                rewards.defenseShardsNormalEnemy(),
                unreadablePaths,
                violations);
        requirePositive(
                "rewards.defense-shards-special-enemy",
                rewards.defenseShardsSpecialEnemy(),
                unreadablePaths,
                violations);
        if (isReadable("rewards.enhancement-core-drop-percent", unreadablePaths)
                && (rewards.enhancementCoreDropPercent() < 0
                        || rewards.enhancementCoreDropPercent() > 100)) {
            violations.add(
                    "rewards.enhancement-core-drop-percent: must be between 0 and 100"
                            + " (was " + rewards.enhancementCoreDropPercent() + ")");
        }
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

    private static void requireNonNegative(
            String path,
            int value,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (isReadable(path, unreadablePaths) && value < 0) {
            violations.add(path + ": must be >= 0 (was " + value + ")");
        }
    }

    private static void requirePositiveFinite(
            String path,
            double value,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (isReadable(path, unreadablePaths)
                && (!Double.isFinite(value) || value <= 0.0d)) {
            violations.add(path + ": must be finite and > 0 (was " + value + ")");
        }
    }

    private static void requireAtLeastFinite(
            String path,
            double value,
            double minimum,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (isReadable(path, unreadablePaths)
                && (!Double.isFinite(value) || value < minimum)) {
            violations.add(path + ": must be finite and >= " + minimum + " (was " + value + ")");
        }
    }

    private static void requireNonNegativeFinite(
            String path,
            double value,
            Set<String> unreadablePaths,
            List<String> violations) {
        if (isReadable(path, unreadablePaths)
                && (!Double.isFinite(value) || value < 0.0d)) {
            violations.add(path + ": must be finite and >= 0 (was " + value + ")");
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
