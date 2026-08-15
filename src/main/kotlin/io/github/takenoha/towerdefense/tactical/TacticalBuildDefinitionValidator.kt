package io.github.takenoha.towerdefense.tactical

import java.util.HashMap
import java.util.HashSet
import java.util.Optional

/** Fail-closed validation for configured tactical definitions. */
class TacticalBuildDefinitionValidator private constructor() {
    companion object {
        @JvmStatic
        fun validate(definition: TacticalBuildDefinition?) {
            if (definition == null) {
                throw IllegalArgumentException("definition must not be null")
            }
            if (!definition.id().matches(Regex("[a-z0-9][a-z0-9_-]{1,63}"))) {
                throw IllegalArgumentException("build id must be lowercase slug: ${definition.id()}")
            }
            val nodes = definition.nodes()
            if (nodes.size != 6) {
                throw IllegalArgumentException("a build must define exactly six nodes")
            }

            val byId = HashMap<String, TacticalSkillNodeDefinition>()
            for (node in nodes) {
                if (node == null) {
                    throw IllegalArgumentException("a build cannot contain a null node")
                }
                if (byId.putIfAbsent(node.id(), node) != null) {
                    throw IllegalArgumentException("duplicate tactical node id: ${node.id()}")
                }
                if (!node.id().startsWith(definition.id() + "-")) {
                    throw IllegalArgumentException(
                        "node id must be namespaced by its build id: ${node.id()}"
                    )
                }
                for (effect in node.effects()) {
                    validateEffect(effect)
                }
                validateBranchMetadata(node)
            }

            validateTierShape(nodes)
            validatePrerequisiteReferences(nodes, byId)
            validateAcyclic(nodes, byId)
            validatePrerequisiteTiers(nodes, byId)
            validateBranchTopology(nodes)
        }

        @JvmStatic
        fun validateAll(definitions: List<TacticalBuildDefinition>?) {
            if (definitions == null || definitions.isEmpty()) {
                throw IllegalArgumentException("at least one tactical build is required")
            }
            val ids = HashSet<String>()
            for (definition in definitions) {
                validate(definition)
                if (!ids.add(definition.id())) {
                    throw IllegalArgumentException("duplicate tactical build id: ${definition.id()}")
                }
            }
        }

        @JvmStatic
        private fun validateTierShape(nodes: List<TacticalSkillNodeDefinition>) {
            val branched = nodes.any { it.branchId().isPresent }
            if (!branched) {
                val tiers = HashSet<Int>()
                for (node in nodes) {
                    if (!tiers.add(node.tier())) {
                        throw IllegalArgumentException(
                            "a linear build cannot define multiple nodes for tier ${node.tier()}"
                        )
                    }
                }
                for (tier in 1..6) {
                    if (!tiers.contains(tier)) {
                        throw IllegalArgumentException("missing tactical tier $tier")
                    }
                }
            }
        }

        @JvmStatic
        private fun validatePrerequisiteReferences(
            nodes: List<TacticalSkillNodeDefinition>,
            byId: Map<String, TacticalSkillNodeDefinition>,
        ) {
            for (node in nodes) {
                val seen = HashSet<String>()
                for (prerequisiteId in node.prerequisiteNodeIds()) {
                    if (prerequisiteId == null || prerequisiteId.isBlank()) {
                        throw IllegalArgumentException(
                            "prerequisite node id must not be blank for ${node.id()}"
                        )
                    }
                    if (!seen.add(prerequisiteId)) {
                        throw IllegalArgumentException(
                            "duplicate prerequisite node id $prerequisiteId for ${node.id()}"
                        )
                    }
                    val prerequisite = byId[prerequisiteId]
                        ?: throw IllegalArgumentException(
                            "unknown prerequisite node $prerequisiteId for ${node.id()}"
                        )
                    if (prerequisite.id() == node.id()) {
                        throw IllegalArgumentException("node cannot prerequisite itself: ${node.id()}")
                    }
                }
            }
        }

        @JvmStatic
        private fun validatePrerequisiteTiers(
            nodes: List<TacticalSkillNodeDefinition>,
            byId: Map<String, TacticalSkillNodeDefinition>,
        ) {
            for (node in nodes) {
                for (prerequisiteId in node.prerequisiteNodeIds()) {
                    val prerequisite = byId[prerequisiteId]!!
                    if (prerequisite.tier() >= node.tier()) {
                        throw IllegalArgumentException(
                            "prerequisite tier must be lower than node tier: "
                                + "$prerequisiteId -> ${node.id()}"
                        )
                    }
                }
            }
        }

        @JvmStatic
        private fun validateBranchMetadata(node: TacticalSkillNodeDefinition) {
            val group = node.exclusiveBranchGroup()
            val branch = node.branchId()
            if (group.isPresent != branch.isPresent) {
                throw IllegalArgumentException(
                    "exclusive branch group and branch id must be present together: ${node.id()}"
                )
            }
            group.ifPresent { value -> validateSlug(value, "exclusive branch group", node.id()) }
            branch.ifPresent { value -> validateSlug(value, "branch id", node.id()) }
        }

        @JvmStatic
        private fun validateBranchTopology(nodes: List<TacticalSkillNodeDefinition>) {
            val branchesByGroup = HashMap<String, MutableSet<String>>()
            val groupByBranch = HashMap<String, String>()
            val tiersByBranch = HashMap<String, MutableSet<Int>>()
            for (node in nodes) {
                if (node.branchId().isEmpty) {
                    continue
                }
                val group = node.exclusiveBranchGroup().orElseThrow()
                val branch = node.branchId().orElseThrow()
                val previousGroup = groupByBranch.putIfAbsent(branch, group)
                if (previousGroup != null && previousGroup != group) {
                    throw IllegalArgumentException(
                        "branch id belongs to multiple exclusive groups: $branch"
                    )
                }
                branchesByGroup.computeIfAbsent(group) { HashSet() }.add(branch)
                val branchKey = "$group\u0000$branch"
                if (!tiersByBranch.computeIfAbsent(branchKey) { HashSet() }.add(node.tier())) {
                    throw IllegalArgumentException(
                        "branch cannot define multiple nodes for tier ${node.tier()}: $branch"
                    )
                }
            }
            for ((group, branches) in branchesByGroup) {
                if (branches.size < 2) {
                    throw IllegalArgumentException(
                        "exclusive branch group must contain at least two branches: $group"
                    )
                }
            }
            for ((branchKey, tiers) in tiersByBranch) {
                val highestTier = tiers.maxOrNull() ?: throw NoSuchElementException()
                for (tier in 1..highestTier) {
                    if (!tiers.contains(tier)) {
                        throw IllegalArgumentException(
                            "branch tiers must start at one and be contiguous: $branchKey"
                        )
                    }
                }
            }
        }

        @JvmStatic
        private fun validateAcyclic(
            nodes: List<TacticalSkillNodeDefinition>,
            byId: Map<String, TacticalSkillNodeDefinition>,
        ) {
            val visiting = HashSet<String>()
            val visited = HashSet<String>()
            for (node in nodes) {
                visit(node.id(), byId, visiting, visited)
            }
        }

        @JvmStatic
        private fun visit(
            nodeId: String,
            byId: Map<String, TacticalSkillNodeDefinition>,
            visiting: MutableSet<String>,
            visited: MutableSet<String>,
        ) {
            if (visited.contains(nodeId)) {
                return
            }
            if (!visiting.add(nodeId)) {
                throw IllegalArgumentException("cyclic tactical prerequisites at: $nodeId")
            }
            val node = byId[nodeId]!!
            for (prerequisiteId in node.prerequisiteNodeIds()) {
                visit(prerequisiteId, byId, visiting, visited)
            }
            visiting.remove(nodeId)
            visited.add(nodeId)
        }

        @JvmStatic
        private fun validateSlug(value: String, name: String, nodeId: String) {
            if (!value.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) {
                throw IllegalArgumentException("$name must be lowercase slug for $nodeId")
            }
        }

        @JvmStatic
        private fun validateEffect(effect: TacticalEffectEntry) {
            if (effect.value() == 0.0 && effect.type() != TacticalEffectType.CHAIN_COUNT_ADD) {
                throw IllegalArgumentException("tactical effect values must not be zero")
            }
            if (effect.type() == TacticalEffectType.CHAIN_COUNT_ADD
                && effect.value() != Math.rint(effect.value())
            ) {
                throw IllegalArgumentException("chain count additions must be whole numbers")
            }
            if (effect.type().name.endsWith("MULTIPLIER") && effect.value() <= 0.0) {
                throw IllegalArgumentException("multiplier effects must be positive")
            }
        }
    }
}
