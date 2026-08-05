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

    @Test
    void defaultsExposeAllSpecialistRolesAndBattleBoostPolicy() {
        TowerSettings settings = TowerSettings.defaults();

        assertEquals(2, settings.damageFor(TowerType.FROST));
        assertEquals(3, settings.chainCountFor(TowerType.LIGHTNING));
        assertEquals(1.25d, settings.supportDamageMultiplier());
        assertEquals(0.80d, settings.supportSpeedMultiplier());
        assertEquals(2, settings.supportStackLimit());
        assertEquals(18, settings.damageFor(TowerType.SNIPER));
        assertEquals(3.0d, settings.areaRadiusFor(TowerType.FLAME));
        assertEquals(80, settings.burnDurationTicksFor(TowerType.FLAME));
        assertEquals(TowerSettings.DEFAULT_BATTLE_BOOST_BASE_COST,
                settings.battleBoostCost(0));
        assertEquals(
                TowerSettings.DEFAULT_BATTLE_BOOST_BASE_COST
                        + TowerSettings.DEFAULT_BATTLE_BOOST_COST_PER_LEVEL,
                settings.battleBoostCost(1));
    }
}
