package io.github.takenoha.towerdefense.tactical;

import java.util.HashSet;
import java.util.List;
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
        Set<Integer> tiers = new HashSet<>();
        Set<String> nodeIds = new HashSet<>();
        for (TacticalSkillNodeDefinition node : nodes) {
            if (!tiers.add(node.tier())) {
                throw new IllegalArgumentException(
                        "a build cannot define multiple nodes for tier " + node.tier());
            }
            if (!nodeIds.add(node.id())) {
                throw new IllegalArgumentException("duplicate tactical node id: " + node.id());
            }
            if (!node.id().startsWith(definition.id() + "-")) {
                throw new IllegalArgumentException(
                        "node id must be namespaced by its build id: " + node.id());
            }
            for (TacticalEffectEntry effect : node.effects()) {
                validateEffect(effect);
            }
        }
        for (int tier = 1; tier <= 6; tier++) {
            if (!tiers.contains(tier)) {
                throw new IllegalArgumentException("missing tactical tier " + tier);
            }
        }
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
