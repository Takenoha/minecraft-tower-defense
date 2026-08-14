package io.github.takenoha.towerdefense.interop;

import io.github.takenoha.towerdefense.persistence.BattleFundsMutationResult;
import io.github.takenoha.towerdefense.persistence.BlockChangeKind;
import io.github.takenoha.towerdefense.persistence.BlockChangeStatus;
import io.github.takenoha.towerdefense.persistence.CoreMutationResult;
import io.github.takenoha.towerdefense.persistence.CorePlacementResult;
import io.github.takenoha.towerdefense.persistence.DropSourceKind;
import io.github.takenoha.towerdefense.persistence.ManagementOutcome;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.PaymentMode;
import io.github.takenoha.towerdefense.persistence.PreparedRollback;
import io.github.takenoha.towerdefense.persistence.RaidSealRefundResult;
import io.github.takenoha.towerdefense.persistence.ResourceMutationResult;
import io.github.takenoha.towerdefense.persistence.RewardQueueScope;
import io.github.takenoha.towerdefense.persistence.RewardQueueStatus;
import io.github.takenoha.towerdefense.persistence.StartOutcome;
import io.github.takenoha.towerdefense.persistence.TeamInvitationMutationResult;
import io.github.takenoha.towerdefense.persistence.TeamMutationResult;
import io.github.takenoha.towerdefense.persistence.VoucherWithdrawalResult;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java-facing ABI and validation checks for the B5 persistence type slice. */
class PersistenceKotlinBoundaryAbiTest {
    @Test
    void outcomeAndLedgerEnumsKeepTheirJavaNames() {
        assertEnumNames(OperationOutcome.class,
                "APPLIED", "ALREADY_APPLIED", "ALREADY_TERMINAL", "STATE_MISMATCH");
        assertEnumNames(ManagementOutcome.class, "APPLIED", "ALREADY_APPLIED");
        assertEnumNames(StartOutcome.class, "STARTED", "LOCKED");
        assertEnumNames(BlockChangeKind.class, "EVENT_BLOCK", "TEMPORARY_BLOCK");
        assertEnumNames(BlockChangeStatus.class,
                "PREPARED", "APPLIED", "SETTLED", "ROLLED_BACK", "CONFLICT");
        assertEnumNames(DropSourceKind.class, "ENEMY", "BLOCK");
        assertEnumNames(PaymentMode.class, "POINT_WALLET", "LEGACY_ITEMS");
        assertEnumNames(RewardQueueScope.class, "PLAYER", "TEAM");
        assertEnumNames(RewardQueueStatus.class, "PENDING", "DELIVERED", "VOIDED");
    }

    @Test
    void resultTypesRemainJvmRecordsWithTheSameComponentsAndAccessors() {
        assertRecord(CorePlacementResult.class, "placement", "core");
        assertRecord(CoreMutationResult.class, "outcome", "core");
        assertRecord(TeamInvitationMutationResult.class, "outcome", "invitation", "team");
        assertRecord(TeamMutationResult.class, "outcome", "team");
        assertRecord(ResourceMutationResult.class, "outcome", "resources");
        assertRecord(BattleFundsMutationResult.class, "outcome", "funds");
        assertRecord(VoucherWithdrawalResult.class, "outcome", "voucher");
        assertRecord(RaidSealRefundResult.class, "outcome", "returnedSeal");
        assertRecord(PreparedRollback.class, "operationId", "decision");
    }

    @Test
    void resultConstructorsRejectNullRequiredComponents() {
        assertThrows(NullPointerException.class, () -> new CorePlacementResult(null, null));
        assertThrows(NullPointerException.class, () -> new CoreMutationResult(null, null));
        assertThrows(NullPointerException.class,
                () -> new TeamInvitationMutationResult(null, null, null));
        assertThrows(NullPointerException.class, () -> new TeamMutationResult(null, null));
        assertThrows(NullPointerException.class, () -> new ResourceMutationResult(null, null));
        assertThrows(NullPointerException.class,
                () -> new BattleFundsMutationResult(null, null));
        assertThrows(NullPointerException.class,
                () -> new VoucherWithdrawalResult(null, null));
        assertThrows(NullPointerException.class,
                () -> new RaidSealRefundResult(null, null));
        assertThrows(NullPointerException.class, () -> new PreparedRollback(null, null));
    }

    private static void assertRecord(Class<?> type, String... componentNames) {
        assertTrue(type.isRecord(), type + " must remain a JVM record");
        RecordComponent[] components = type.getRecordComponents();
        assertNotNull(components);
        assertArrayEquals(componentNames,
                Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new));
        for (String componentName : componentNames) {
            try {
                assertNotNull(type.getMethod(componentName),
                        type.getName() + " must expose " + componentName + "()");
            } catch (NoSuchMethodException exception) {
                throw new AssertionError(type.getName() + " must expose " + componentName + "()",
                        exception);
            }
        }
    }

    private static void assertEnumNames(Class<? extends Enum<?>> type, String... expected) {
        Enum<?>[] constants = type.getEnumConstants();
        assertNotNull(constants);
        assertArrayEquals(expected,
                Arrays.stream(constants).map(Enum::name).toArray(String[]::new));
    }
}
