package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;
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
