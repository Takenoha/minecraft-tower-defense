package io.github.takenoha.towerdefense.domain

import io.github.takenoha.towerdefense.config.CoreSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreRepairCostJvmRecordTest {
    @Test
    fun `keeps the Java record surface while using the Kotlin implementation`() {
        val recordComponents = CoreRepairCost::class.java.getRecordComponents()

        assertTrue(CoreRepairCost::class.java.isRecord)
        assertNotNull(recordComponents)
        assertEquals(
            listOf(
                "repairAmount",
                "repairUnits",
                "vanillaMaterialAmount",
                "defenseShardAmount",
                "highestClearedLevel",
            ),
            recordComponents!!.map { it.name },
        )

        val cost = CoreRepairCost.forMissing(
            201L,
            4L,
            CoreSettings(1_000, 10, 20, "IRON_INGOT", 100, 1, 2, 3),
        )

        assertEquals(201L, cost.repairAmount)
        assertEquals(3L, cost.repairUnits)
        assertEquals(39L, cost.vanillaMaterialAmount)
        assertEquals(42L, cost.defenseShardAmount)
        assertEquals(4L, cost.highestClearedLevel)
    }
}
