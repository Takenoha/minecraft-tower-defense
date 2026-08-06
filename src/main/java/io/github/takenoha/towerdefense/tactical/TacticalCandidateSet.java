package io.github.takenoha.towerdefense.tactical;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persistable deterministic candidate result. */
public record TacticalCandidateSet(
        UUID tacticalSessionId,
        UUID startOperationId,
        UUID teamId,
        int stage,
        long seed,
        int generatorVersion,
        List<TacticalCandidate> candidates,
        Instant generatedAt) {
    public TacticalCandidateSet {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        Objects.requireNonNull(startOperationId, "startOperationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (stage <= 0 || generatorVersion <= 0) {
            throw new IllegalArgumentException("stage and generatorVersion must be positive");
        }
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.size() != 3) {
            throw new IllegalArgumentException("exactly three tactical candidates are required");
        }
        candidates = List.copyOf(candidates);
        for (int slot = 0; slot < candidates.size(); slot++) {
            if (candidates.get(slot).slot() != slot) {
                throw new IllegalArgumentException("candidate slots must be contiguous and ordered");
            }
        }
        TacticalBuildDefinitionValidator.validateAll(
                candidates.stream().map(TacticalCandidate::definition).toList());
        if (candidates.stream().map(candidate -> candidate.definition().id()).distinct().count()
                != candidates.size()) {
            throw new IllegalArgumentException("candidate build ids must be unique");
        }
    }

    public TacticalBuildDefinition requireBuild(String buildId) {
        return candidates.stream()
                .map(TacticalCandidate::definition)
                .filter(definition -> definition.id().equals(buildId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "build is not a candidate: " + buildId));
    }
}
