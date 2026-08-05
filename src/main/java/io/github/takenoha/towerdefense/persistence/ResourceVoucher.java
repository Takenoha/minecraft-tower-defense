package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Canonical database payload for one non-stackable team-bound point voucher. */
public record ResourceVoucher(
        UUID voucherId,
        UUID withdrawalOperationId,
        UUID teamId,
        ResourceType resourceType,
        long quantity,
        ResourceVoucherState state,
        UUID deliveryRecipientPlayerId,
        String payloadFingerprint,
        Instant issuedAt,
        Instant availableAt,
        Instant reservedAt,
        Instant redeemedAt,
        Instant voidedAt) {
    public ResourceVoucher {
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(withdrawalOperationId, "withdrawalOperationId");
        Objects.requireNonNull(teamId, "teamId");
        ResourceType.require(resourceType);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(deliveryRecipientPlayerId, "deliveryRecipientPlayerId");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (quantity <= 0L) {
            throw new IllegalArgumentException("voucher quantity must be positive");
        }
        if (state == ResourceVoucherState.PENDING_DELIVERY && availableAt != null) {
            throw new IllegalArgumentException("pending voucher cannot have an available timestamp");
        }
        if (state == ResourceVoucherState.AVAILABLE && availableAt == null) {
            throw new IllegalArgumentException("available voucher requires an available timestamp");
        }
        if (state == ResourceVoucherState.RESERVED && (availableAt == null || reservedAt == null)) {
            throw new IllegalArgumentException("reserved voucher requires reservation timestamps");
        }
        if (state == ResourceVoucherState.REDEEMED
                && (availableAt == null || reservedAt == null || redeemedAt == null)) {
            throw new IllegalArgumentException("redeemed voucher requires lifecycle timestamps");
        }
        if (state == ResourceVoucherState.VOIDED && voidedAt == null) {
            throw new IllegalArgumentException("voided voucher requires a voided timestamp");
        }
    }
}
