package io.github.takenoha.towerdefense.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreSettingsJvmRecordTest {
    @Test
    fun `keeps the Java record components and static defaults`() {
        val recordComponents = CoreSettings::class.java.getRecordComponents()

        assertTrue(CoreSettings::class.java.isRecord)
        assertNotNull(recordComponents)
        assertEquals(
            listOf(
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
                "warningMinIntervalTicks",
            ),
            recordComponents!!.map { it.name },
        )

        assertEquals(20, CoreSettings.DEFAULT_ATTACK_INTERVAL_TICKS)
        assertEquals("IRON_INGOT", CoreSettings.DEFAULT_REPAIR_MATERIAL)
        assertEquals(100, CoreSettings.DEFAULT_REPAIR_HEALTH_PER_UNIT)
        assertEquals(1.0, CoreSettings.DEFAULT_WARNING_VOLUME)
    }
}
