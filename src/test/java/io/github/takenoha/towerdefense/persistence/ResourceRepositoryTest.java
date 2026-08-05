package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-05T08:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void walletCreditDebitIsIdempotentAndCannotUnderflow() {
        Database database = new Database(temporaryDirectory.resolve("wallet.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);

        assertEquals(
                0L,
                resources.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
        UUID creditOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                resources.credit(
                        teamId,
                        ResourceType.DEFENSE_POINTS,
                        12L,
                        creditOperation,
                        "test-credit",
                        NOW).outcome());
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                resources.credit(
                        teamId,
                        ResourceType.DEFENSE_POINTS,
                        12L,
                        creditOperation,
                        "test-credit",
                        NOW.plusSeconds(1L)).outcome());
        assertEquals(
                12L,
                resources.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
        assertThrows(
                PersistenceConflictException.class,
                () -> resources.credit(
                        teamId,
                        ResourceType.DEFENSE_POINTS,
                        13L,
                        creditOperation,
                        "test-credit",
                        NOW.plusSeconds(2L)));

        UUID debitOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                resources.debit(
                        teamId,
                        ownerId,
                        ResourceType.DEFENSE_POINTS,
                        5L,
                        debitOperation,
                        "test-debit",
                        NOW.plusSeconds(3L)).outcome());
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                resources.debit(
                        teamId,
                        ownerId,
                        ResourceType.DEFENSE_POINTS,
                        5L,
                        debitOperation,
                        "test-debit",
                        NOW.plusSeconds(4L)).outcome());
        assertEquals(
                7L,
                resources.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
        assertThrows(
                PersistenceConflictException.class,
                () -> resources.debit(
                        teamId,
                        outsiderId,
                        ResourceType.DEFENSE_POINTS,
                        1L,
                        UUID.randomUUID(),
                        "outsider",
                        NOW.plusSeconds(5L)));
        assertThrows(
                PersistenceConflictException.class,
                () -> resources.debit(
                        teamId,
                        ownerId,
                        ResourceType.DEFENSE_POINTS,
                        8L,
                        UUID.randomUUID(),
                        "underflow",
                        NOW.plusSeconds(6L)));
    }

    @Test
    void migrationCreatesBothWalletRowsWithoutChangingTeamProgress() {
        Database database = new Database(temporaryDirectory.resolve("migration.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);

        assertEquals(0L, resources.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
        assertEquals(0L, resources.load(teamId, ownerId).balance(ResourceType.ENHANCEMENT_POINTS));
        assertEquals(0L, teams.loadTeamProgress(teamId).researchPoints());
    }

    @Test
    void repositoryBackfillsWalletRowsForTeamsFromAPartialRestore() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("wallet-backfill.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);

        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM team_resource_balances
                        WHERE team_id = ? AND resource_type = 'ENHANCEMENT_POINTS'
                        """)) {
            statement.setString(1, teamId.toString());
            assertEquals(1, statement.executeUpdate());
        }

        ResourceRepository restarted = new ResourceRepository(database);

        assertEquals(
                0L,
                restarted.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
        assertEquals(
                0L,
                restarted.load(teamId, ownerId).balance(ResourceType.ENHANCEMENT_POINTS));
        assertEquals(0L, resources.load(teamId, ownerId).balance(ResourceType.DEFENSE_POINTS));
    }

    @Test
    void legacyQueueMigrationOnlyConvertsPendingRowsWithoutDeliveryOperations() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("legacy-queue.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        EscrowRepository escrow = new EscrowRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(), teamId, UUID.randomUUID(), 0, 64, 0,
                100L, 100L, NOW, NOW);
        teams.placeCore(core, 192.0D);
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(), teamId, 1L, 8, CoreState.intact(100L));
        assertEquals(
                StartOutcome.STARTED,
                teams.tryStart(new StartRequest(session.snapshot(), core.id(), "{}", 1, NOW)));
        DefenseSession active = DefenseSession.restore(session.snapshot());
        active.completeCountdown(Set.of(ownerId));
        teams.saveTransition(active.snapshot(), 0L, UUID.randomUUID(), NOW);

        UUID operationlessDrop = UUID.randomUUID();
        UUID preparedDrop = UUID.randomUUID();
        UUID deliveredDrop = UUID.randomUUID();
        for (UUID dropId : List.of(operationlessDrop, preparedDrop, deliveredDrop)) {
            escrow.prepare(
                    new EscrowDrop(
                            active.eventId(),
                            dropId,
                            DropSourceKind.ENEMY,
                            UUID.randomUUID(),
                            "defense_shard",
                            "{}",
                            1,
                            Optional.empty()),
                    UUID.randomUUID(),
                    NOW.plusSeconds(1L));
        }
        UUID operationlessQueue = UUID.randomUUID();
        UUID preparedQueue = UUID.randomUUID();
        UUID deliveredQueue = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            insertLegacyQueue(
                    connection,
                    operationlessQueue,
                    active.eventId(),
                    teamId,
                    operationlessDrop,
                    "PENDING");
            insertLegacyQueue(
                    connection,
                    preparedQueue,
                    active.eventId(),
                    teamId,
                    preparedDrop,
                    "PENDING");
            insertLegacyQueue(
                    connection,
                    deliveredQueue,
                    active.eventId(),
                    teamId,
                    deliveredDrop,
                    "DELIVERED");
            insertDeliveryOperation(
                    connection,
                    UUID.randomUUID(),
                    preparedQueue,
                    active.eventId(),
                    ownerId,
                    "PREPARED",
                    NOW.plusSeconds(2L),
                    null);
            insertDeliveryOperation(
                    connection,
                    UUID.randomUUID(),
                    deliveredQueue,
                    active.eventId(),
                    ownerId,
                    "APPLIED",
                    NOW.plusSeconds(2L),
                    NOW.plusSeconds(3L));
        }

        ResourceRepository migrated = new ResourceRepository(database);

        assertEquals(1L, migrated.load(teamId, ownerId).defensePoints());
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT queue_id, status FROM event_reward_queue
                        WHERE queue_id IN (?, ?, ?) ORDER BY queue_id
                        """)) {
            statement.setString(1, operationlessQueue.toString());
            statement.setString(2, preparedQueue.toString());
            statement.setString(3, deliveredQueue.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                int rows = 0;
                while (resultSet.next()) {
                    rows++;
                    if (resultSet.getString("queue_id").equals(operationlessQueue.toString())) {
                        assertEquals("DELIVERED", resultSet.getString("status"));
                    } else if (resultSet.getString("queue_id").equals(preparedQueue.toString())) {
                        assertEquals("PENDING", resultSet.getString("status"));
                    } else {
                        assertEquals("DELIVERED", resultSet.getString("status"));
                    }
                }
                assertEquals(3, rows);
            }
        }
    }

    private static void insertLegacyQueue(
            Connection connection,
            UUID queueId,
            UUID eventId,
            UUID teamId,
            UUID sourceDropId,
            String status) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_reward_queue(
                    queue_id, event_id, scope, recipient_id, item_id, item_payload,
                    quantity, source_drop_id, status, issued_operation_id, created_at, updated_at)
                VALUES (?, ?, 'TEAM', ?, 'defense_shard', '{}', 1, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, queueId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, teamId.toString());
            statement.setString(4, sourceDropId.toString());
            statement.setString(5, status);
            statement.setString(6, UUID.randomUUID().toString());
            statement.setString(7, NOW.toString());
            statement.setString(8, NOW.toString());
            statement.executeUpdate();
        }
    }

    private static void insertDeliveryOperation(
            Connection connection,
            UUID operationId,
            UUID queueId,
            UUID eventId,
            UUID playerId,
            String state,
            Instant preparedAt,
            Instant appliedAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_reward_delivery_operations(
                    operation_id, queue_id, event_id, player_id, quantity,
                    payload_fingerprint, state, prepared_at, applied_at)
                VALUES (?, ?, ?, ?, 1, '{}', ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, queueId.toString());
            statement.setString(3, eventId.toString());
            statement.setString(4, playerId.toString());
            statement.setString(5, state);
            statement.setString(6, preparedAt.toString());
            statement.setString(7, appliedAt == null ? null : appliedAt.toString());
            statement.executeUpdate();
        }
    }

    @Test
    void feedbackUsesClaimLedgerAndAbortSettlesOnlyClaimedPoints() {
        Database database = new Database(temporaryDirectory.resolve("feedback.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        ResourceRepository resources = new ResourceRepository(database);
        EscrowRepository escrow = new EscrowRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(), teamId, UUID.randomUUID(), 0, 64, 0,
                100L, 100L, NOW, NOW);
        teams.placeCore(core, 192.0D);
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(), teamId, 1L, 8, CoreState.intact(100L));
        assertEquals(
                StartOutcome.STARTED,
                teams.tryStart(new StartRequest(session.snapshot(), core.id(), "{}", 1, NOW)));
        DefenseSession active = DefenseSession.restore(session.snapshot());
        active.completeCountdown(Set.of(ownerId));
        assertEquals(
                OperationOutcome.APPLIED,
                teams.saveTransition(active.snapshot(), 0L, UUID.randomUUID(), NOW));

        UUID dropId = UUID.randomUUID();
        escrow.prepare(
                new EscrowDrop(
                        active.eventId(),
                        dropId,
                        DropSourceKind.ENEMY,
                        UUID.randomUUID(),
                        "defense_shard",
                        "{\"schema\":1}",
                        3,
                        Optional.empty()),
                UUID.randomUUID(),
                NOW);
        escrow.claim(
                active.eventId(),
                dropId,
                ownerId,
                2,
                UUID.randomUUID(),
                NOW.plusSeconds(1L));
        ResourcePickupFeedback feedback = resources.loadPickupFeedback(
                active.eventId(), ownerId, ResourceType.DEFENSE_POINTS, 2);
        assertEquals(2, feedback.claimedQuantity());
        assertEquals(2L, feedback.eventPlayerTotal());
        assertEquals(0L, feedback.teamBalance());

        assertTrue(active.abort());
        assertEquals(
                OperationOutcome.APPLIED,
                teams.finishEvent(active.snapshot(), 1L, UUID.randomUUID(), NOW.plusSeconds(2L)));
        assertEquals(2L, resources.load(teamId, ownerId).defensePoints());
        assertEquals(
                2L,
                resources.loadTerminalSettlement(
                        active.eventId(), active.phase()).defensePoints());
        assertEquals(
                OperationOutcome.ALREADY_TERMINAL,
                teams.finishEvent(active.snapshot(), 1L, UUID.randomUUID(), NOW.plusSeconds(3L)));
    }
}
