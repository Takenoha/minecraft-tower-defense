package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatAreaTest {
    private static final CombatArea DEFAULT_AREA = new CombatArea(80.0d, 56.0d, 80.0d, 192.0d, 32.0d);

    @Test
    void acceptsTheSpecifiedBoundaryConfiguration() {
        assertEquals(192.0d, DEFAULT_AREA.requiredCoreDistance());
        assertTrue(DEFAULT_AREA.contains(10.0d, -10.0d, 90.0d, -10.0d));
        assertFalse(DEFAULT_AREA.contains(
                10.0d, -10.0d, Math.nextUp(90.0d), -10.0d));
    }

    @Test
    void spawnBandIncludesBothEdges() {
        assertTrue(DEFAULT_AREA.isInSpawnBand(0.0d, 0.0d, 56.0d, 0.0d));
        assertTrue(DEFAULT_AREA.isInSpawnBand(0.0d, 0.0d, 80.0d, 0.0d));
        assertFalse(DEFAULT_AREA.isInSpawnBand(0.0d, 0.0d, Math.nextDown(56.0d), 0.0d));
        assertFalse(DEFAULT_AREA.isInSpawnBand(0.0d, 0.0d, Math.nextUp(80.0d), 0.0d));
    }

    @Test
    void distanceUsesOnlyTheHorizontalPlane() {
        assertEquals(5.0d, CombatArea.horizontalDistance(10.0d, 20.0d, 13.0d, 24.0d));
        assertTrue(DEFAULT_AREA.coresAreFarEnoughApart(0.0d, 0.0d, 192.0d, 0.0d));
        assertFalse(DEFAULT_AREA.coresAreFarEnoughApart(0.0d, 0.0d, 191.999d, 0.0d));
    }

    @Test
    void rejectsEveryCrossFieldInvariantViolation() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(0.0d, 0.0d, 1.0d, 2.0d, 0.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(80.0d, -1.0d, 70.0d, 192.0d, 32.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(80.0d, 56.0d, 56.0d, 192.0d, 32.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(80.0d, 56.0d, 81.0d, 192.0d, 32.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(80.0d, 56.0d, 80.0d, 191.999d, 32.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(80.0d, 56.0d, 80.0d, 192.0d, -1.0d)));
    }

    @Test
    void rejectsNonFiniteConfigurationAndCoordinates() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(Double.NaN, 0.0d, 1.0d, 2.0d, 0.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(80.0d, 56.0d, Double.POSITIVE_INFINITY, 192.0d, 32.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CombatArea(Double.MAX_VALUE, 0.0d, 1.0d, Double.MAX_VALUE, 0.0d)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> DEFAULT_AREA.contains(Double.NaN, 0.0d, 0.0d, 0.0d)));
    }
}
