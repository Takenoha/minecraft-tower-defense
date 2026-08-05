package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;

/** Team and issuance-batch identity carried by a delivered research crystal. */
public record ResearchCrystalItemIdentity(
        UUID batchId,
        UUID teamId,
        int issuedQuantity) {
    public ResearchCrystalItemIdentity {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(teamId, "teamId");
        if (issuedQuantity <= 0) {
            throw new IllegalArgumentException("issuedQuantity must be positive");
        }
    }
}
