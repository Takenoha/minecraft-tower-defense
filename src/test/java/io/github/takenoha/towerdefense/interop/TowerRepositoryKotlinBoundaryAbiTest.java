package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.TowerPlacement;
import io.github.takenoha.towerdefense.persistence.TowerRecord;
import io.github.takenoha.towerdefense.persistence.TowerRemoval;
import io.github.takenoha.towerdefense.persistence.TowerRepository;
import io.github.takenoha.towerdefense.persistence.TowerResearchMutationResult;
import io.github.takenoha.towerdefense.persistence.TowerUpgrade;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeReceipt;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin TowerRepository boundary. */
class TowerRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryMethodsKeepTheirJavaSignatures() throws Exception {
        assertNotNull(TowerRepository.class.getConstructor(Database.class));
        assertMethod("loadAllTowers", List.class);
        assertMethod("findTower", Optional.class, UUID.class);
        assertMethod("loadTowerResearch", List.class, UUID.class);
        assertMethod(
                "findTowerResearch",
                Optional.class,
                UUID.class,
                TowerType.class);
        assertMethod(
                "purchaseTowerResearch",
                TowerResearchMutationResult.class,
                UUID.class,
                UUID.class,
                TowerType.class,
                long.class,
                UUID.class,
                Instant.class);
        assertMethod("prepareTowerUpgrade", TowerUpgrade.class, TowerUpgrade.class);
        assertMethod(
                "applyTowerUpgrade",
                TowerUpgradeResult.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "applyTowerUpgradeFromWallet",
                TowerUpgradeResult.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "rollbackTowerUpgrade",
                Optional.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "reserveTowerUpgradeReceipts",
                List.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "secureTowerUpgradeReceipts",
                OperationOutcome.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "markTowerUpgradeReceiptsClearPending",
                OperationOutcome.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "clearTowerUpgradeReceipts",
                OperationOutcome.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "restoreTowerUpgradeReceipts",
                OperationOutcome.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "findTowerUpgradeReceipts",
                List.class,
                UUID.class);
        assertMethod("loadPreparedTowerUpgrades", List.class);
        assertMethod(
                "loadTerminalTowerUpgradeReceipts",
                List.class,
                UUID.class);
        assertMethod(
                "updateTargetPriority",
                TowerRecord.class,
                UUID.class,
                UUID.class,
                TowerTargetPriority.class,
                Instant.class);
        assertMethod("loadAppliedTowerIds", List.class);
        assertMethod("loadPendingTowerPlacements", List.class);
        assertMethod("loadPendingTowerRemovals", List.class);
        assertMethod("loadAppliedTowerRemovals", List.class);
        assertMethod("prepareTowerRemoval", TowerRemoval.class, TowerRemoval.class);
        assertMethod(
                "applyTowerRemoval",
                TowerRemoval.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "rollbackTowerRemoval",
                Optional.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "prepareTowerPlacement",
                TowerPlacement.class,
                TowerPlacement.class,
                TowerSettings.class);
        assertMethod(
                "applyTowerPlacement",
                TowerRecord.class,
                UUID.class,
                UUID.class,
                TowerSettings.class,
                Instant.class);
        assertMethod(
                "rollbackTowerPlacement",
                Optional.class,
                UUID.class,
                Instant.class);
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        assertEquals(returnType, TowerRepository.class.getMethod(name, parameterTypes).getReturnType(), name);
    }
}
