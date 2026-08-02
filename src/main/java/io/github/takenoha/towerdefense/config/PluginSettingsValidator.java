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
        return List.copyOf(violations);
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
