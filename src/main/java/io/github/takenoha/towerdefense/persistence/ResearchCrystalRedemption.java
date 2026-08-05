package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One operation which reserves and then consumes team-bound crystal items. */
public record ResearchCrystalRedemption(
        UUID operationId,
        UUID batchId,
        UUID coreId,
        UUID teamId,
        UUID actorId,
        int quantity,
        String payloadFingerprint,
        Integer segmentOffset,
        Integer segmentQuantity,
        ResearchCrystalRedemptionState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt) {
    public ResearchCrystalRedemption {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if ((segmentOffset == null) != (segmentQuantity == null)) {
            throw new IllegalArgumentException(
                    "segmentOffset and segmentQuantity must be supplied together");
        }
        if (segmentOffset != null && (segmentOffset < 0 || segmentQuantity <= 0)) {
            throw new IllegalArgumentException("research crystal redemption segment is invalid");
        }
        if (state == ResearchCrystalRedemptionState.PREPARED
                && (appliedAt != null || rolledBackAt != null)) {
            throw new IllegalArgumentException("prepared redemption cannot have a terminal time");
        }
        if (state == ResearchCrystalRedemptionState.APPLIED
                && (appliedAt == null || rolledBackAt != null)) {
            throw new IllegalArgumentException("applied redemption must have only appliedAt");
        }
        if (state == ResearchCrystalRedemptionState.ROLLED_BACK
                && (appliedAt != null || rolledBackAt == null)) {
            throw new IllegalArgumentException(
                    "rolled-back redemption must have only rolledBackAt");
        }
    }
}
