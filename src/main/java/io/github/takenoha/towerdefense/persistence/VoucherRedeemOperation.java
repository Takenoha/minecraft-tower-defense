package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Receipt ledger for one team-member voucher deposit. */
public record VoucherRedeemOperation(
        UUID operationId,
        UUID voucherId,
        UUID teamId,
        UUID actorId,
        ResourceType resourceType,
        long quantity,
        String payloadFingerprint,
        VoucherRedeemState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt) {
    public VoucherRedeemOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        ResourceType.require(resourceType);
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (quantity <= 0L) {
            throw new IllegalArgumentException("redeem quantity must be positive");
        }
    }
}
