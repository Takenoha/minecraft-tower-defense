package io.github.takenoha.towerdefense.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.takenoha.towerdefense.domain.TowerType;
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

    @Test
    void defaultsExposeTheProvisionalCannonValues() {
        TowerSettings settings = TowerSettings.defaults();

        assertEquals(TowerSettings.DEFAULT_CANNON_DAMAGE, settings.cannonDamage());
        assertEquals(TowerSettings.DEFAULT_CANNON_RANGE, settings.cannonRange());
        assertEquals(
                TowerSettings.DEFAULT_CANNON_ATTACK_INTERVAL_TICKS,
                settings.cannonAttackIntervalTicks());
        assertEquals(
                TowerSettings.DEFAULT_CANNON_SPLASH_RADIUS,
                settings.cannonSplashRadius());
    }

    @Test
    void selectsCombatValuesByTowerType() {
        TowerSettings settings = TowerSettings.defaults();

        assertEquals(settings.arrowDamage(), settings.damageFor(TowerType.ARROW));
        assertEquals(settings.arrowRange(), settings.rangeFor(TowerType.ARROW));
        assertEquals(
                settings.arrowAttackIntervalTicks(),
                settings.attackIntervalTicksFor(TowerType.ARROW));
        assertEquals(settings.cannonDamage(), settings.damageFor(TowerType.CANNON));
        assertEquals(settings.cannonRange(), settings.rangeFor(TowerType.CANNON));
        assertEquals(
                settings.cannonAttackIntervalTicks(),
                settings.attackIntervalTicksFor(TowerType.CANNON));
    }
}
