package io.github.takenoha.towerdefense.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TowerSettingsTest {
    @Test
    void defaultCapacityGrowsWithClearedStagesAndStopsAtHardCap() {
        TowerSettings settings = TowerSettings.defaults();

        assertEquals(8, settings.limitFor(0));
        assertEquals(10, settings.limitFor(1));
        assertEquals(40, settings.limitFor(16));
        assertEquals(40, settings.limitFor(Long.MAX_VALUE));
    }
}
