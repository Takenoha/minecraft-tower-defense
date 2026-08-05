package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Inventory delivery receipt for one voucher UUID and recipient UUID. */
public record VoucherDeliveryOperation(
        UUID deliveryOperationId,
        UUID voucherId,
        UUID recipientPlayerId,
        String payloadFingerprint,
        VoucherDeliveryState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt) {
    public VoucherDeliveryOperation {
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId");
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
    }
}
