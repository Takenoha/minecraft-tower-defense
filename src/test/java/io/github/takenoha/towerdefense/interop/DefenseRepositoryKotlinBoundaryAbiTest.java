package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.takenoha.towerdefense.config.RewardSettings;
import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.persistence.BattleBoost;
import io.github.takenoha.towerdefense.persistence.BattleBoostKind;
import io.github.takenoha.towerdefense.persistence.BattleBoostMutationResult;
import io.github.takenoha.towerdefense.persistence.BattleFunds;
import io.github.takenoha.towerdefense.persistence.BattleFundsMutationResult;
import io.github.takenoha.towerdefense.persistence.CoreMutationResult;
import io.github.takenoha.towerdefense.persistence.CorePlacement;
import io.github.takenoha.towerdefense.persistence.CorePlacementResult;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.CoreRepairOperation;
import io.github.takenoha.towerdefense.persistence.CoreRepairReceipt;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.EnemyLedgerEntry;
import io.github.takenoha.towerdefense.persistence.EnemyStatus;
import io.github.takenoha.towerdefense.persistence.EventTransitionRecord;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.PaymentMode;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalBatch;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalRedemption;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalRedemptionResult;
import io.github.takenoha.towerdefense.persistence.StartOutcome;
import io.github.takenoha.towerdefense.persistence.StartRequest;
import io.github.takenoha.towerdefense.persistence.StoredDefenseEvent;
import io.github.takenoha.towerdefense.persistence.TeamInvitation;
import io.github.takenoha.towerdefense.persistence.TeamInvitationMutationResult;
import io.github.takenoha.towerdefense.persistence.TeamMutationResult;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.persistence.TowerDamageMutationResult;
import io.github.takenoha.towerdefense.persistence.TowerRepairMutationResult;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Java-facing constructor and method ABI checks for the Kotlin DefenseRepository boundary. */
class DefenseRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryBoundaryMatchesTheFormerJavaSurface() throws Exception {
        assertNotNull(DefenseRepository.class.getConstructor(Database.class));
        assertNotNull(DefenseRepository.class.getConstructor(Database.class, Duration.class));
        assertNotNull(DefenseRepository.class.getConstructor(Database.class, RewardSettings.class));
        assertNotNull(
                DefenseRepository.class.getConstructor(
                        Database.class, Duration.class, RewardSettings.class));
        assertEquals(int.class, DefenseRepository.class.getField("MAX_TEAM_MEMBERS").getType());
        assertEquals(
                Duration.class,
                DefenseRepository.class.getField("DEFAULT_INVITATION_RETENTION").getType());

        Set<String> expected = Set.of(
                sig("createSoloTeam", TeamRecord.class, UUID.class, UUID.class, Instant.class),
                sig("findTeam", Optional.class, UUID.class),
                sig("findTeamByOwner", Optional.class, UUID.class),
                sig("findTeamByMember", Optional.class, UUID.class),
                sig("renameTeam", TeamMutationResult.class, UUID.class, UUID.class, String.class, UUID.class, Instant.class),
                sig("findPendingTeamInvitations", List.class, UUID.class, Instant.class),
                sig("findTeamInvitation", Optional.class, UUID.class),
                sig("createTeamInvitation", TeamInvitationMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, UUID.class, Instant.class, Instant.class),
                sig("acceptTeamInvitation", TeamInvitationMutationResult.class, UUID.class, UUID.class, UUID.class, Instant.class),
                sig("declineTeamInvitation", TeamInvitationMutationResult.class, UUID.class, UUID.class, UUID.class, Instant.class),
                sig("loadTeamProgress", TeamProgress.class, UUID.class),
                sig("findResearchCrystalBatch", Optional.class, UUID.class),
                sig("findResearchCrystalRedemption", Optional.class, UUID.class),
                sig("prepareResearchCrystalRedemption", ResearchCrystalRedemption.class, UUID.class, UUID.class, UUID.class, int.class, UUID.class, Instant.class),
                sig("prepareResearchCrystalRedemption", ResearchCrystalRedemption.class, UUID.class, UUID.class, UUID.class, UUID.class, int.class, int.class, UUID.class, Instant.class),
                sig("prepareResearchCrystalRedemption", ResearchCrystalRedemption.class, UUID.class, UUID.class, UUID.class, UUID.class, int.class, Integer.class, Integer.class, int.class, UUID.class, Instant.class),
                sig("applyResearchCrystalRedemption", ResearchCrystalRedemptionResult.class, UUID.class, Instant.class),
                sig("rollbackResearchCrystalRedemption", Optional.class, UUID.class, Instant.class),
                sig("addTeamMember", TeamMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, Instant.class),
                sig("removeTeamMember", TeamMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, Instant.class),
                sig("transferTeamOwnership", TeamMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, Instant.class),
                sig("leaveTeam", TeamMutationResult.class, UUID.class, UUID.class, UUID.class, Instant.class),
                sig("disbandTeam", TeamMutationResult.class, UUID.class, UUID.class, UUID.class, Instant.class),
                sig("placeCore", CoreRecord.class, CoreRecord.class, double.class),
                sig("placeCore", CoreRecord.class, UUID.class, CoreRecord.class, double.class),
                sig("placeCore", CoreMutationResult.class, UUID.class, CoreRecord.class, double.class, UUID.class, Instant.class),
                sig("findCore", Optional.class, UUID.class),
                sig("findCoreByTeam", Optional.class, UUID.class),
                sig("loadAllCores", List.class),
                sig("prepareCorePlacement", CorePlacement.class, CorePlacement.class),
                sig("applyCorePlacement", CorePlacementResult.class, UUID.class, Instant.class),
                sig("rollbackCorePlacement", Optional.class, UUID.class, Instant.class),
                sig("loadPendingCorePlacements", List.class),
                sig("loadAppliedCorePlacementItemIds", List.class),
                sig("findAppliedCorePlacementByCore", Optional.class, UUID.class),
                sig("findDistanceConflict", Optional.class, UUID.class, int.class, int.class, double.class),
                sig("repairCore", CoreMutationResult.class, UUID.class, UUID.class, long.class, UUID.class, Instant.class),
                sig("repairCore", CoreMutationResult.class, UUID.class, UUID.class, long.class, long.class, UUID.class, Instant.class),
                sig("repairCore", CoreMutationResult.class, UUID.class, UUID.class, long.class, long.class, PaymentMode.class, UUID.class, Instant.class),
                sig("prepareCoreRepair", CoreRepairOperation.class, UUID.class, UUID.class, long.class, long.class, PaymentMode.class, String.class, long.class, UUID.class, Instant.class),
                sig("prepareCoreRepair", CoreRepairOperation.class, UUID.class, UUID.class, long.class, long.class, PaymentMode.class, String.class, long.class, long.class, UUID.class, Instant.class),
                sig("reserveCoreRepairReceipt", CoreRepairReceipt.class, UUID.class, UUID.class, String.class, long.class, Instant.class),
                sig("applyPreparedCoreRepair", CoreMutationResult.class, UUID.class, Instant.class),
                sig("secureCoreRepairReceipt", OperationOutcome.class, UUID.class, Instant.class),
                sig("markCoreRepairReceiptClearPending", OperationOutcome.class, UUID.class, Instant.class),
                sig("clearCoreRepairReceipt", OperationOutcome.class, UUID.class, Instant.class),
                sig("rollbackPreparedCoreRepair", OperationOutcome.class, UUID.class, Instant.class),
                sig("restoreCoreRepairReceipt", OperationOutcome.class, UUID.class, Instant.class),
                sig("findCoreRepairOperation", Optional.class, UUID.class),
                sig("findCoreRepairReceipt", Optional.class, UUID.class),
                sig("loadPreparedCoreRepairs", List.class, UUID.class),
                sig("loadTerminalCoreRepairReceipts", List.class, UUID.class),
                sig("relocateCore", CoreMutationResult.class, UUID.class, UUID.class, UUID.class, int.class, int.class, int.class, double.class, UUID.class, Instant.class),
                sig("rebuildCore", CoreMutationResult.class, UUID.class, UUID.class, UUID.class, int.class, int.class, int.class, long.class, double.class, UUID.class, Instant.class),
                sig("tryStart", StartOutcome.class, StartRequest.class),
                sig("tryStartReserved", StartOutcome.class, StartRequest.class),
                sig("consumeReservedStartSeal", OperationOutcome.class, UUID.class, UUID.class, Instant.class),
                sig("activeEventId", Optional.class),
                sig("loadBattleFunds", BattleFunds.class, UUID.class),
                sig("creditBattleFunds", BattleFundsMutationResult.class, UUID.class, UUID.class, UUID.class, String.class, long.class, Instant.class),
                sig("spendBattleFunds", BattleFundsMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, String.class, long.class, Instant.class),
                sig("loadBattleBoosts", List.class, UUID.class),
                sig("purchaseBattleBoost", BattleBoostMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, BattleBoostKind.class, long.class, double.class, UUID.class, Instant.class),
                sig("repairTowerWithBattleFunds", TowerRepairMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, long.class, long.class, UUID.class, Instant.class),
                sig("damageTowerByEnemy", TowerDamageMutationResult.class, UUID.class, UUID.class, UUID.class, UUID.class, long.class, UUID.class, Instant.class),
                sig("findEvent", Optional.class, UUID.class),
                sig("loadUnfinishedEvents", List.class),
                sig("saveSnapshot", OperationOutcome.class, DefenseSessionSnapshot.class, Instant.class),
                sig("saveSnapshot", OperationOutcome.class, DefenseSessionSnapshot.class, long.class, Instant.class),
                sig("saveTransition", OperationOutcome.class, DefenseSessionSnapshot.class, UUID.class, Instant.class),
                sig("saveTransition", OperationOutcome.class, DefenseSessionSnapshot.class, long.class, UUID.class, Instant.class),
                sig("finishEvent", OperationOutcome.class, DefenseSessionSnapshot.class, UUID.class, Instant.class),
                sig("finishEvent", OperationOutcome.class, DefenseSessionSnapshot.class, long.class, UUID.class, Instant.class),
                sig("recoverUnfinishedEvent", OperationOutcome.class, UUID.class, UUID.class, Instant.class),
                sig("recoverUnfinishedEvent", OperationOutcome.class, UUID.class, long.class, UUID.class, Instant.class),
                sig("upsertEnemy", void.class, EnemyLedgerEntry.class),
                sig("updateEnemyStatus", void.class, UUID.class, UUID.class, UUID.class, EnemyStatus.class, Instant.class),
                sig("loadEnemyLedger", List.class, UUID.class),
                sig("loadTransitions", List.class, UUID.class));

        Set<String> actual = Arrays.stream(DefenseRepository.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(DefenseRepositoryKotlinBoundaryAbiTest::sig)
                .collect(Collectors.toCollection(HashSet::new));
        assertEquals(expected, actual);
    }

    private static String sig(String name, Class<?> returnType, Class<?>... parameterTypes) {
        return name + "(" + Arrays.stream(parameterTypes).map(Class::getName).collect(Collectors.joining(","))
                + ")->" + returnType.getName();
    }

    private static String sig(Method method) {
        return sig(method.getName(), method.getReturnType(), method.getParameterTypes());
    }
}
