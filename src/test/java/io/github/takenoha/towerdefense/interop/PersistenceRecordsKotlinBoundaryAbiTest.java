package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.BlockChange;
import io.github.takenoha.towerdefense.persistence.BlockChangeStatus;
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import io.github.takenoha.towerdefense.persistence.RewardQueueEntry;
import io.github.takenoha.towerdefense.persistence.RewardQueueScope;
import io.github.takenoha.towerdefense.persistence.RewardQueueStatus;
import io.github.takenoha.towerdefense.persistence.StartRequest;
import io.github.takenoha.towerdefense.persistence.StoredBlockChange;
import io.github.takenoha.towerdefense.persistence.TowerDamageMutationResult;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersistenceRecordsKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCanonicalConstructors() throws Exception {
        assertRecord(BlockStateSnapshot.class,
                new Class<?>[]{String.class, String.class, String.class},
                "blockData", "blockState", "tileNbt");
        assertRecord(TowerDamageMutationResult.class,
                new Class<?>[]{io.github.takenoha.towerdefense.persistence.OperationOutcome.class,
                        UUID.class, UUID.class, UUID.class, UUID.class,
                        long.class, long.class, boolean.class},
                "outcome", "eventId", "teamId", "towerId", "attackerLogicalEnemyId",
                "damage", "remainingHitPoints", "destroyed");
        assertRecord(StartRequest.class,
                new Class<?>[]{io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot.class,
                        UUID.class, String.class, int.class, Instant.class, Optional.class},
                "session", "coreId", "configSnapshot", "configVersion", "startedAt", "raidSealId");
        assertRecord(StoredBlockChange.class,
                new Class<?>[]{BlockChange.class, BlockChangeStatus.class, UUID.class,
                        Optional.class, Optional.class, Instant.class, Optional.class, Optional.class},
                "change", "status", "prepareOperationId", "applyOperationId",
                "rollbackOperationId", "preparedAt", "appliedAt", "resolvedAt");
        assertRecord(RewardQueueEntry.class,
                new Class<?>[]{UUID.class, UUID.class, RewardQueueScope.class, UUID.class,
                        String.class, String.class, int.class, UUID.class, RewardQueueStatus.class,
                        UUID.class, Instant.class, Instant.class, Optional.class},
                "queueId", "eventId", "scope", "recipientId", "itemId", "itemPayload",
                "quantity", "sourceDropId", "status", "issuedOperationId", "createdAt",
                "updatedAt", "teamClaimDeadline");
    }

    @Test
    void preservesCompatibilityConstructors() throws Exception {
        assertNotNull(BlockStateSnapshot.class.getConstructor(String.class, String.class));
        assertNotNull(StartRequest.class.getConstructor(
                io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot.class,
                UUID.class, String.class, int.class, Instant.class));
        assertNotNull(RewardQueueEntry.class.getConstructor(
                UUID.class, UUID.class, RewardQueueScope.class, UUID.class,
                String.class, String.class, int.class, UUID.class, RewardQueueStatus.class,
                UUID.class, Instant.class, Instant.class));
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
