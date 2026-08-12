package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.tactical.TacticalBuildCategory;
import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinitionValidator;
import io.github.takenoha.towerdefense.tactical.TacticalBuildRarity;
import io.github.takenoha.towerdefense.tactical.TacticalEffectEntry;
import io.github.takenoha.towerdefense.tactical.TacticalEffectType;
import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalTargetCondition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Versioned, deterministic snapshot codec stored in the tactical SQLite tables. */
public final class TacticalDefinitionCodec {
    private static final String JSON_V1_PREFIX = "{\"format\":\"tdb1\",\"payload\":\"";
    private static final String JSON_V2_PREFIX = "{\"format\":\"tdb2\",\"payload\":\"";
    private static final String JSON_SUFFIX = "\"}";

    private TacticalDefinitionCodec() {
    }

    /** Encodes a definition as valid JSON with a binary-safe canonical payload. */
    public static String encode(TacticalBuildDefinition definition) {
        boolean hasBranchMetadata = definition.nodes().stream().anyMatch(
                node -> !node.prerequisiteNodeIds().isEmpty()
                        || node.exclusiveBranchGroup().isPresent()
                        || node.branchId().isPresent());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeDefinition(output, definition, hasBranchMetadata);
            }
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes.toByteArray());
            String prefix = hasBranchMetadata ? JSON_V2_PREFIX : JSON_V1_PREFIX;
            return prefix + payload + JSON_SUFFIX;
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode tactical definition", impossible);
        }
    }

    /** Decodes a definition and rejects unknown/truncated snapshots. */
    public static TacticalBuildDefinition decode(String encoded) {
        String prefix = prefixFor(encoded);
        if (prefix == null) {
            throw new IllegalArgumentException("invalid tactical definition snapshot envelope");
        }
        String payload = encoded.substring(
                prefix.length(), encoded.length() - JSON_SUFFIX.length());
        boolean hasBranchMetadata = prefix.equals(JSON_V2_PREFIX);
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payload);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                TacticalBuildDefinition definition = readDefinition(input, hasBranchMetadata);
                if (input.read() != -1) {
                    throw new IllegalArgumentException("trailing bytes in tactical definition snapshot");
                }
                TacticalBuildDefinitionValidator.validate(definition);
                return definition;
            }
        } catch (IllegalArgumentException | IOException invalid) {
            throw new IllegalArgumentException("invalid tactical definition snapshot", invalid);
        }
    }

    private static void writeDefinition(
            DataOutputStream output,
            TacticalBuildDefinition definition,
            boolean hasBranchMetadata) throws IOException {
        output.writeUTF(definition.id());
        output.writeInt(definition.version());
        output.writeUTF(definition.displayName());
        output.writeUTF(definition.description());
        output.writeUTF(definition.category().name());
        output.writeUTF(definition.rarity().name());
        output.writeBoolean(definition.enabled());
        output.writeInt(definition.weight());
        output.writeUTF(definition.iconMaterial());
        writeTowerTypes(output, definition.targetTowerTypes());
        output.writeBoolean(definition.requiredUnlockId().isPresent());
        definition.requiredUnlockId().ifPresent(value -> writeUtf(output, value));
        output.writeInt(definition.nodes().size());
        for (TacticalSkillNodeDefinition node : definition.nodes()) {
            output.writeUTF(node.id());
            output.writeInt(node.version());
            output.writeInt(node.tier());
            output.writeUTF(node.displayName());
            output.writeUTF(node.description());
            output.writeInt(node.effects().size());
            for (TacticalEffectEntry effect : node.effects()) {
                output.writeUTF(effect.type().name());
                writeTowerTypes(output, effect.towerTypes());
                output.writeDouble(effect.value());
                output.writeUTF(effect.condition().name());
                writeNullableDouble(output, effect.minimum());
                writeNullableDouble(output, effect.maximum());
            }
            if (hasBranchMetadata) {
                output.writeInt(node.prerequisiteNodeIds().size());
                for (String prerequisiteNodeId : node.prerequisiteNodeIds()) {
                    output.writeUTF(prerequisiteNodeId);
                }
                writeOptionalUtf(output, node.exclusiveBranchGroup());
                writeOptionalUtf(output, node.branchId());
            }
        }
    }

    private static TacticalBuildDefinition readDefinition(
            DataInputStream input,
            boolean hasBranchMetadata) throws IOException {
        String id = input.readUTF();
        int version = input.readInt();
        String displayName = input.readUTF();
        String description = input.readUTF();
        TacticalBuildCategory category = TacticalBuildCategory.valueOf(input.readUTF());
        TacticalBuildRarity rarity = TacticalBuildRarity.valueOf(input.readUTF());
        boolean enabled = input.readBoolean();
        int weight = input.readInt();
        String iconMaterial = input.readUTF();
        Set<TowerType> targets = readTowerTypes(input);
        Optional<String> requiredUnlockId = input.readBoolean()
                ? Optional.of(readUtf(input))
                : Optional.empty();
        int nodeCount = requireCount(input.readInt(), "node count");
        List<TacticalSkillNodeDefinition> nodes = new ArrayList<>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            String nodeId = input.readUTF();
            int nodeVersion = input.readInt();
            int tier = input.readInt();
            String nodeName = input.readUTF();
            String nodeDescription = input.readUTF();
            int effectCount = requireCount(input.readInt(), "effect count");
            List<TacticalEffectEntry> effects = new ArrayList<>(effectCount);
            for (int effectIndex = 0; effectIndex < effectCount; effectIndex++) {
                TacticalEffectType type = TacticalEffectType.valueOf(input.readUTF());
                Set<TowerType> effectTargets = readTowerTypes(input);
                double value = input.readDouble();
                TacticalTargetCondition condition = TacticalTargetCondition.valueOf(
                        input.readUTF());
                Double minimum = readNullableDouble(input);
                Double maximum = readNullableDouble(input);
                effects.add(new TacticalEffectEntry(
                        type, effectTargets, value, condition, minimum, maximum));
            }
            List<String> prerequisiteNodeIds = List.of();
            Optional<String> exclusiveBranchGroup = Optional.empty();
            Optional<String> branchId = Optional.empty();
            if (hasBranchMetadata) {
                int prerequisiteCount = requireCount(input.readInt(), "prerequisite count");
                List<String> prerequisites = new ArrayList<>(prerequisiteCount);
                for (int prerequisiteIndex = 0;
                        prerequisiteIndex < prerequisiteCount;
                        prerequisiteIndex++) {
                    prerequisites.add(input.readUTF());
                }
                prerequisiteNodeIds = List.copyOf(prerequisites);
                exclusiveBranchGroup = readOptionalUtf(input);
                branchId = readOptionalUtf(input);
            }
            nodes.add(new TacticalSkillNodeDefinition(
                    nodeId,
                    nodeVersion,
                    tier,
                    nodeName,
                    nodeDescription,
                    effects,
                    prerequisiteNodeIds,
                    exclusiveBranchGroup,
                    branchId));
        }
        return new TacticalBuildDefinition(
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
                nodes);
    }

    private static void writeTowerTypes(DataOutputStream output, Set<TowerType> types)
            throws IOException {
        List<TowerType> ordered = types.stream().sorted(Comparator.comparing(Enum::ordinal)).toList();
        output.writeInt(ordered.size());
        for (TowerType type : ordered) {
            output.writeUTF(type.name());
        }
    }

    private static Set<TowerType> readTowerTypes(DataInputStream input) throws IOException {
        int count = requireCount(input.readInt(), "tower type count");
        EnumSet<TowerType> types = EnumSet.noneOf(TowerType.class);
        for (int index = 0; index < count; index++) {
            types.add(TowerType.valueOf(input.readUTF()));
        }
        return types;
    }

    private static void writeNullableDouble(DataOutputStream output, Double value)
            throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeDouble(value);
        }
    }

    private static Double readNullableDouble(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readDouble() : null;
    }

    private static void writeOptionalUtf(DataOutputStream output, Optional<String> value)
            throws IOException {
        output.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            output.writeUTF(value.orElseThrow());
        }
    }

    private static Optional<String> readOptionalUtf(DataInputStream input) throws IOException {
        return input.readBoolean() ? Optional.of(input.readUTF()) : Optional.empty();
    }

    private static void writeUtf(DataOutputStream output, String value) {
        try {
            output.writeUTF(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not write tactical snapshot text", impossible);
        }
    }

    private static String readUtf(DataInputStream input) throws IOException {
        try {
            return input.readUTF();
        } catch (EOFException truncated) {
            throw truncated;
        }
    }

    private static int requireCount(int count, String name) {
        if (count < 0 || count > 1_000) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return count;
    }

    private static String prefixFor(String encoded) {
        if (encoded == null || !encoded.endsWith(JSON_SUFFIX)) {
            return null;
        }
        if (encoded.startsWith(JSON_V1_PREFIX)) {
            return JSON_V1_PREFIX;
        }
        if (encoded.startsWith(JSON_V2_PREFIX)) {
            return JSON_V2_PREFIX;
        }
        return null;
    }
}
