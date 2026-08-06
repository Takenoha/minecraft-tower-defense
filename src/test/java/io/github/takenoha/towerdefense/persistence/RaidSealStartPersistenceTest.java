package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RaidSealStartPersistenceTest {
    private static final Instant START = Instant.parse("2026-08-04T04:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void reservedStartCanBeConsumedOnlyAfterPhysicalRemovalBoundary() {
        Database database = new Database(temporaryDirectory.resolve("reserved-start.sqlite"));
        DefenseRepository repository = new DefenseRepository(database);
        RaidSealRepository seals = new RaidSealRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, START.minusSeconds(2L));
        CoreRecord core = placeCore(repository, teamId);
        UUID sealId = UUID.randomUUID();
        seals.register(sealId, ownerId, 1L, START.minusSeconds(1L));
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                teamId,
                1L,
                8,
                new CoreState(100L, 100L, true));
        StartRequest request = new StartRequest(
                session.snapshot(),
                core.id(),
                "{}",
                1,
                START,
                Optional.of(sealId));

        assertEquals(StartOutcome.STARTED, repository.tryStartReserved(request));
        assertEquals(RaidSealStatus.RESERVED, seals.find(sealId).orElseThrow().status());
        assertEquals(
                OperationOutcome.APPLIED,
                repository.consumeReservedStartSeal(session.eventId(), sealId, START.plusSeconds(1L)));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                repository.consumeReservedStartSeal(session.eventId(), sealId, START.plusSeconds(2L)));
        assertEquals(RaidSealStatus.CONSUMED, seals.find(sealId).orElseThrow().status());
    }

    @Test
    void technicalRecoveryReturnsAFreshAvailableSealAndNeverReenablesOriginalUuid() {
        Database database = new Database(temporaryDirectory.resolve("reserved-recovery.sqlite"));
        DefenseRepository repository = new DefenseRepository(database);
        RaidSealRepository seals = new RaidSealRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, START.minusSeconds(2L));
        CoreRecord core = placeCore(repository, teamId);
        UUID originalSealId = UUID.randomUUID();
        seals.register(originalSealId, ownerId, 1L, START.minusSeconds(1L));
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                teamId,
                1L,
                8,
                new CoreState(100L, 100L, true));
        StartRequest request = new StartRequest(
                session.snapshot(),
                core.id(),
                "{}",
                1,
                START,
                Optional.of(originalSealId));
        repository.tryStartReserved(request);
        repository.consumeReservedStartSeal(session.eventId(), originalSealId, START);

        UUID recoveryOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                repository.recoverUnfinishedEvent(
                        session.eventId(), recoveryOperation, START.plusSeconds(1L)));

        RaidSeal returned = seals.loadAvailableRefunds(ownerId).getFirst();
        assertNotEquals(originalSealId, returned.sealId());
        assertEquals(RaidSealStatus.REFUNDED, seals.find(originalSealId).orElseThrow().status());
        assertEquals(RaidSealStatus.AVAILABLE, returned.status());
    }

    private static CoreRecord placeCore(DefenseRepository repository, UUID teamId) {
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                100L,
                100L,
                START.minusSeconds(1L),
                START.minusSeconds(1L));
        repository.placeCore(core, 192.0D);
        return core;
    }
}
