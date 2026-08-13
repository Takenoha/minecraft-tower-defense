package io.github.takenoha.towerdefense.tactical;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Fail-closed validation for configured tactical definitions. */
public final class TacticalBuildDefinitionValidator {
    private TacticalBuildDefinitionValidator() {
    }

    public static void validate(TacticalBuildDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (!definition.id().matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("build id must be lowercase slug: " + definition.id());
        }
        List<TacticalSkillNodeDefinition> nodes = definition.nodes();
        if (nodes.size() != 6) {
            throw new IllegalArgumentException("a build must define exactly six nodes");
        }

        Map<String, TacticalSkillNodeDefinition> byId = new HashMap<>();
        for (TacticalSkillNodeDefinition node : nodes) {
            if (node == null) {
                throw new IllegalArgumentException("a build cannot contain a null node");
            }
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate tactical node id: " + node.id());
            }
            if (!node.id().startsWith(definition.id() + "-")) {
                throw new IllegalArgumentException(
                        "node id must be namespaced by its build id: " + node.id());
            }
            for (TacticalEffectEntry effect : node.effects()) {
                validateEffect(effect);
            }
            validateBranchMetadata(node);
        }

        validateTierShape(nodes);
        validatePrerequisiteReferences(nodes, byId);
        validateAcyclic(nodes, byId);
        validatePrerequisiteTiers(nodes, byId);
        validateBranchTopology(nodes);
    }

    public static void validateAll(List<TacticalBuildDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("at least one tactical build is required");
        }
        Set<String> ids = new HashSet<>();
        for (TacticalBuildDefinition definition : definitions) {
            validate(definition);
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("duplicate tactical build id: " + definition.id());
            }
        }
    }

    private static void validateTierShape(List<TacticalSkillNodeDefinition> nodes) {
        boolean branched = nodes.stream().anyMatch(node -> node.branchId().isPresent());
        if (!branched) {
            Set<Integer> tiers = new HashSet<>();
            for (TacticalSkillNodeDefinition node : nodes) {
                if (!tiers.add(node.tier())) {
                    throw new IllegalArgumentException(
                            "a linear build cannot define multiple nodes for tier " + node.tier());
                }
            }
            for (int tier = 1; tier <= 6; tier++) {
                if (!tiers.contains(tier)) {
                    throw new IllegalArgumentException("missing tactical tier " + tier);
                }
            }
        }
    }

    private static void validatePrerequisiteReferences(
            List<TacticalSkillNodeDefinition> nodes,
            Map<String, TacticalSkillNodeDefinition> byId) {
        for (TacticalSkillNodeDefinition node : nodes) {
            Set<String> seen = new HashSet<>();
            for (String prerequisiteId : node.prerequisiteNodeIds()) {
                if (prerequisiteId == null || prerequisiteId.isBlank()) {
                    throw new IllegalArgumentException(
                            "prerequisite node id must not be blank for " + node.id());
                }
                if (!seen.add(prerequisiteId)) {
                    throw new IllegalArgumentException(
                            "duplicate prerequisite node id " + prerequisiteId
                                    + " for " + node.id());
                }
                TacticalSkillNodeDefinition prerequisite = byId.get(prerequisiteId);
                if (prerequisite == null) {
                    throw new IllegalArgumentException(
                            "unknown prerequisite node " + prerequisiteId
                                    + " for " + node.id());
                }
                if (prerequisite.id().equals(node.id())) {
                    throw new IllegalArgumentException("node cannot prerequisite itself: " + node.id());
                }
            }
        }
    }

    private static void validatePrerequisiteTiers(
            List<TacticalSkillNodeDefinition> nodes,
            Map<String, TacticalSkillNodeDefinition> byId) {
        for (TacticalSkillNodeDefinition node : nodes) {
            for (String prerequisiteId : node.prerequisiteNodeIds()) {
                TacticalSkillNodeDefinition prerequisite = byId.get(prerequisiteId);
                if (prerequisite.tier() >= node.tier()) {
                    throw new IllegalArgumentException(
                            "prerequisite tier must be lower than node tier: "
                                    + prerequisiteId + " -> " + node.id());
                }
            }
        }
    }

    private static void validateBranchMetadata(TacticalSkillNodeDefinition node) {
        Optional<String> group = node.exclusiveBranchGroup();
        Optional<String> branch = node.branchId();
        if (group.isPresent() != branch.isPresent()) {
            throw new IllegalArgumentException(
                    "exclusive branch group and branch id must be present together: " + node.id());
        }
        group.ifPresent(value -> validateSlug(value, "exclusive branch group", node.id()));
        branch.ifPresent(value -> validateSlug(value, "branch id", node.id()));
    }

    private static void validateBranchTopology(List<TacticalSkillNodeDefinition> nodes) {
        Map<String, Set<String>> branchesByGroup = new HashMap<>();
        Map<String, String> groupByBranch = new HashMap<>();
        Map<String, Set<Integer>> tiersByBranch = new HashMap<>();
        for (TacticalSkillNodeDefinition node : nodes) {
            if (node.branchId().isEmpty()) {
                continue;
            }
            String group = node.exclusiveBranchGroup().orElseThrow();
            String branch = node.branchId().orElseThrow();
            String previousGroup = groupByBranch.putIfAbsent(branch, group);
            if (previousGroup != null && !previousGroup.equals(group)) {
                throw new IllegalArgumentException(
                        "branch id belongs to multiple exclusive groups: " + branch);
            }
            branchesByGroup.computeIfAbsent(group, ignored -> new HashSet<>()).add(branch);
            String branchKey = group + "\u0000" + branch;
            if (!tiersByBranch.computeIfAbsent(branchKey, ignored -> new HashSet<>())
                    .add(node.tier())) {
                throw new IllegalArgumentException(
                        "branch cannot define multiple nodes for tier " + node.tier()
                                + ": " + branch);
            }
        }
        for (Map.Entry<String, Set<String>> entry : branchesByGroup.entrySet()) {
            if (entry.getValue().size() < 2) {
                throw new IllegalArgumentException(
                        "exclusive branch group must contain at least two branches: "
                                + entry.getKey());
            }
        }
        for (Map.Entry<String, Set<Integer>> entry : tiersByBranch.entrySet()) {
            int highestTier = entry.getValue().stream().mapToInt(Integer::intValue).max().orElseThrow();
            for (int tier = 1; tier <= highestTier; tier++) {
                if (!entry.getValue().contains(tier)) {
                    throw new IllegalArgumentException(
                            "branch tiers must start at one and be contiguous: " + entry.getKey());
                }
            }
        }
    }

    private static void validateAcyclic(
            List<TacticalSkillNodeDefinition> nodes,
            Map<String, TacticalSkillNodeDefinition> byId) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (TacticalSkillNodeDefinition node : nodes) {
            visit(node.id(), byId, visiting, visited);
        }
    }

    private static void visit(
            String nodeId,
            Map<String, TacticalSkillNodeDefinition> byId,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(nodeId)) {
            return;
        }
        if (!visiting.add(nodeId)) {
            throw new IllegalArgumentException("cyclic tactical prerequisites at: " + nodeId);
        }
        TacticalSkillNodeDefinition node = byId.get(nodeId);
        for (String prerequisiteId : node.prerequisiteNodeIds()) {
            visit(prerequisiteId, byId, visiting, visited);
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
    }

    private static void validateSlug(String value, String name, String nodeId) {
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be lowercase slug for " + nodeId);
        }
    }

    private static void validateEffect(TacticalEffectEntry effect) {
        if (effect.value() == 0.0d && effect.type() != TacticalEffectType.CHAIN_COUNT_ADD) {
            throw new IllegalArgumentException("tactical effect values must not be zero");
        }
        if (effect.type() == TacticalEffectType.CHAIN_COUNT_ADD
                && effect.value() != Math.rint(effect.value())) {
            throw new IllegalArgumentException("chain count additions must be whole numbers");
        }
        if (effect.type().name().endsWith("MULTIPLIER") && effect.value() <= 0.0d) {
            throw new IllegalArgumentException("multiplier effects must be positive");
        }
    }
}
