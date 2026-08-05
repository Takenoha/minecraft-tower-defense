package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.ResourceType;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Parsed, untrusted PDC view of a physical resource voucher. */
public record ResourceVoucherItemData(
        UUID voucherId,
        UUID teamId,
        ResourceType resourceType,
        long quantity,
        Optional<UUID> deliveryOperationId,
        Optional<UUID> redeemOperationId) {
    public ResourceVoucherItemData {
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(teamId, "teamId");
        ResourceType.require(resourceType);
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId");
        Objects.requireNonNull(redeemOperationId, "redeemOperationId");
        if (quantity <= 0L) {
            throw new IllegalArgumentException("voucher quantity must be positive");
        }
        if (deliveryOperationId.isPresent() && redeemOperationId.isPresent()) {
            throw new IllegalArgumentException("a voucher cannot have two receipt states");
        }
    }

    public boolean hasReceipt() {
        return deliveryOperationId.isPresent() || redeemOperationId.isPresent();
    }
}
