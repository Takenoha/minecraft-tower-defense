package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CorePlacementPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void publicPlacementIsDurableAcrossThePhysicalBlockStopWindow() {
        Path databaseFile = temporaryDirectory.resolve("placement.sqlite");
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        DefenseRepository repository = new DefenseRepository(new Database(databaseFile));
        repository.createSoloTeam(teamId, ownerId, NOW);

        CorePlacement prepared = placement(
                operationId, itemId, itemId, ownerId, teamId, worldId, false, NOW);
        assertEquals(prepared, repository.prepareCorePlacement(prepared));
        assertEquals(prepared, repository.prepareCorePlacement(prepared));
        assertEquals(List.of(prepared), repository.loadPendingCorePlacements());

        CorePlacementResult applied = repository.applyCorePlacement(
                operationId, NOW.plusSeconds(1L));
        assertEquals(CorePlacementState.APPLIED, applied.placement().state());
        assertEquals(itemId, applied.core().id());
        assertEquals(1_000L, applied.core().currentHitPoints());
        assertTrue(repository.loadPendingCorePlacements().isEmpty());
        assertEquals(List.of(itemId), repository.loadAppliedCorePlacementItemIds());

        assertEquals(applied, repository.applyCorePlacement(operationId, NOW.plusSeconds(2L)));

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals(applied.core(), reopened.findCoreByTeam(teamId).orElseThrow());
        assertEquals(List.of(itemId), reopened.loadAppliedCorePlacementItemIds());
    }

    @Test
    void preparedPlacementCanBeRolledBackWithoutCreatingCoreState() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("rollback.sqlite")));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        CorePlacement prepared = placement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ownerId,
                teamId,
                UUID.randomUUID(),
                false,
                NOW);

        repository.prepareCorePlacement(prepared);
        CorePlacement rolledBack = repository.rollbackCorePlacement(
                        prepared.operationId(), NOW.plusSeconds(1L))
                .orElseThrow();
        assertEquals(CorePlacementState.ROLLED_BACK, rolledBack.state());
        assertTrue(repository.findCoreByTeam(teamId).isEmpty());
        assertTrue(repository.loadPendingCorePlacements().isEmpty());
        assertEquals(
                rolledBack,
                repository.rollbackCorePlacement(
                                prepared.operationId(), NOW.plusSeconds(2L))
                        .orElseThrow());
    }

    @Test
    void destroyedCoreIsRebuiltInPlaceWithTheSameCoreIdentity() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("rebuild.sqlite")));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        UUID coreId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        repository.placeCore(
                ownerId,
                new CoreRecord(
                        coreId, teamId, worldId, 0, 64, 0, 0L, 1_000L, NOW, NOW),
                192.0D);

        CorePlacement prepared = placement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                coreId,
                ownerId,
                teamId,
                worldId,
                true,
                NOW.plusSeconds(1L));
        repository.prepareCorePlacement(prepared);
        CorePlacementResult applied = repository.applyCorePlacement(
                prepared.operationId(), NOW.plusSeconds(2L));

        assertEquals(coreId, applied.core().id());
        assertEquals(1_000L, applied.core().currentHitPoints());
        assertEquals(192, applied.core().blockX());
        assertEquals(72, applied.core().blockY());
        assertEquals(-24, applied.core().blockZ());
    }

    @Test
    void placementRequiresOwnerAndRejectsActiveDefense() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("authorization.sqlite")));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        repository.addTeamMember(teamId, ownerId, memberId, UUID.randomUUID(), NOW);
        CorePlacement prepared = placement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                memberId,
                teamId,
                UUID.randomUUID(),
                false,
                NOW);

        assertThrows(PersistenceConflictException.class, () -> repository.prepareCorePlacement(prepared));

        CorePlacement ownerPlacement = new CorePlacement(
                prepared.operationId(),
                prepared.itemId(),
                prepared.coreId(),
                ownerId,
                prepared.teamId(),
                prepared.worldId(),
                prepared.blockX(),
                prepared.blockY(),
                prepared.blockZ(),
                prepared.maximumHitPoints(),
                prepared.minimumCoreDistance(),
                prepared.rebuildingDestroyedCore(),
                prepared.previousBlockData(),
                prepared.state(),
                prepared.preparedAt(),
                null,
                null);
        repository.prepareCorePlacement(ownerPlacement);
        repository.applyCorePlacement(ownerPlacement.operationId(), NOW.plusSeconds(1L));
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                teamId,
                1L,
                8,
                CoreState.intact(1_000L));
        assertEquals(
                StartOutcome.STARTED,
                repository.tryStart(new StartRequest(
                        session.snapshot(), ownerPlacement.coreId(), "{}", 1, NOW.plusSeconds(2L))));

        UUID otherTeamId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        repository.createSoloTeam(otherTeamId, otherOwnerId, NOW);
        CorePlacement blockedByEvent = placement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                otherOwnerId,
                otherTeamId,
                UUID.randomUUID(),
                false,
                NOW.plusSeconds(3L));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.prepareCorePlacement(blockedByEvent));
    }

    private static CorePlacement placement(
            UUID operationId,
            UUID itemId,
            UUID coreId,
            UUID ownerId,
            UUID teamId,
            UUID worldId,
            boolean rebuilding,
            Instant preparedAt) {
        return CorePlacement.prepared(
                operationId,
                itemId,
                coreId,
                ownerId,
                teamId,
                worldId,
                rebuilding ? 192 : 0,
                rebuilding ? 72 : 64,
                rebuilding ? -24 : 0,
                1_000L,
                192.0D,
                rebuilding,
                "minecraft:stone",
                preparedAt);
    }
}
