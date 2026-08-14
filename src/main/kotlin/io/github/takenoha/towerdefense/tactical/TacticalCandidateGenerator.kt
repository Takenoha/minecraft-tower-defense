package io.github.takenoha.towerdefense.tactical

import java.time.Instant
import java.util.ArrayList
import java.util.Comparator
import java.util.Objects
import java.util.SplittableRandom
import java.util.UUID
import java.util.stream.Collectors

/** Generates three stable candidates without using process-global randomness. */
class TacticalCandidateGenerator {
    fun generate(
        tacticalSessionId: UUID,
        startOperationId: UUID,
        teamId: UUID,
        stage: Int,
        generatorVersion: Int,
        definitions: List<TacticalBuildDefinition>,
        generatedAt: Instant,
    ): TacticalCandidateSet {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId")
        Objects.requireNonNull(startOperationId, "startOperationId")
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(definitions, "definitions")
        Objects.requireNonNull(generatedAt, "generatedAt")
        if (stage <= 0 || generatorVersion <= 0) {
            throw IllegalArgumentException("stage and generatorVersion must be positive")
        }
        TacticalBuildDefinitionValidator.validateAll(definitions)
        val pool = definitions.stream()
            .filter { it.enabled() }
            .filter { it.weight() > 0 }
            .sorted(Comparator.comparing { it.id() })
            .collect(Collectors.toUnmodifiableList())
        if (pool.size < CANDIDATE_COUNT) {
            throw IllegalStateException("fewer than three enabled tactical builds are available")
        }

        val seed = seedFor(startOperationId, teamId, stage, generatorVersion)
        val random = SplittableRandom(seed)
        val remaining = ArrayList(pool)
        val selected = ArrayList<TacticalBuildDefinition>(CANDIDATE_COUNT)
        while (selected.size < CANDIDATE_COUNT) {
            var totalWeight = 0
            for (definition in remaining) {
                totalWeight = safeAdd(totalWeight, definition.weight())
            }
            val draw = random.nextInt(totalWeight)
            var cursor = 0
            var chosen: TacticalBuildDefinition? = null
            for (definition in remaining) {
                cursor = safeAdd(cursor, definition.weight())
                if (draw < cursor) {
                    chosen = definition
                    break
                }
            }
            if (chosen == null) {
                throw IllegalStateException("candidate selection exhausted its weighted pool")
            }
            selected.add(chosen)
            remaining.remove(chosen)
        }
        ensureCategoryDiversity(selected, remaining)
        val candidates = ArrayList<TacticalCandidate>(CANDIDATE_COUNT)
        for (slot in selected.indices) {
            candidates.add(TacticalCandidate(slot, selected[slot]))
        }
        return TacticalCandidateSet(
            tacticalSessionId,
            startOperationId,
            teamId,
            stage,
            seed,
            generatorVersion,
            candidates,
            generatedAt,
        )
    }

    companion object {
        @JvmField
        val CANDIDATE_COUNT: Int = 3

        @JvmStatic
        fun seedFor(
            startOperationId: UUID,
            teamId: UUID,
            stage: Int,
            generatorVersion: Int,
        ): Long {
            Objects.requireNonNull(startOperationId, "startOperationId")
            Objects.requireNonNull(teamId, "teamId")
            val value = startOperationId.mostSignificantBits xor
                java.lang.Long.rotateLeft(startOperationId.leastSignificantBits, 17) xor
                java.lang.Long.rotateLeft(teamId.mostSignificantBits, 31) xor
                java.lang.Long.rotateLeft(teamId.leastSignificantBits, 47) xor
                (stage.toLong() shl 32) xor
                Integer.toUnsignedLong(generatorVersion)
            return mix64(value)
        }

        private fun ensureCategoryDiversity(
            selected: MutableList<TacticalBuildDefinition>,
            remaining: List<TacticalBuildDefinition>,
        ) {
            if (selected.map { it.category() }.distinct().size >= 2) {
                return
            }
            val replacement = remaining.firstOrNull {
                it.category() != selected.first().category()
            }
            if (replacement != null) {
                selected[selected.size - 1] = replacement
            }
        }

        private fun safeAdd(left: Int, right: Int): Int {
            return try {
                Math.addExact(left, right)
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("tactical candidate weights overflow", overflow)
            }
        }

        private fun mix64(value: Long): Long {
            var mixed = value
            mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
            mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
            return mixed xor (mixed ushr 31)
        }
    }
}
