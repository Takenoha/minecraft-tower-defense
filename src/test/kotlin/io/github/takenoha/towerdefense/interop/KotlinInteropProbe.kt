package io.github.takenoha.towerdefense.interop

import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition
import java.io.IOException
import java.util.Optional

/** Test-only surface used to record the Java/Kotlin interop rules for the migration. */
object KotlinInteropProbe {
    @JvmStatic
    fun javaRecordId(node: TacticalSkillNodeDefinition): String = node.id()

    @JvmStatic
    fun javaRecordBranchId(node: TacticalSkillNodeDefinition): String? = node.branchId().orElse(null)

    @JvmStatic
    fun nullableToOptional(value: String?): Optional<String> = Optional.ofNullable(value)

    @JvmStatic
    fun optionalToNullable(value: Optional<String>): String? = value.orElse(null)

    @JvmStatic
    @Throws(IOException::class)
    fun throwChecked(): Unit = throw IOException("Kotlin interop checked-exception probe")

    @JvmStatic
    fun immutableValues(): List<String> = listOf("alpha", "beta")
}
