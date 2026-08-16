package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.EnemySettings;
import io.github.takenoha.towerdefense.config.RewardSettings;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigLeafRecordsKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCompatibilityConstructors() throws Exception {
        assertRecord(EnemySettings.class,
                new Class<?>[]{int.class, int.class, int.class, int.class,
                        double.class, double.class, double.class, double.class, double.class,
                        double.class, double.class,
                        double.class, double.class, double.class, int.class, double.class, int.class,
                        int.class, int.class, double.class},
                "maxAlive", "spawnPerTick", "basePerWave", "addedPerWave",
                "bossHealthMultiplier", "moveSpeed", "destroyerRatio", "builderRatio",
                "speedsterRatio", "rangedRatio", "heavyRatio",
                "supportRatio", "supportRadius", "supportHealAmount", "supportCooldownTicks",
                "supportSpeedMultiplier", "supportSpeedDurationTicks",
                "towerAttackDamage", "towerAttackIntervalTicks", "towerAttackRange");
        assertRecord(RewardSettings.class,
                new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, int.class, int.class, boolean.class},
                "teamQueueRetentionSeconds", "researchCrystalBasePerStage",
                "researchCrystalReplayPercent", "researchCrystalMinimumQuantity",
                "battleFundsNormalEnemy", "battleFundsSpecialEnemy", "battleFundsBossEnemy",
                "battleFundsPerWave", "defenseShardsNormalEnemy", "defenseShardsSpecialEnemy",
                "enhancementCoreDropPercent", "legacyResourcePaymentsEnabled");

        assertNotNull(EnemySettings.class.getConstructor(
                int.class, int.class, int.class, int.class,
                double.class, double.class, double.class, double.class));
        assertNotNull(EnemySettings.class.getConstructor(
                int.class, int.class, int.class, int.class, double.class, double.class));
        assertNotNull(RewardSettings.class.getConstructor(int.class));
        assertNotNull(RewardSettings.class.getConstructor(
                int.class, int.class, int.class, int.class));
    }

    @Test
    void preservesStaticSettingsFieldsAndMethods() throws Exception {
        assertPublicStatic(RewardSettings.class, "defaults");
        assertPublicInstance(RewardSettings.class, "teamQueueRetention");
        assertPublicInstance(RewardSettings.class, "researchCrystalQuantity",
                long.class, long.class);
        assertPublicInstance(RewardSettings.class, "battleFundsFor", EnemyRole.class);
        assertPublicInstance(RewardSettings.class, "defenseShardsFor", EnemyRole.class);

        assertField(EnemySettings.class, "DEFAULT_DESTROYER_RATIO", double.class, 0.15d);
        assertField(EnemySettings.class, "DEFAULT_BUILDER_RATIO", double.class, 0.10d);
        assertField(EnemySettings.class, "DEFAULT_SPEEDSTER_RATIO", double.class, 0.10d);
        assertField(EnemySettings.class, "DEFAULT_RANGED_RATIO", double.class, 0.10d);
        assertField(EnemySettings.class, "DEFAULT_HEAVY_RATIO", double.class, 0.05d);
        assertField(EnemySettings.class, "DEFAULT_SUPPORT_RATIO", double.class, 0.05d);
        assertField(EnemySettings.class, "DEFAULT_SUPPORT_RADIUS", double.class, 8.0d);
        assertField(EnemySettings.class, "DEFAULT_SUPPORT_HEAL_AMOUNT", double.class, 4.0d);
        assertField(EnemySettings.class, "DEFAULT_SUPPORT_COOLDOWN_TICKS", int.class, 100);
        assertField(EnemySettings.class, "DEFAULT_SUPPORT_SPEED_MULTIPLIER", double.class, 1.15d);
        assertField(EnemySettings.class, "DEFAULT_SUPPORT_SPEED_DURATION_TICKS", int.class, 60);
        assertField(EnemySettings.class, "DEFAULT_TOWER_ATTACK_DAMAGE", int.class, 8);
        assertField(EnemySettings.class, "DEFAULT_TOWER_ATTACK_INTERVAL_TICKS", int.class, 20);
        assertField(EnemySettings.class, "DEFAULT_TOWER_ATTACK_RANGE", double.class, 2.5d);
        assertField(RewardSettings.class, "DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE",
                int.class, 100);
        assertField(RewardSettings.class, "DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT",
                int.class, 25);
        assertField(RewardSettings.class, "DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY",
                int.class, 0);
        assertField(RewardSettings.class, "DEFAULT_BATTLE_FUNDS_NORMAL_ENEMY", int.class, 5);
        assertField(RewardSettings.class, "DEFAULT_BATTLE_FUNDS_SPECIAL_ENEMY", int.class, 15);
        assertField(RewardSettings.class, "DEFAULT_BATTLE_FUNDS_BOSS_ENEMY", int.class, 50);
        assertField(RewardSettings.class, "DEFAULT_BATTLE_FUNDS_PER_WAVE", int.class, 50);
        assertField(RewardSettings.class, "DEFAULT_DEFENSE_SHARDS_NORMAL_ENEMY", int.class, 1);
        assertField(RewardSettings.class, "DEFAULT_DEFENSE_SHARDS_SPECIAL_ENEMY", int.class, 2);
        assertField(RewardSettings.class, "DEFAULT_ENHANCEMENT_CORE_DROP_PERCENT", int.class, 10);

        RewardSettings defaults = RewardSettings.defaults();
        assertEquals(Duration.ofDays(7), defaults.teamQueueRetention());
        assertEquals(100, defaults.researchCrystalQuantity(1L, 0L));
        assertEquals(25, defaults.researchCrystalQuantity(1L, 1L));
        assertEquals(0, defaults.researchCrystalQuantity(1L, 4L));
        assertEquals(5, defaults.battleFundsFor(EnemyRole.NORMAL));
        assertEquals(15, defaults.battleFundsFor(EnemyRole.DESTROYER));
        assertEquals(15, defaults.battleFundsFor(EnemyRole.RANGED));
        assertEquals(50, defaults.battleFundsFor(EnemyRole.BOSS));
        assertEquals(1, defaults.defenseShardsFor(EnemyRole.NORMAL));
        assertEquals(2, defaults.defenseShardsFor(EnemyRole.BOSS));
    }

    private static void assertPublicStatic(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }

    private static void assertPublicInstance(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(!Modifier.isStatic(method.getModifiers()), name);
    }

    private static void assertField(Class<?> type, String name, Class<?> fieldType, Object value)
            throws Exception {
        var field = type.getField(name);
        assertEquals(fieldType, field.getType(), name);
        assertTrue(Modifier.isPublic(field.getModifiers()), name);
        assertTrue(Modifier.isStatic(field.getModifiers()), name);
        assertTrue(Modifier.isFinal(field.getModifiers()), name);
        assertEquals(value, field.get(null), name);
    }

    private static void assertRecord(Class<?> type, Class<?>[] componentTypes, String... names)
            throws Exception {
        assertTrue(type.isRecord(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(List.of(names), Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(names.length, componentTypes.length);
        assertNotNull(type.getConstructor(componentTypes));
    }
}
