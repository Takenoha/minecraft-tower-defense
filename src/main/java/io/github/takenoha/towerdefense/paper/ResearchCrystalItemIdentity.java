package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;

/** Team and issuance-batch identity carried by a delivered research crystal. */
public record ResearchCrystalItemIdentity(
        UUID batchId,
        UUID teamId,
        int issuedQuantity,
        Integer segmentOffset,
        Integer segmentQuantity) {
    public ResearchCrystalItemIdentity(UUID batchId, UUID teamId, int issuedQuantity) {
        this(batchId, teamId, issuedQuantity, null, null);
    }

    public ResearchCrystalItemIdentity {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(teamId, "teamId");
        if (issuedQuantity <= 0) {
            throw new IllegalArgumentException("issuedQuantity must be positive");
        }
        if ((segmentOffset == null) != (segmentQuantity == null)) {
            throw new IllegalArgumentException(
                    "segmentOffset and segmentQuantity must be supplied together");
        }
        if (segmentOffset != null
                && (segmentOffset < 0
                        || segmentQuantity <= 0
                        || (long) segmentOffset + segmentQuantity > issuedQuantity)) {
            throw new IllegalArgumentException("research crystal segment is outside the batch");
        }
    }

    public boolean hasSegmentIdentity() {
        return segmentOffset != null;
    }
}
