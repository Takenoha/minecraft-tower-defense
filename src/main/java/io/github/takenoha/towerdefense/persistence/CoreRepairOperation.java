package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Prepared core-repair payload retained across an inventory/database stop boundary. */
public record CoreRepairOperation(
        UUID operationId,
        UUID coreId,
        UUID teamId,
        UUID actorId,
        long expectedCurrentHitPoints,
        long repairAmount,
        long defensePointCost,
        PaymentMode paymentMode,
        String vanillaMaterial,
        long vanillaMaterialAmount,
        long legacyDefenseShardAmount,
        String payloadFingerprint,
        CoreRepairOperationState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt) {
    public CoreRepairOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(paymentMode, "paymentMode");
        Objects.requireNonNull(vanillaMaterial, "vanillaMaterial");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (expectedCurrentHitPoints <= 0L || repairAmount <= 0L
                || defensePointCost < 0L || vanillaMaterialAmount < 0L
                || legacyDefenseShardAmount < 0L) {
            throw new IllegalArgumentException("core repair quantities are invalid");
        }
        if (paymentMode == PaymentMode.POINT_WALLET && legacyDefenseShardAmount != 0L) {
            throw new IllegalArgumentException(
                    "wallet repairs cannot include legacy shard materials");
        }
        try {
            Math.addExact(vanillaMaterialAmount, legacyDefenseShardAmount);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("core repair receipt quantity exceeds Long.MAX_VALUE", overflow);
        }
        if (state == CoreRepairOperationState.PREPARED
                && (appliedAt != null || rolledBackAt != null)) {
            throw new IllegalArgumentException("prepared repair cannot have terminal timestamps");
        }
        if (state == CoreRepairOperationState.APPLIED
                && (appliedAt == null || rolledBackAt != null)) {
            throw new IllegalArgumentException("applied repair requires only appliedAt");
        }
        if (state == CoreRepairOperationState.ROLLED_BACK
                && (appliedAt != null || rolledBackAt == null)) {
            throw new IllegalArgumentException("rolled-back repair requires only rolledBackAt");
        }
    }
}
