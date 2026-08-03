package io.github.takenoha.towerdefense.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PluginSettingsValidatorTest {
    @Test
    void acceptsBoundaryValuesForTheDocumentedSpatialInvariants() {
        PluginSettings settings = new PluginSettings(
                new CombatSettings(80.0, 0.0, 80.0, 192.0, 32.0, 1, 1, 1, 1, 1),
                new CoreSettings(1, 1),
                new EnemySettings(1, 1, 1, 0, 1.0, 0.0001));

        assertSame(settings, assertDoesNotThrow(settings::validated));
        assertSame(settings, PluginSettingsValidator.validate(settings));
    }

    @Test
    void aggregatesEveryIndependentViolation() {
        PluginSettings settings = new PluginSettings(
                new CombatSettings(50.0, -1.0, 60.0, 100.0, 20.0, 0, 0, -1, 0, -2),
                new CoreSettings(0, -1),
                new EnemySettings(0, -1, 0, -1, 0.5, 0.0));

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                settings::validated);

        assertContains(exception.violations(),
                "combat: requires 0 <= spawn-inner < spawn-outer <= radius",
                "combat.minimum-core-distance: must be >= 2 * radius + core-gap",
                "combat.max-participants: must be > 0",
                "combat.countdown-seconds: must be > 0",
                "combat.preparation-seconds: must be > 0",
                "combat.intermission-seconds: must be > 0",
                "combat.absence-grace-seconds: must be > 0",
                "core.max-health: must be > 0",
                "core.damage-per-enemy: must be > 0",
                "enemies.max-alive: must be > 0",
                "enemies.spawn-per-tick: must be > 0",
                "enemies.base-per-wave: must be > 0",
                "enemies.added-per-wave: must be >= 0",
                "enemies.boss-health-multiplier: must be >= 1",
                "enemies.move-speed: must be > 0");
        assertEquals(15, exception.violations().size());
        assertTrue(exception.getMessage().startsWith("Invalid plugin settings (15 violations):"));
        for (String violation : exception.violations()) {
            assertTrue(exception.getMessage().contains(" - " + violation));
        }
    }

    @Test
    void rejectsNonFiniteDistancesMultiplierAndSpeed() {
        PluginSettings settings = new PluginSettings(
                new CombatSettings(
                        Double.POSITIVE_INFINITY,
                        Double.NaN,
                        80.0,
                        Double.NaN,
                        Double.NEGATIVE_INFINITY,
                        1,
                        1,
                        1,
                        1,
                        1),
                new CoreSettings(1, 1),
                new EnemySettings(1, 1, 1, 0, Double.POSITIVE_INFINITY, Double.NaN));

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                settings::validated);

        assertContains(exception.violations(),
                "combat.radius: must be finite",
                "combat.spawn-inner: must be finite",
                "combat.minimum-core-distance: must be finite",
                "combat.core-gap: must be finite",
                "enemies.boss-health-multiplier: must be finite",
                "enemies.move-speed: must be finite");
        assertEquals(6, exception.violations().size());
    }

    @Test
    void rejectsNegativeAndOverlappingRoleRatios() {
        PluginSettings negative = new PluginSettings(
                new CombatSettings(80.0, 0.0, 80.0, 192.0, 32.0, 1, 1, 1, 1, 1),
                new CoreSettings(1, 1),
                new EnemySettings(1, 1, 1, 0, 1.0, 1.0, -0.1, 0.1));
        InvalidPluginSettingsException negativeException = assertThrows(
                InvalidPluginSettingsException.class,
                negative::validated);
        assertEquals(
                List.of("enemies.destroyer-ratio: must be >= 0 (was -0.1)"),
                negativeException.violations());

        PluginSettings overlapping = new PluginSettings(
                new CombatSettings(80.0, 0.0, 80.0, 192.0, 32.0, 1, 1, 1, 1, 1),
                new CoreSettings(1, 1),
                new EnemySettings(1, 1, 1, 0, 1.0, 1.0, 0.6, 0.5));
        InvalidPluginSettingsException overlappingException = assertThrows(
                InvalidPluginSettingsException.class,
                overlapping::validated);
        assertEquals(1, overlappingException.violations().size());
        assertTrue(overlappingException.violations().get(0).startsWith(
                "enemies: destroyer-ratio + builder-ratio must be <= 1"));
    }

    @Test
    void reportsAllMissingRecordSections() {
        PluginSettings settings = new PluginSettings(null, null, null);

        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                settings::validated);

        assertEquals(List.of(
                "combat: section is required",
                "core: section is required",
                "enemies: section is required"), exception.violations());
    }

    @Test
    void rejectsNullSettingsWithAConfigurationException() {
        InvalidPluginSettingsException exception = assertThrows(
                InvalidPluginSettingsException.class,
                () -> PluginSettingsValidator.validate(null));

        assertEquals(List.of("settings: must not be null"), exception.violations());
    }

    @Test
    void exceptionExposesAnImmutableViolationSnapshot() {
        InvalidPluginSettingsException exception = new InvalidPluginSettingsException(
                List.of("combat.max-participants: must be > 0"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> exception.violations().add("another violation"));
    }

    private static void assertContains(List<String> actual, String... expectedPrefixes) {
        for (String prefix : expectedPrefixes) {
            assertTrue(
                    actual.stream().anyMatch(violation -> violation.startsWith(prefix)),
                    () -> "Expected a violation starting with '" + prefix + "' but got " + actual);
        }
    }
}
