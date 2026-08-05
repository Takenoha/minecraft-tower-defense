package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CoreTeamLifecyclePersistenceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T04:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void membershipMutationsAreAuthorizedIdempotentAndSurviveReopen() {
        Path databaseFile = temporaryDirectory.resolve("membership.sqlite");
        DefenseRepository repository = new DefenseRepository(new Database(databaseFile));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);

        UUID addOperation = UUID.randomUUID();
        TeamMutationResult added = repository.addTeamMember(
                teamId, ownerId, memberId, addOperation, NOW.plusSeconds(1L));
        assertEquals(ManagementOutcome.APPLIED, added.outcome());
        assertEquals(Set.of(ownerId, memberId), added.team().orElseThrow().members());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.addTeamMember(
                        teamId, ownerId, memberId, addOperation, NOW.plusSeconds(2L)).outcome());

        assertThrows(
                PersistenceConflictException.class,
                () -> repository.addTeamMember(
                        teamId, memberId, UUID.randomUUID(), UUID.randomUUID(), NOW));

        UUID transferOperation = UUID.randomUUID();
        TeamMutationResult transferred = repository.transferTeamOwnership(
                teamId, ownerId, memberId, transferOperation, NOW.plusSeconds(3L));
        assertEquals(memberId, transferred.team().orElseThrow().ownerId());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.transferTeamOwnership(
                        teamId, ownerId, memberId, transferOperation, NOW.plusSeconds(4L)).outcome());

        TeamMutationResult removed = repository.removeTeamMember(
                teamId, memberId, ownerId, UUID.randomUUID(), NOW.plusSeconds(5L));
        assertEquals(Set.of(memberId), removed.team().orElseThrow().members());

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals(removed.team().orElseThrow(), reopened.findTeam(teamId).orElseThrow());
        assertEquals(Optional.of(removed.team().orElseThrow()), reopened.findTeamByMember(memberId));
        assertTrue(reopened.findTeamByMember(ownerId).isEmpty());
    }

    @Test
    void ownerCannotLeaveWithMembersAndEmptySoleTeamCanBeDisbanded() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("leave.sqlite")));
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        repository.addTeamMember(teamId, ownerId, memberId, UUID.randomUUID(), NOW);

        assertThrows(
                PersistenceConflictException.class,
                () -> repository.leaveTeam(teamId, ownerId, UUID.randomUUID(), NOW));

        repository.removeTeamMember(teamId, ownerId, memberId, UUID.randomUUID(), NOW);
        UUID leaveOperation = UUID.randomUUID();
        TeamMutationResult left = repository.leaveTeam(
                teamId, ownerId, leaveOperation, NOW.plusSeconds(1L));
        assertEquals(ManagementOutcome.APPLIED, left.outcome());
        assertTrue(left.team().isEmpty());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.leaveTeam(teamId, ownerId, leaveOperation, NOW.plusSeconds(2L)).outcome());
        assertTrue(repository.findTeam(teamId).isEmpty());
    }

    @Test
    void coreRepairRelocationAndRebuildAreAtomicAndIdempotent() throws SQLException {
        Path databaseFile = temporaryDirectory.resolve("core-lifecycle.sqlite");
        Database database = new Database(databaseFile);
        DefenseRepository repository = new DefenseRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord initial = new CoreRecord(
                UUID.randomUUID(), teamId, worldId, 0, 64, 0, 40L, 100L, NOW, NOW);
        UUID placeOperation = UUID.randomUUID();
        assertEquals(
                ManagementOutcome.APPLIED,
                repository.placeCore(
                                ownerId,
                                initial,
                                192.0D,
                                placeOperation,
                                NOW)
                        .outcome());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.placeCore(
                                ownerId,
                                initial,
                                192.0D,
                                placeOperation,
                                NOW.plusSeconds(1L))
                        .outcome());

        UUID repairOperation = UUID.randomUUID();
        CoreMutationResult repaired = repository.repairCore(
                initial.id(), ownerId, 30L, repairOperation, NOW.plusSeconds(1L));
        assertEquals(70L, repaired.core().orElseThrow().currentHitPoints());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.repairCore(
                        initial.id(), ownerId, 30L, repairOperation, NOW.plusSeconds(2L)).outcome());

        UUID fullRepairOperation = UUID.randomUUID();
        assertEquals(
                100L,
                repository.repairCore(
                        initial.id(), ownerId, 100L, fullRepairOperation, NOW.plusSeconds(3L))
                        .core()
                        .orElseThrow()
                        .currentHitPoints());

        UUID relocateOperation = UUID.randomUUID();
        CoreRecord relocated = repository.relocateCore(
                        initial.id(),
                        ownerId,
                        worldId,
                        200,
                        65,
                        0,
                        192.0D,
                        relocateOperation,
                        NOW.plusSeconds(4L))
                .core()
                .orElseThrow();
        assertEquals(200, relocated.blockX());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.relocateCore(
                        initial.id(),
                        ownerId,
                        worldId,
                        200,
                        65,
                        0,
                        192.0D,
                        relocateOperation,
                        NOW.plusSeconds(5L)).outcome());

        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE cores SET current_hp = 0, updated_at = ? WHERE core_id = ?")) {
            statement.setString(1, NOW.plusSeconds(6L).toString());
            statement.setString(2, initial.id().toString());
            assertEquals(1, statement.executeUpdate());
        }

        UUID rebuildOperation = UUID.randomUUID();
        CoreMutationResult rebuilt = repository.rebuildCore(
                initial.id(),
                ownerId,
                worldId,
                500,
                70,
                0,
                120L,
                192.0D,
                rebuildOperation,
                NOW.plusSeconds(7L));
        assertEquals(120L, rebuilt.core().orElseThrow().currentHitPoints());
        assertEquals(120L, rebuilt.core().orElseThrow().maximumHitPoints());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.rebuildCore(
                        initial.id(),
                        ownerId,
                        worldId,
                        500,
                        70,
                        0,
                        120L,
                        192.0D,
                        rebuildOperation,
                        NOW.plusSeconds(8L)).outcome());

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals(rebuilt.core().orElseThrow(), reopened.findCore(initial.id()).orElseThrow());
    }

    @Test
    void walletCoreRepairDebitsPointsAndCoreHealthExactlyOnce() {
        Database database = new Database(temporaryDirectory.resolve("wallet-repair.sqlite"));
        DefenseRepository repository = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                40L,
                100L,
                NOW,
                NOW);
        repository.placeCore(core, 192.0D);
        resources.credit(
                teamId,
                ResourceType.DEFENSE_POINTS,
                5L,
                UUID.randomUUID(),
                "repair-funding",
                NOW);

        UUID operationId = UUID.randomUUID();
        CoreMutationResult repaired = repository.repairCore(
                core.id(),
                ownerId,
                20L,
                3L,
                PaymentMode.POINT_WALLET,
                operationId,
                NOW.plusSeconds(1L));
        assertEquals(60L, repaired.core().orElseThrow().currentHitPoints());
        assertEquals(2L, resources.load(teamId, ownerId).defensePoints());
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.repairCore(
                        core.id(),
                        ownerId,
                        20L,
                        3L,
                        PaymentMode.POINT_WALLET,
                        operationId,
                        NOW.plusSeconds(2L)).outcome());
        assertEquals(2L, resources.load(teamId, ownerId).defensePoints());
    }

    @Test
    void preparedWalletRepairRequiresReceiptAndClearsItAfterApply() {
        Database database = new Database(temporaryDirectory.resolve("prepared-wallet-repair.sqlite"));
        DefenseRepository repository = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                40L,
                100L,
                NOW,
                NOW);
        repository.placeCore(core, 192.0D);
        resources.credit(
                teamId,
                ResourceType.DEFENSE_POINTS,
                5L,
                UUID.randomUUID(),
                "prepared-repair-funding",
                NOW);
        UUID operationId = UUID.randomUUID();
        CoreRepairOperation prepared = repository.prepareCoreRepair(
                core.id(),
                ownerId,
                20L,
                3L,
                PaymentMode.POINT_WALLET,
                "IRON_INGOT",
                4L,
                operationId,
                NOW.plusSeconds(1L));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.applyPreparedCoreRepair(
                        prepared.operationId(), NOW.plusSeconds(2L)));

        repository.reserveCoreRepairReceipt(
                operationId,
                ownerId,
                "IRON_INGOT",
                4L,
                NOW.plusSeconds(2L));
        CoreMutationResult applied = repository.applyPreparedCoreRepair(
                operationId,
                NOW.plusSeconds(3L));
        assertEquals(ManagementOutcome.APPLIED, applied.outcome());
        assertEquals(60L, applied.core().orElseThrow().currentHitPoints());
        assertEquals(2L, resources.load(teamId, ownerId).defensePoints());
        assertEquals(
                OperationOutcome.APPLIED,
                repository.clearCoreRepairReceipt(operationId, NOW.plusSeconds(4L)));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                repository.clearCoreRepairReceipt(operationId, NOW.plusSeconds(5L)));
        assertEquals(
                ManagementOutcome.ALREADY_APPLIED,
                repository.applyPreparedCoreRepair(operationId, NOW.plusSeconds(6L)).outcome());
        assertEquals(CoreRepairReceiptState.CLEARED,
                repository.findCoreRepairReceipt(operationId).orElseThrow().state());
        assertEquals(2L, resources.load(teamId, ownerId).defensePoints());
    }

    @Test
    void failedPreparedWalletRepairLeavesCoreAndWalletUnchangedUntilRollback() {
        Database database = new Database(temporaryDirectory.resolve("prepared-wallet-repair-failure.sqlite"));
        DefenseRepository repository = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                40L,
                100L,
                NOW,
                NOW);
        repository.placeCore(core, 192.0D);
        resources.credit(
                teamId,
                ResourceType.DEFENSE_POINTS,
                2L,
                UUID.randomUUID(),
                "prepared-repair-insufficient-funding",
                NOW);
        UUID operationId = UUID.randomUUID();
        repository.prepareCoreRepair(
                core.id(), ownerId, 20L, 3L, PaymentMode.POINT_WALLET,
                "IRON_INGOT", 4L, operationId, NOW.plusSeconds(1L));
        repository.reserveCoreRepairReceipt(
                operationId, ownerId, "IRON_INGOT", 4L, NOW.plusSeconds(2L));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.applyPreparedCoreRepair(operationId, NOW.plusSeconds(3L)));
        assertEquals(40L, repository.findCore(core.id()).orElseThrow().currentHitPoints());
        assertEquals(2L, resources.load(teamId, ownerId).defensePoints());
        assertEquals(
                OperationOutcome.APPLIED,
                repository.rollbackPreparedCoreRepair(operationId, NOW.plusSeconds(4L)));
        assertEquals(CoreRepairReceiptState.RESTORED,
                repository.findCoreRepairReceipt(operationId).orElseThrow().state());
    }

    @Test
    void coreMutationsRejectDistanceConflictsAndActiveEvents() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("core-boundaries.sqlite")));
        UUID worldId = UUID.randomUUID();
        UUID firstTeam = UUID.randomUUID();
        UUID firstOwner = UUID.randomUUID();
        UUID secondTeam = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        repository.createSoloTeam(firstTeam, firstOwner, NOW);
        repository.createSoloTeam(secondTeam, secondOwner, NOW);
        CoreRecord first = new CoreRecord(
                UUID.randomUUID(), firstTeam, worldId, 0, 64, 0, 100L, 100L, NOW, NOW);
        repository.placeCore(firstOwner, first, 192.0D);
        CoreRecord second = new CoreRecord(
                UUID.randomUUID(), secondTeam, worldId, 192, 64, 0, 100L, 100L, NOW, NOW);
        repository.placeCore(secondOwner, second, 192.0D);

        assertThrows(
                PersistenceConflictException.class,
                () -> repository.relocateCore(
                        first.id(),
                        firstOwner,
                        worldId,
                        191,
                        64,
                        0,
                        192.0D,
                        UUID.randomUUID(),
                        NOW));

        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                firstTeam,
                1L,
                8,
                CoreState.intact(100L));
        assertEquals(
                StartOutcome.STARTED,
                repository.tryStart(new StartRequest(
                        session.snapshot(), first.id(), "{}", 1, NOW)));

        assertThrows(
                PersistenceConflictException.class,
                () -> repository.addTeamMember(
                        firstTeam, firstOwner, UUID.randomUUID(), UUID.randomUUID(), NOW));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.repairCore(
                        first.id(), firstOwner, 1L, UUID.randomUUID(), NOW));

        assertEquals(
                OperationOutcome.APPLIED,
                repository.recoverUnfinishedEvent(
                        session.eventId(), UUID.randomUUID(), NOW.plusSeconds(1L)));
        assertFalse(repository.activeEventId().isPresent());
    }
}
