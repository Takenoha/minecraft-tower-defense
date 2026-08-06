package io.github.takenoha.towerdefense.tactical;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

/** Generates three stable candidates without using process-global randomness. */
public final class TacticalCandidateGenerator {
    public static final int CANDIDATE_COUNT = 3;

    public TacticalCandidateSet generate(
            UUID tacticalSessionId,
            UUID startOperationId,
            UUID teamId,
            int stage,
            int generatorVersion,
            List<TacticalBuildDefinition> definitions,
            Instant generatedAt) {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        Objects.requireNonNull(startOperationId, "startOperationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (stage <= 0 || generatorVersion <= 0) {
            throw new IllegalArgumentException("stage and generatorVersion must be positive");
        }
        TacticalBuildDefinitionValidator.validateAll(definitions);
        List<TacticalBuildDefinition> pool = definitions.stream()
                .filter(TacticalBuildDefinition::enabled)
                .filter(definition -> definition.weight() > 0)
                .sorted(Comparator.comparing(TacticalBuildDefinition::id))
                .toList();
        if (pool.size() < CANDIDATE_COUNT) {
            throw new IllegalStateException(
                    "fewer than three enabled tactical builds are available");
        }

        long seed = seedFor(startOperationId, teamId, stage, generatorVersion);
        SplittableRandom random = new SplittableRandom(seed);
        List<TacticalBuildDefinition> remaining = new ArrayList<>(pool);
        List<TacticalBuildDefinition> selected = new ArrayList<>(CANDIDATE_COUNT);
        while (selected.size() < CANDIDATE_COUNT) {
            int totalWeight = remaining.stream().mapToInt(TacticalBuildDefinition::weight)
                    .reduce(0, TacticalCandidateGenerator::safeAdd);
            int draw = random.nextInt(totalWeight);
            int cursor = 0;
            TacticalBuildDefinition chosen = null;
            for (TacticalBuildDefinition definition : remaining) {
                cursor = safeAdd(cursor, definition.weight());
                if (draw < cursor) {
                    chosen = definition;
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("candidate selection exhausted its weighted pool");
            }
            selected.add(chosen);
            remaining.remove(chosen);
        }
        ensureCategoryDiversity(selected, remaining);
        List<TacticalCandidate> candidates = new ArrayList<>(CANDIDATE_COUNT);
        for (int slot = 0; slot < selected.size(); slot++) {
            candidates.add(new TacticalCandidate(slot, selected.get(slot)));
        }
        return new TacticalCandidateSet(
                tacticalSessionId,
                startOperationId,
                teamId,
                stage,
                seed,
                generatorVersion,
                candidates,
                generatedAt);
    }

    public static long seedFor(
            UUID startOperationId,
            UUID teamId,
            int stage,
            int generatorVersion) {
        Objects.requireNonNull(startOperationId, "startOperationId");
        Objects.requireNonNull(teamId, "teamId");
        long value = startOperationId.getMostSignificantBits()
                ^ Long.rotateLeft(startOperationId.getLeastSignificantBits(), 17)
                ^ Long.rotateLeft(teamId.getMostSignificantBits(), 31)
                ^ Long.rotateLeft(teamId.getLeastSignificantBits(), 47)
                ^ ((long) stage << 32)
                ^ Integer.toUnsignedLong(generatorVersion);
        return mix64(value);
    }

    private static void ensureCategoryDiversity(
            List<TacticalBuildDefinition> selected,
            List<TacticalBuildDefinition> remaining) {
        if (selected.stream().map(TacticalBuildDefinition::category).distinct().count() >= 2) {
            return;
        }
        TacticalBuildDefinition replacement = remaining.stream()
                .filter(candidate -> candidate.category() != selected.getFirst().category())
                .findFirst()
                .orElse(null);
        if (replacement != null) {
            selected.set(selected.size() - 1, replacement);
        }
    }

    private static int safeAdd(int left, int right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("tactical candidate weights overflow", overflow);
        }
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
