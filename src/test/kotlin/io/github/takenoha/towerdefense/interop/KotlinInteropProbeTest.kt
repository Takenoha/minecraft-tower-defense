package io.github.takenoha.towerdefense.interop

import io.github.takenoha.towerdefense.persistence.SchemaMigrator
import io.github.takenoha.towerdefense.persistence.TacticalDefinitionCodec
import io.github.takenoha.towerdefense.tactical.TacticalBuildCatalog
import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition
import java.io.IOException
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinInteropProbeTest {
    @Test
    fun readsJavaRecordAccessorsAndOptionalBranchMetadata() {
        val node = TacticalSkillNodeDefinition(
            "interop-node",
            1,
            2,
            "Interop node",
            "Interop probe",
            listOf(),
            listOf("parent-node"),
            Optional.of("interop-group"),
            Optional.of("interop-branch"),
        )

        assertEquals("interop-node", KotlinInteropProbe.javaRecordId(node))
        assertEquals("interop-branch", KotlinInteropProbe.javaRecordBranchId(node))
        assertEquals(listOf("parent-node"), node.prerequisiteNodeIds())
    }

    @Test
    fun makesOptionalAndNullableConversionsExplicit() {
        assertEquals(Optional.of("value"), KotlinInteropProbe.nullableToOptional("value"))
        assertEquals(Optional.empty<String>(), KotlinInteropProbe.nullableToOptional(null))
        assertEquals("value", KotlinInteropProbe.optionalToNullable(Optional.of("value")))
        assertNull(KotlinInteropProbe.optionalToNullable(Optional.empty()))
    }

    @Test
    fun checkedExceptionBoundaryIsDeclaredAndPreserved() {
        assertThrows(IOException::class.java) { KotlinInteropProbe.throwChecked() }
    }

    @Test
    fun immutableKotlinCollectionIsNotMutableAtRuntime() {
        val values = KotlinInteropProbe.immutableValues()

        assertEquals(listOf("alpha", "beta"), values)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (values as MutableList<String>).add("gamma")
        }
    }

    @Test
    fun KotlinReadsJavaProductionCodecAndSchemaContract() {
        val branched = TacticalBuildCatalog.defaults().require("arrow-specialization")
        val encoded = TacticalDefinitionCodec.encode(branched)

        assertTrue(encoded.startsWith("{\"format\":\"tdb2\""))
        assertEquals(branched, TacticalDefinitionCodec.decode(encoded))
        assertEquals(40, SchemaMigrator.CURRENT_VERSION)
        assertFalse(branched.branchIds().isEmpty())
    }
}
