package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.CoreSettings;
import io.github.takenoha.towerdefense.domain.CoreRepairCost;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class CoreDomainKotlinJavaAbiTest {
    @Test
    void keepsTheJavaRecordAndStaticFieldSurface() {
        assertTrue(CoreSettings.class.isRecord());
        assertArrayEquals(
                new String[] {
                    "maxHealth",
                    "damagePerEnemy",
                    "attackIntervalTicks",
                    "repairMaterial",
                    "repairHealthPerUnit",
                    "repairMaterialBaseCost",
                    "repairShardBaseCost",
                    "repairCostPerClearLevel",
                    "warningSound",
                    "warningVolume",
                    "warningPitch",
                    "warningMinIntervalTicks"
                },
                recordComponentNames(CoreSettings.class));
        assertEquals(20, CoreSettings.DEFAULT_ATTACK_INTERVAL_TICKS);
        assertEquals("IRON_INGOT", CoreSettings.DEFAULT_REPAIR_MATERIAL);

        CoreSettings settings = new CoreSettings(
                1_000,
                10,
                20,
                "IRON_INGOT",
                100,
                1,
                2,
                3);
        assertEquals(1_000, settings.maxHealth());
        assertEquals(20, settings.attackIntervalTicks());
        assertEquals("IRON_INGOT", settings.repairMaterial());

        assertTrue(CoreRepairCost.class.isRecord());
        assertArrayEquals(
                new String[] {
                    "repairAmount",
                    "repairUnits",
                    "vanillaMaterialAmount",
                    "defenseShardAmount",
                    "highestClearedLevel"
                },
                recordComponentNames(CoreRepairCost.class));

        CoreRepairCost direct = new CoreRepairCost(201L, 3L, 39L, 42L, 4L);
        assertEquals(201L, direct.repairAmount());
        assertEquals(3L, direct.repairUnits());
        assertEquals(39L, direct.vanillaMaterialAmount());
        assertEquals(42L, direct.defenseShardAmount());
        assertEquals(4L, direct.highestClearedLevel());

        CoreRepairCost calculated = CoreRepairCost.forMissing(201L, 4L, settings);
        assertEquals(direct, calculated);
    }

    private static String[] recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
