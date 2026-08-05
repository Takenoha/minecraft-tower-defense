package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable prepared/applied individual-level upgrade operation. */
public record TowerUpgrade(
        UUID operationId,
        UUID towerId,
        UUID actorId,
        UUID teamId,
        int fromLevel,
        int toLevel,
        int defenseShardCost,
        int enhancementCoreCost,
        String payloadFingerprint,
        TowerUpgradeState state,
        Instant preparedAt,
        Instant appliedAt,
        Instant rolledBackAt,
        PaymentMode paymentMode) {
    public TowerUpgrade {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        Objects.requireNonNull(paymentMode, "paymentMode");
        if (fromLevel <= 0 || toLevel != fromLevel + 1) {
            throw new IllegalArgumentException("an upgrade must raise one positive level");
        }
        if (defenseShardCost <= 0 || enhancementCoreCost <= 0) {
            throw new IllegalArgumentException("upgrade material costs must be positive");
        }
        if (state == TowerUpgradeState.PREPARED
                && (appliedAt != null || rolledBackAt != null)) {
            throw new IllegalArgumentException("prepared upgrades cannot have terminal timestamps");
        }
        if (state == TowerUpgradeState.APPLIED
                && (appliedAt == null || rolledBackAt != null)) {
            throw new IllegalArgumentException("applied upgrades require only appliedAt");
        }
        if (state == TowerUpgradeState.ROLLED_BACK
                && (appliedAt != null || rolledBackAt == null)) {
            throw new IllegalArgumentException("rolled-back upgrades require only rolledBackAt");
        }
    }

    public static TowerUpgrade prepared(
            UUID operationId,
            TowerRecord tower,
            UUID actorId,
            int defenseShardCost,
            int enhancementCoreCost,
            Instant preparedAt) {
        Objects.requireNonNull(tower, "tower");
        return new TowerUpgrade(
                operationId,
                tower.id(),
                actorId,
                tower.teamId(),
                tower.individualLevel(),
                tower.individualLevel() + 1,
                defenseShardCost,
                enhancementCoreCost,
                "",
                TowerUpgradeState.PREPARED,
                preparedAt,
                null,
                null,
                PaymentMode.LEGACY_ITEMS);
    }

    public static TowerUpgrade preparedWallet(
            UUID operationId,
            TowerRecord tower,
            UUID actorId,
            int defensePointCost,
            int enhancementPointCost,
            Instant preparedAt) {
        Objects.requireNonNull(tower, "tower");
        return new TowerUpgrade(
                operationId,
                tower.id(),
                actorId,
                tower.teamId(),
                tower.individualLevel(),
                tower.individualLevel() + 1,
                defensePointCost,
                enhancementPointCost,
                "",
                TowerUpgradeState.PREPARED,
                preparedAt,
                null,
                null,
                PaymentMode.POINT_WALLET);
    }
}
