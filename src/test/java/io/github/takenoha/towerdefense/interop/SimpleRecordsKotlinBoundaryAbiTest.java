package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyPathContext;
import io.github.takenoha.towerdefense.persistence.BattleBoostMutationResult;
import io.github.takenoha.towerdefense.persistence.EscrowClaimResult;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalRedemptionResult;
import io.github.takenoha.towerdefense.persistence.ResourcePickupFeedback;
import io.github.takenoha.towerdefense.persistence.TacticalSelectionResult;
import io.github.takenoha.towerdefense.persistence.TeamResourceSettlement;
import io.github.takenoha.towerdefense.persistence.TowerDurability;
import io.github.takenoha.towerdefense.persistence.TowerRepairMutationResult;
import io.github.takenoha.towerdefense.persistence.TowerResearchMutationResult;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeResult;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryResult;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemResult;
import io.github.takenoha.towerdefense.runtime.CoreBlockKey;
import io.github.takenoha.towerdefense.runtime.TaggedEnemy;
import io.github.takenoha.towerdefense.runtime.TowerBlockKey;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

class SimpleRecordsKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCanonicalConstructors() throws Exception {
        assertRecord(TowerBlockKey.class,
                new Class<?>[]{UUID.class, int.class, int.class, int.class},
                "worldId", "x", "y", "z");
        assertRecord(CoreBlockKey.class,
                new Class<?>[]{UUID.class, int.class, int.class, int.class},
                "worldId", "x", "y", "z");
        assertRecord(EnemyPathContext.class,
                new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class, int.class},
                "directPathAvailable", "protectedObstacle", "breakableObstacle",
                "buildableGap", "consecutivePathFailures");
        assertRecord(TacticalSelectionResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        io.github.takenoha.towerdefense.tactical.TacticalBuildSelectionView.class},
                "outcome", "selection");
        assertRecord(VoucherDeliveryResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.VoucherDeliveryOutcome.class,
                        io.github.takenoha.towerdefense.persistence.ResourceVoucher.class,
                        io.github.takenoha.towerdefense.persistence.VoucherDeliveryOperation.class},
                "outcome", "voucher", "operation");
        assertRecord(TowerUpgradeResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        java.util.Optional.class}, "outcome", "tower");
        assertRecord(TowerRepairMutationResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        io.github.takenoha.towerdefense.persistence.TowerDurability.class,
                        io.github.takenoha.towerdefense.persistence.BattleFunds.class},
                "outcome", "durability", "funds");
        assertRecord(BattleBoostMutationResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        io.github.takenoha.towerdefense.persistence.BattleBoost.class,
                        io.github.takenoha.towerdefense.persistence.BattleFunds.class},
                "outcome", "boost", "funds");
        assertRecord(VoucherRedeemResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        io.github.takenoha.towerdefense.persistence.ResourceVoucher.class,
                        io.github.takenoha.towerdefense.persistence.VoucherRedeemOperation.class},
                "outcome", "voucher", "operation");
        assertRecord(ResearchCrystalRedemptionResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        io.github.takenoha.towerdefense.domain.TeamProgress.class,
                        io.github.takenoha.towerdefense.persistence.ResearchCrystalBatch.class},
                "outcome", "progress", "batch");
        assertRecord(TowerDurability.class,
                new Class<?>[]{UUID.class, UUID.class, long.class, long.class},
                "towerId", "teamId", "currentHitPoints", "maximumHitPoints");
        assertRecord(TowerResearchMutationResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        io.github.takenoha.towerdefense.domain.TeamProgress.class,
                        io.github.takenoha.towerdefense.domain.TowerResearch.class},
                "outcome", "progress", "research");
        assertRecord(TeamResourceSettlement.class,
                new Class<?>[]{UUID.class, UUID.class,
                        io.github.takenoha.towerdefense.domain.DefensePhase.class, long.class, long.class},
                "eventId", "teamId", "phase", "defensePoints", "enhancementPoints");
        assertRecord(ResourcePickupFeedback.class,
                new Class<?>[]{UUID.class, UUID.class,
                        io.github.takenoha.towerdefense.persistence.ResourceType.class,
                        int.class, long.class, long.class},
                "eventId", "playerId", "resourceType", "claimedQuantity",
                "eventPlayerTotal", "teamBalance");
        assertRecord(EscrowClaimResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        int.class, java.util.Optional.class},
                "outcome", "claimedQuantity", "pickupFeedback");
        assertRecord(TaggedEnemy.class,
                new Class<?>[]{UUID.class, UUID.class,
                        io.github.takenoha.towerdefense.domain.EnemyRole.class},
                "eventId", "logicalEnemyId", "role");
    }

    @Test
    void preservesCompatibilityOverloadsAndStaticFactory() throws Exception {
        assertNotNull(TaggedEnemy.class.getConstructor(UUID.class, UUID.class));
        assertNotNull(EscrowClaimResult.class.getConstructor(
                io.github.takenoha.towerdefense.persistence.OperationOutcome.class, int.class));
        var from = CoreBlockKey.class.getMethod("from", Block.class);
        assertTrue(Modifier.isPublic(from.getModifiers()));
        assertTrue(Modifier.isStatic(from.getModifiers()));
        assertEquals(CoreBlockKey.class, from.getReturnType());
    }

    private static void assertRecord(Class<?> type, Class<?>[] componentTypes, String... names)
            throws Exception {
        assertTrue(type.isRecord(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(List.of(names), Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(names.length, componentTypes.length);
        assertNotNull(type.getConstructor(componentTypes));
    }
}
