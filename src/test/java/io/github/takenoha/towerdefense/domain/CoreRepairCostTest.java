package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.takenoha.towerdefense.config.CoreSettings;
import org.junit.jupiter.api.Test;

final class CoreRepairCostTest {
    @Test
    void roundsMissingHealthToRepairUnitsAndScalesByClearedLevel() {
        CoreSettings settings = new CoreSettings(
                1_000,
                10,
                20,
                "IRON_INGOT",
                100,
                1,
                2,
                3);

        CoreRepairCost cost = CoreRepairCost.forMissing(201L, 4L, settings);

        assertEquals(201L, cost.repairAmount());
        assertEquals(3L, cost.repairUnits());
        assertEquals(39L, cost.vanillaMaterialAmount());
        assertEquals(42L, cost.defenseShardAmount());
        assertEquals(4L, cost.highestClearedLevel());
    }

    @Test
    void exactHealthDoesNotCreateAZeroCostQuote() {
        CoreSettings settings = new CoreSettings(1_000, 10, 20);

        CoreRepairCost cost = CoreRepairCost.forMissing(100L, 0L, settings);

        assertEquals(100L, cost.repairAmount());
        assertEquals(1L, cost.repairUnits());
        assertEquals(1L, cost.vanillaMaterialAmount());
        assertEquals(1L, cost.defenseShardAmount());
    }
}
