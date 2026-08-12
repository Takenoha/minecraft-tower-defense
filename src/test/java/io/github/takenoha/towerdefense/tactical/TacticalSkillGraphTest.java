package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.TacticalDefinitionCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class TacticalSkillGraphTest {
    @Test
    void arrowSpecializationContainsTwoThreeTierExclusiveBranches() {
        TacticalBuildDefinition definition = TacticalBuildCatalog.defaults()
                .require("arrow-specialization");

        assertEquals(6, definition.nodes().size());
        assertEquals(
                Set.of("rapid-fire", "range"),
                definition.nodes().stream()
                        .map(TacticalSkillNodeDefinition::branchId)
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of("arrow-path"),
                definition.nodes().stream()
                        .map(TacticalSkillNodeDefinition::exclusiveBranchGroup)
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(1, 2, 3),
                definition.nodes().stream()
                        .map(TacticalSkillNodeDefinition::tier)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                3,
                definition.nodes().stream()
                        .filter(node -> node.branchId().equals(Optional.of("rapid-fire")))
                        .count());
        assertEquals(
                3,
                definition.nodes().stream()
                        .filter(node -> node.branchId().equals(Optional.of("range")))
                        .count());

        TacticalSkillNodeDefinition rapidTierTwo = definition.nodes().stream()
                .filter(node -> node.id().equals("arrow-specialization-rapid-fire-tier-2"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of("arrow-specialization-rapid-fire-tier-1"),
                rapidTierTwo.prerequisiteNodeIds());
        assertEquals(
                Optional.of("rapid-fire"),
                rapidTierTwo.snapshot().branchId());
    }

    @Test
    void validatorRejectsUnknownDuplicateSelfCyclicAndBackwardPrerequisites() {
        TacticalBuildDefinition definition = TacticalBuildCatalog.defaults()
                .require("arrow-specialization");
        TacticalSkillNodeDefinition tierTwo = node(definition, "arrow-specialization-rapid-fire-tier-2");

        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validate(withNode(
                        definition,
                        tierTwo,
                        original -> withPrerequisites(original, List.of("missing-node")))));
        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validate(withNode(
                        definition,
                        tierTwo,
                        original -> withPrerequisites(
                                original,
                                List.of("arrow-specialization-rapid-fire-tier-1",
                                        "arrow-specialization-rapid-fire-tier-1")))));
        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validate(withNode(
                        definition,
                        tierTwo,
                        original -> withPrerequisites(
                                original,
                                List.of("arrow-specialization-rapid-fire-tier-2")))));
        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validate(withNode(
                        definition,
                        tierTwo,
                        original -> withPrerequisites(
                                original,
                                List.of("arrow-specialization-rapid-fire-tier-3")))));

        TacticalSkillNodeDefinition tierOne = node(
                definition,
                "arrow-specialization-rapid-fire-tier-1");
        TacticalBuildDefinition cyclic = withNode(
                withNode(
                        definition,
                        tierOne,
                        original -> withPrerequisites(
                                original,
                                List.of("arrow-specialization-rapid-fire-tier-2"))),
                tierTwo,
                original -> withPrerequisites(
                        original,
                        List.of("arrow-specialization-rapid-fire-tier-1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> TacticalBuildDefinitionValidator.validate(cyclic));
    }

    @Test
    void codecRoundTripsBranchMetadataAndKeepsLegacyEnvelopeReadable() {
        TacticalBuildCatalog catalog = TacticalBuildCatalog.defaults();
        TacticalBuildDefinition branched = catalog.require("arrow-specialization");
        TacticalBuildDefinition linear = catalog.require("rapid-fire");

        String branchedEncoded = TacticalDefinitionCodec.encode(branched);
        assertTrue(branchedEncoded.startsWith("{\"format\":\"tdb2\""));
        assertEquals(branched, TacticalDefinitionCodec.decode(branchedEncoded));

        String legacyEncoded = TacticalDefinitionCodec.encode(linear);
        assertTrue(legacyEncoded.startsWith("{\"format\":\"tdb1\""));
        TacticalBuildDefinition legacyDecoded = TacticalDefinitionCodec.decode(legacyEncoded);
        assertEquals(linear, legacyDecoded);
        assertTrue(legacyDecoded.nodes().stream()
                .allMatch(node -> node.prerequisiteNodeIds().isEmpty()
                        && node.exclusiveBranchGroup().isEmpty()
                        && node.branchId().isEmpty()));
    }

    @Test
    void linearNodesKeepAllBranchMetadataEmptyThroughLegacyConstructor() {
        TacticalSkillNodeDefinition node = new TacticalSkillNodeDefinition(
                "rapid-fire-tier-1",
                1,
                1,
                "Tier 1",
                "Faster arrows",
                List.of());

        assertTrue(node.prerequisiteNodeIds().isEmpty());
        assertTrue(node.exclusiveBranchGroup().isEmpty());
        assertTrue(node.branchId().isEmpty());
        assertFalse(node.snapshot().branchId().isPresent());
    }

    @Test
    void compilerUsesOnlyTheExplicitlyUnlockedBranchNodes() {
        TacticalBuildDefinition definition = TacticalBuildCatalog.defaults()
                .require("arrow-specialization");
        TacticalEffectSnapshot effects = new TacticalEffectCompiler().compile(
                definition.selectionView(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        2,
                        Optional.of("rapid-fire"),
                        Set.of(
                                "arrow-specialization-rapid-fire-tier-1",
                                "arrow-specialization-rapid-fire-tier-2")));

        assertEquals(
                0.92d,
                effects.attackIntervalMultiplier(TowerType.ARROW, TacticalTargetContext.neutral()),
                0.000001d);
        assertEquals(
                1.10d,
                effects.damageMultiplier(TowerType.ARROW, TacticalTargetContext.neutral()),
                0.000001d);
        assertEquals(0.0d, effects.rangeAdd(TowerType.ARROW), 0.000001d);
    }

    private static TacticalSkillNodeDefinition node(
            TacticalBuildDefinition definition,
            String nodeId) {
        return definition.nodes().stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst()
                .orElseThrow();
    }

    private static TacticalSkillNodeDefinition withPrerequisites(
            TacticalSkillNodeDefinition original,
            List<String> prerequisites) {
        return new TacticalSkillNodeDefinition(
                original.id(),
                original.version(),
                original.tier(),
                original.displayName(),
                original.description(),
                original.effects(),
                prerequisites,
                original.exclusiveBranchGroup(),
                original.branchId());
    }

    private static TacticalBuildDefinition withNode(
            TacticalBuildDefinition definition,
            TacticalSkillNodeDefinition target,
            UnaryOperator<TacticalSkillNodeDefinition> update) {
        List<TacticalSkillNodeDefinition> nodes = new ArrayList<>(definition.nodes());
        int index = nodes.indexOf(target);
        nodes.set(index, update.apply(target));
        return new TacticalBuildDefinition(
                definition.id(),
                definition.version(),
                definition.displayName(),
                definition.description(),
                definition.category(),
                definition.rarity(),
                definition.enabled(),
                definition.weight(),
                definition.iconMaterial(),
                definition.targetTowerTypes(),
                definition.requiredUnlockId(),
                nodes);
    }
}
