package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.TowerType
import io.github.takenoha.towerdefense.tactical.TacticalBuildCategory
import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition
import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinitionValidator
import io.github.takenoha.towerdefense.tactical.TacticalBuildRarity
import io.github.takenoha.towerdefense.tactical.TacticalEffectEntry
import io.github.takenoha.towerdefense.tactical.TacticalEffectType
import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition
import io.github.takenoha.towerdefense.tactical.TacticalTargetCondition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.ArrayList
import java.util.Base64
import java.util.EnumSet
import java.util.Optional

/** Versioned, deterministic snapshot codec stored in the tactical SQLite tables. */
class TacticalDefinitionCodec private constructor() {
    companion object {
        private const val JSON_V1_PREFIX = "{\"format\":\"tdb1\",\"payload\":\""
        private const val JSON_V2_PREFIX = "{\"format\":\"tdb2\",\"payload\":\""
        private const val JSON_SUFFIX = "\"}"

        /** Encodes a definition as valid JSON with a binary-safe canonical payload. */
        @JvmStatic
        fun encode(definition: TacticalBuildDefinition): String {
            val hasBranchMetadata = definition.nodes().any { node ->
                node.prerequisiteNodeIds().isNotEmpty()
                    || node.exclusiveBranchGroup().isPresent
                    || node.branchId().isPresent
            }
            return try {
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { output ->
                    writeDefinition(output, definition, hasBranchMetadata)
                }
                val payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes.toByteArray())
                val prefix = if (hasBranchMetadata) JSON_V2_PREFIX else JSON_V1_PREFIX
                prefix + payload + JSON_SUFFIX
            } catch (impossible: IOException) {
                throw IllegalStateException("Could not encode tactical definition", impossible)
            }
        }

        /** Decodes a definition and rejects unknown/truncated snapshots. */
        @JvmStatic
        fun decode(encoded: String?): TacticalBuildDefinition {
            val prefix = prefixFor(encoded)
                ?: throw IllegalArgumentException("invalid tactical definition snapshot envelope")
            val snapshot = encoded ?: throw IllegalArgumentException(
                "invalid tactical definition snapshot envelope",
            )
            val payload = snapshot.substring(prefix.length, snapshot.length - JSON_SUFFIX.length)
            val hasBranchMetadata = prefix == JSON_V2_PREFIX
            return try {
                val bytes = Base64.getUrlDecoder().decode(payload)
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val definition = readDefinition(input, hasBranchMetadata)
                    if (input.read() != -1) {
                        throw IllegalArgumentException(
                            "trailing bytes in tactical definition snapshot",
                        )
                    }
                    TacticalBuildDefinitionValidator.validate(definition)
                    definition
                }
            } catch (invalid: IllegalArgumentException) {
                throw IllegalArgumentException("invalid tactical definition snapshot", invalid)
            } catch (invalid: IOException) {
                throw IllegalArgumentException("invalid tactical definition snapshot", invalid)
            }
        }

        private fun writeDefinition(
            output: DataOutputStream,
            definition: TacticalBuildDefinition,
            hasBranchMetadata: Boolean,
        ) {
            try {
                output.writeUTF(definition.id())
                output.writeInt(definition.version())
                output.writeUTF(definition.displayName())
                output.writeUTF(definition.description())
                output.writeUTF(definition.category().name)
                output.writeUTF(definition.rarity().name)
                output.writeBoolean(definition.enabled())
                output.writeInt(definition.weight())
                output.writeUTF(definition.iconMaterial())
                writeTowerTypes(output, definition.targetTowerTypes())
                output.writeBoolean(definition.requiredUnlockId().isPresent)
                definition.requiredUnlockId().ifPresent { value -> writeUtf(output, value) }
                output.writeInt(definition.nodes().size)
                for (node in definition.nodes()) {
                    output.writeUTF(node.id())
                    output.writeInt(node.version())
                    output.writeInt(node.tier())
                    output.writeUTF(node.displayName())
                    output.writeUTF(node.description())
                    output.writeInt(node.effects().size)
                    for (effect in node.effects()) {
                        output.writeUTF(effect.type().name)
                        writeTowerTypes(output, effect.towerTypes())
                        output.writeDouble(effect.value())
                        output.writeUTF(effect.condition().name)
                        writeNullableDouble(output, effect.minimum())
                        writeNullableDouble(output, effect.maximum())
                    }
                    if (hasBranchMetadata) {
                        output.writeInt(node.prerequisiteNodeIds().size)
                        for (prerequisiteNodeId in node.prerequisiteNodeIds()) {
                            output.writeUTF(prerequisiteNodeId)
                        }
                        writeOptionalUtf(output, node.exclusiveBranchGroup())
                        writeOptionalUtf(output, node.branchId())
                    }
                }
            } catch (impossible: IOException) {
                throw IllegalStateException("Could not write tactical snapshot", impossible)
            }
        }

        private fun readDefinition(
            input: DataInputStream,
            hasBranchMetadata: Boolean,
        ): TacticalBuildDefinition {
            val id = input.readUTF()
            val version = input.readInt()
            val displayName = input.readUTF()
            val description = input.readUTF()
            val category = TacticalBuildCategory.valueOf(input.readUTF())
            val rarity = TacticalBuildRarity.valueOf(input.readUTF())
            val enabled = input.readBoolean()
            val weight = input.readInt()
            val iconMaterial = input.readUTF()
            val targets = readTowerTypes(input)
            val requiredUnlockId: Optional<String> = if (input.readBoolean()) {
                Optional.of(readUtf(input))
            } else {
                Optional.empty()
            }
            val nodeCount = requireCount(input.readInt(), "node count")
            val nodes = ArrayList<TacticalSkillNodeDefinition>(nodeCount)
            repeat(nodeCount) {
                val nodeId = input.readUTF()
                val nodeVersion = input.readInt()
                val tier = input.readInt()
                val nodeName = input.readUTF()
                val nodeDescription = input.readUTF()
                val effectCount = requireCount(input.readInt(), "effect count")
                val effects = ArrayList<TacticalEffectEntry>(effectCount)
                repeat(effectCount) {
                    val type = TacticalEffectType.valueOf(input.readUTF())
                    val effectTargets = readTowerTypes(input)
                    val value = input.readDouble()
                    val condition = TacticalTargetCondition.valueOf(input.readUTF())
                    val minimum = readNullableDouble(input)
                    val maximum = readNullableDouble(input)
                    effects.add(TacticalEffectEntry(
                        type,
                        effectTargets,
                        value,
                        condition,
                        minimum,
                        maximum,
                    ))
                }
                var prerequisiteNodeIds: List<String> = emptyList()
                var exclusiveBranchGroup: Optional<String> = Optional.empty()
                var branchId: Optional<String> = Optional.empty()
                if (hasBranchMetadata) {
                    val prerequisiteCount = requireCount(
                        input.readInt(),
                        "prerequisite count",
                    )
                    val prerequisites = ArrayList<String>(prerequisiteCount)
                    repeat(prerequisiteCount) {
                        prerequisites.add(input.readUTF())
                    }
                    prerequisiteNodeIds = prerequisites.toList()
                    exclusiveBranchGroup = readOptionalUtf(input)
                    branchId = readOptionalUtf(input)
                }
                nodes.add(TacticalSkillNodeDefinition(
                    nodeId,
                    nodeVersion,
                    tier,
                    nodeName,
                    nodeDescription,
                    java.util.List.copyOf(effects),
                    java.util.List.copyOf(prerequisiteNodeIds),
                    exclusiveBranchGroup,
                    branchId,
                ))
            }
            return TacticalBuildDefinition(
                id,
                version,
                displayName,
                description,
                category,
                rarity,
                enabled,
                weight,
                iconMaterial,
                targets,
                requiredUnlockId,
                nodes,
            )
        }

        private fun writeTowerTypes(output: DataOutputStream, types: Set<TowerType>) {
            try {
                val ordered = types.sortedBy { type -> type.ordinal }
                output.writeInt(ordered.size)
                for (type in ordered) {
                    output.writeUTF(type.name)
                }
            } catch (impossible: IOException) {
                throw IllegalStateException("Could not write tactical tower types", impossible)
            }
        }

        private fun readTowerTypes(input: DataInputStream): Set<TowerType> {
            val count = requireCount(input.readInt(), "tower type count")
            val types = EnumSet.noneOf(TowerType::class.java)
            repeat(count) {
                types.add(TowerType.valueOf(input.readUTF()))
            }
            return types
        }

        private fun writeNullableDouble(output: DataOutputStream, value: Double?) {
            try {
                output.writeBoolean(value != null)
                if (value != null) {
                    output.writeDouble(value)
                }
            } catch (impossible: IOException) {
                throw IllegalStateException("Could not write tactical numeric value", impossible)
            }
        }

        private fun readNullableDouble(input: DataInputStream): Double? =
            if (input.readBoolean()) input.readDouble() else null

        private fun writeOptionalUtf(output: DataOutputStream, value: Optional<String>) {
            try {
                output.writeBoolean(value.isPresent)
                if (value.isPresent) {
                    output.writeUTF(value.get())
                }
            } catch (impossible: IOException) {
                throw IllegalStateException("Could not write tactical optional text", impossible)
            }
        }

        private fun readOptionalUtf(input: DataInputStream): Optional<String> =
            if (input.readBoolean()) Optional.of(input.readUTF()) else Optional.empty()

        private fun writeUtf(output: DataOutputStream, value: String) {
            try {
                output.writeUTF(value)
            } catch (impossible: IOException) {
                throw IllegalStateException("Could not write tactical snapshot text", impossible)
            }
        }

        private fun readUtf(input: DataInputStream): String = input.readUTF()

        private fun requireCount(count: Int, name: String): Int {
            if (count < 0 || count > 1_000) {
                throw IllegalArgumentException("$name is outside the supported range")
            }
            return count
        }

        private fun prefixFor(encoded: String?): String? {
            if (encoded == null || !encoded.endsWith(JSON_SUFFIX)) {
                return null
            }
            return when {
                encoded.startsWith(JSON_V1_PREFIX) -> JSON_V1_PREFIX
                encoded.startsWith(JSON_V2_PREFIX) -> JSON_V2_PREFIX
                else -> null
            }
        }
    }
}
