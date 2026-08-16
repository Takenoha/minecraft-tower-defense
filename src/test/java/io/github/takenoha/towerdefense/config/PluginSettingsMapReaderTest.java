package io.github.takenoha.towerdefense.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginSettingsMapReaderTest {
    @Test
    void readsTheNestedYamlMapShapeAndValidatesIt() {
        Map<String, Object> values = validValues();

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(
                new CombatSettings(80.0, 56.0, 80.0, 192.0, 32.0, 8, 10, 10, 8, 5),
                settings.combat());
        assertEquals(new CoreSettings(1_000, 20), settings.core());
        assertEquals(new EnemySettings(120, 8, 4, 2, 4.0, 1.0), settings.enemies());
        assertEquals(ProtectionSettings.empty(), settings.protection());
        assertEquals(RewardSettings.defaults(), settings.rewards());
        assertEquals(TerrainMutationSettings.disabled(), settings.terrainMutation());
    }

    @Test
    void readsAConfiguredCoreAttackInterval() {
        Map<String, Object> values = validValues();
        mutableSection(values, "core").put("attack-interval-ticks", 40);

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(new CoreSettings(1_000, 20, 40), settings.core());
    }

    @Test
    void readsConfiguredCoreRepairEconomy() {
        Map<String, Object> values = validValues();
        mutableSection(values, "core").putAll(Map.of(
                "repair-material", "GOLD_INGOT",
                "repair-health-per-unit", 250,
                "repair-material-base-cost", 2,
                "repair-shard-base-cost", 3,
                "repair-cost-per-clear-level", 4));

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(
                new CoreSettings(1_000, 20, 20, "GOLD_INGOT", 250, 2, 3, 4),
                settings.core());
    }

    @Test
    void readsConfiguredCannonValues() {
        Map<String, Object> values = validValues();
        values.put("towers", Map.of(
                "base-limit", 8,
                "limit-increment", 2,
                "hard-cap", 40,
                "arrow", Map.of(
                        "damage", 4,
                        "range", 16.0,
                        "attack-interval-ticks", 20),
                "cannon", Map.of(
                        "damage", 12,
                        "range", 15.0,
                        "attack-interval-ticks", 50,
                        "splash-radius", 3.0)));

        TowerSettings settings = PluginSettings.from(values).towers();

        assertEquals(12, settings.cannonDamage());
        assertEquals(15.0, settings.cannonRange());
        assertEquals(50, settings.cannonAttackIntervalTicks());
        assertEquals(3.0, settings.cannonSplashRadius());
    }

    @Test
    void rejectsANonPositiveCoreAttackInterval() {
        Map<String, Object> values = validValues();
        mutableSection(values, "core").put("attack-interval-ticks", 0);

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(values));

        assertEquals(
                List.of("core.attack-interval-ticks: must be > 0 (was 0)"),
                exception.violations());
    }

    @Test
    void readsTeamRewardRetentionFromTheOptionalRewardsSection() {
        Map<String, Object> values = validValues();
        values.put("rewards", Map.of("team-queue-retention-seconds", 3_600));

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(new RewardSettings(3_600), settings.rewards());
        assertEquals(java.time.Duration.ofHours(1), settings.rewards().teamQueueRetention());
    }

    @Test
    void readsOptionalRoleRatiosAndKeepsDefaultsWhenTheyAreAbsent() {
        Map<String, Object> values = validValues();
        Map<String, Object> enemies = mutableSection(values, "enemies");
        enemies.put("destroyer-ratio", 0.2d);
        enemies.put("builder-ratio", 0.3d);
        enemies.put("speedster-ratio", 0.1d);
        enemies.put("ranged-ratio", 0.1d);
        enemies.put("heavy-ratio", 0.05d);

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(0.2d, settings.enemies().destroyerRatio());
        assertEquals(0.3d, settings.enemies().builderRatio());
        assertEquals(0.1d, settings.enemies().speedsterRatio());
        assertEquals(0.1d, settings.enemies().rangedRatio());
        assertEquals(0.05d, settings.enemies().heavyRatio());
        assertEquals(
                new EnemySettings(120, 8, 4, 2, 4.0, 1.0),
                PluginSettings.from(validValues()).enemies());
    }

    @Test
    void readsSupportEnemySettings() {
        Map<String, Object> values = validValues();
        mutableSection(values, "enemies").putAll(Map.of(
                "support-ratio", 0.08d,
                "support-radius", 9.5d,
                "support-heal-amount", 6.0d,
                "support-cooldown-ticks", 80,
                "support-speed-multiplier", 1.2d,
                "support-speed-duration-ticks", 50));

        EnemySettings enemies = PluginSettings.from(values).enemies();

        assertEquals(0.08d, enemies.supportRatio());
        assertEquals(9.5d, enemies.supportRadius());
        assertEquals(6.0d, enemies.supportHealAmount());
        assertEquals(80, enemies.supportCooldownTicks());
        assertEquals(1.2d, enemies.supportSpeedMultiplier());
        assertEquals(50, enemies.supportSpeedDurationTicks());
    }

    @Test
    void readsConfiguredDestroyerTowerAttackValues() {
        Map<String, Object> values = validValues();
        mutableSection(values, "enemies").putAll(Map.of(
                "tower-attack-damage", 13,
                "tower-attack-interval-ticks", 27,
                "tower-attack-range", 3.5d));

        EnemySettings enemies = PluginSettings.from(values).enemies();

        assertEquals(13, enemies.towerAttackDamage());
        assertEquals(27, enemies.towerAttackIntervalTicks());
        assertEquals(3.5d, enemies.towerAttackRange());
    }

    @Test
    void readsTheIndependentTerrainMutationActivationInputs() {
        Map<String, Object> values = validValues();
        values.put("terrain-mutation", Map.of(
                "requested", true,
                "paper-integration-verified", true,
                "recovery-verified", false));

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(
                new TerrainMutationSettings(true, true, false),
                settings.terrainMutation());
    }

    @Test
    void rejectsMalformedTerrainMutationActivationInputs() {
        Map<String, Object> values = validValues();
        values.put("terrain-mutation", Map.of(
                "requested", "yes",
                "paper-integration-verified", true,
                "recovery-verified", true));

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(values));

        assertEquals(
                List.of("terrain-mutation.requested: must be a boolean"),
                exception.violations());
    }

    @Test
    void rejectsNonPositiveTeamRewardRetention() {
        Map<String, Object> values = validValues();
        values.put("rewards", Map.of("team-queue-retention-seconds", 0));

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(values));

        assertEquals(
                List.of("rewards.team-queue-retention-seconds: must be > 0 (was 0)"),
                exception.violations());
    }

    @Test
    void readsForbiddenWorldsAndHorizontalRegions() {
        Map<String, Object> values = validValues();
        values.put("protection", new LinkedHashMap<>(Map.of(
                "forbidden-worlds", List.of("world_nether", "event_void"),
                "forbidden-regions", List.of(Map.of(
                        "world", "world",
                        "min-x", -100,
                        "min-z", -50,
                        "max-x", 100,
                        "max-z", 50)))));

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(Set.of("world_nether", "event_void"), settings.protection().forbiddenWorlds());
        assertEquals(
                List.of(new ForbiddenRegion("world", -100.0, -50.0, 100.0, 50.0)),
                settings.protection().forbiddenRegions());
    }

    @Test
    void reportsMalformedProtectionEntriesAlongsideTheirPaths() {
        Map<String, Object> values = validValues();
        values.put("protection", new LinkedHashMap<>(Map.of(
                "forbidden-worlds", List.of("", 3),
                "forbidden-regions", List.of(Map.of(
                        "world", "world",
                        "min-x", 10,
                        "min-z", 10,
                        "max-x", -10,
                        "max-z", 10)))));

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(values));

        assertContains(exception.violations(),
                "protection.forbidden-worlds[0]: must be a non-blank string",
                "protection.forbidden-worlds[1]: must be a non-blank string",
                "protection.forbidden-regions[0]: requires min-x <= max-x");
    }

    @Test
    void acceptsAnyNumericImplementationForDecimalValues() {
        Map<String, Object> values = validValues();
        Map<String, Object> combat = mutableSection(values, "combat");
        Map<String, Object> enemies = mutableSection(values, "enemies");
        combat.put("radius", 80L);
        combat.put("spawn-inner", 56.0F);
        enemies.put("boss-health-multiplier", 4);

        PluginSettings settings = PluginSettings.from(values);

        assertEquals(80.0, settings.combat().radius());
        assertEquals(56.0, settings.combat().spawnInner());
        assertEquals(4.0, settings.enemies().bossHealthMultiplier());
    }

    @Test
    void aggregatesMapShapeTypeAndSemanticProblems() {
        Map<String, Object> values = validValues();
        Map<String, Object> combat = mutableSection(values, "combat");
        Map<String, Object> enemies = mutableSection(values, "enemies");
        combat.remove("radius");
        combat.put("max-participants", "eight");
        combat.put("countdown-seconds", 0);
        enemies.put("max-alive", 1.5);
        enemies.put("boss-health-multiplier", 0.75);

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(values));

        assertContains(exception.violations(),
                "combat.radius: is required",
                "combat.max-participants: must be an integer",
                "enemies.max-alive: must be a 32-bit integer",
                "combat.countdown-seconds: must be > 0",
                "enemies.boss-health-multiplier: must be >= 1");
        assertEquals(5, exception.violations().size());
    }

    @Test
    void reportsAllMissingTopLevelSections() {
        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(Map.of()));

        assertEquals(List.of(
                "combat: section is required",
                "core: section is required",
                "enemies: section is required"), exception.violations());
    }

    @Test
    void reportsNullRootAndEveryMissingSectionTogether() {
        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(null));

        assertEquals(List.of(
                "settings: must not be null",
                "combat: section is required",
                "core: section is required",
                "enemies: section is required"), exception.violations());
    }

    @Test
    void rejectsNonMapSectionsWithoutThrowingACastException() {
        Map<String, Object> values = validValues();
        values.put("core", "not a section");

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(values));

        assertEquals(List.of("core: must be a map"), exception.violations());
    }

    @Test
    void rejectsIntegerOverflowAndFractionalCounts() {
        Map<String, Object> values = validValues();
        Map<String, Object> enemies = mutableSection(values, "enemies");
        enemies.put("max-alive", Long.MAX_VALUE);
        enemies.put("spawn-per-tick", 2.5);

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettings.from(values));

        assertContains(exception.violations(),
                "enemies.max-alive: must be a 32-bit integer",
                "enemies.spawn-per-tick: must be a 32-bit integer");
        assertEquals(2, exception.violations().size());
    }

    private static Map<String, Object> validValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("combat", new LinkedHashMap<>(Map.of(
                "radius", 80,
                "spawn-inner", 56,
                "spawn-outer", 80,
                "minimum-core-distance", 192,
                "core-gap", 32,
                "max-participants", 8,
                "countdown-seconds", 10,
                "preparation-seconds", 10,
                "intermission-seconds", 8,
                "absence-grace-seconds", 5)));
        values.put("core", new LinkedHashMap<>(Map.of(
                "max-health", 1_000,
                "damage-per-enemy", 20)));
        values.put("enemies", new LinkedHashMap<>(Map.of(
                "max-alive", 120,
                "spawn-per-tick", 8,
                "base-per-wave", 4,
                "added-per-wave", 2,
                "boss-health-multiplier", 4.0,
                "move-speed", 1.0)));
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableSection(Map<String, Object> root, String name) {
        Object value = root.get(name);
        assertTrue(value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
    }

    private static void assertContains(List<String> actual, String... expectedPrefixes) {
        for (String prefix : expectedPrefixes) {
            assertTrue(
                    actual.stream().anyMatch(violation -> violation.startsWith(prefix)),
                    () -> "Expected a violation starting with '" + prefix + "' but got " + actual);
        }
    }
}
