package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyPathAction;
import io.github.takenoha.towerdefense.domain.EnemyTerrainActionKind;
import io.github.takenoha.towerdefense.persistence.BattleFundsState;
import io.github.takenoha.towerdefense.persistence.BlockRollbackDecision;
import io.github.takenoha.towerdefense.persistence.CorePlacementState;
import io.github.takenoha.towerdefense.persistence.CoreRepairOperationState;
import io.github.takenoha.towerdefense.persistence.CoreRepairReceiptState;
import io.github.takenoha.towerdefense.persistence.EnemyStatus;
import io.github.takenoha.towerdefense.persistence.EscrowDropStatus;
import io.github.takenoha.towerdefense.persistence.RaidSealStatus;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalBatchStatus;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalRedemptionState;
import io.github.takenoha.towerdefense.persistence.ResourceVoucherState;
import io.github.takenoha.towerdefense.persistence.RewardDeliveryOutcome;
import io.github.takenoha.towerdefense.persistence.TacticalBuildSessionState;
import io.github.takenoha.towerdefense.persistence.TeamInvitationState;
import io.github.takenoha.towerdefense.persistence.TowerPlacementState;
import io.github.takenoha.towerdefense.persistence.TowerRemovalState;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeReceiptState;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeState;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryOutcome;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryState;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemState;
import io.github.takenoha.towerdefense.runtime.TerrainMutationDecision;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateEnumsKotlinBoundaryAbiTest {
    @Test
    void preservesEnumIdentityAndExactOrder() {
        assertEnum(EnemyObstacleClassification.class,
                "CLEAR", "PROTECTED", "BREAKABLE", "BUILDABLE_GAP", "UNAVAILABLE");
        assertEnum(EnemyPathAction.class,
                "ADVANCE", "BREAK_OBSTACLE", "BUILD_SUPPORT", "RECALCULATE_PATH", "RECOVER");
        assertEnum(EnemyTerrainActionKind.class, "BREAK", "BUILD");
        assertEnum(BattleFundsState.class, "ACTIVE", "SETTLED");
        assertEnum(BlockRollbackDecision.class, "RESTORE", "SKIP_ALREADY_BEFORE", "CONFLICT");
        assertEnum(CorePlacementState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(CoreRepairOperationState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(CoreRepairReceiptState.class,
                "RESERVED", "SECURED", "RETURN_PENDING", "CLEAR_PENDING", "CLEARED", "RESTORED");
        assertEnum(EnemyStatus.class,
                "ALLOCATED", "SPAWNED", "DEAD", "DESPAWNED", "RECOVERY_REMOVED");
        assertEnum(EscrowDropStatus.class, "HELD", "SETTLED", "VOIDED");
        assertEnum(RaidSealStatus.class, "AVAILABLE", "RESERVED", "CONSUMED", "REFUNDED");
        assertEnum(ResearchCrystalBatchStatus.class, "ISSUED", "EXHAUSTED", "VOIDED");
        assertEnum(ResearchCrystalRedemptionState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(ResourceVoucherState.class,
                "PENDING_DELIVERY", "AVAILABLE", "RESERVED", "REDEEMED", "VOIDED");
        assertEnum(RewardDeliveryOutcome.class,
                "ACQUIRED", "ALREADY_ACQUIRED", "ALREADY_DELIVERED", "HELD_BY_OTHER", "VOIDED");
        assertEnum(TacticalBuildSessionState.class,
                "GENERATED", "SELECTED", "ACTIVE", "TERMINAL", "CANCELLED", "RECOVERY_HOLD");
        assertEnum(TeamInvitationState.class, "PENDING", "ACCEPTED", "DECLINED", "EXPIRED");
        assertEnum(TowerPlacementState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(TowerRemovalState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(TowerUpgradeReceiptState.class,
                "RESERVED", "SECURED", "RETURN_PENDING", "CLEAR_PENDING", "CLEARED", "RESTORED");
        assertEnum(TowerUpgradeState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(VoucherDeliveryOutcome.class,
                "PREPARED", "ALREADY_PREPARED", "ALREADY_AVAILABLE", "VOIDED");
        assertEnum(VoucherDeliveryState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(VoucherRedeemState.class, "PREPARED", "APPLIED", "ROLLED_BACK");
        assertEnum(TerrainMutationDecision.class, "ALLOW", "DISABLED", "PROTECTED", "ROLE_REJECTED");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertEnum(Class<? extends Enum<?>> type, String... names) {
        assertTrue(type.isEnum());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(List.of(names), Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
        for (String name : names) {
            assertEquals(name, Enum.valueOf(rawEnum(type), name).name());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Class rawEnum(Class<? extends Enum<?>> type) {
        return type;
    }
}
