package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DefenseEventPersistenceTest {
    private static final Instant STARTED_AT = Instant.parse("2026-08-03T01:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void concurrentStartsAcquireExactlyOneGlobalDatabaseLock() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("concurrent.sqlite");
        DefenseRepository setup = new DefenseRepository(new Database(databaseFile));
        UUID worldId = UUID.randomUUID();
        Fixture first = createFixture(setup, worldId, 0);
        Fixture second = createFixture(setup, worldId, 500);
        StartRequest firstRequest = startRequest(first, UUID.randomUUID());
        StartRequest secondRequest = startRequest(second, UUID.randomUUID());
        DefenseRepository firstRepository = new DefenseRepository(new Database(databaseFile));
        DefenseRepository secondRepository = new DefenseRepository(new Database(databaseFile));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Attempt firstAttempt;
        Attempt secondAttempt;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Attempt> firstFuture = executor.submit(
                    () -> attemptStart(firstRepository, firstRequest, ready, release));
            Future<Attempt> secondFuture = executor.submit(
                    () -> attemptStart(secondRepository, secondRequest, ready, release));
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            release.countDown();
            firstAttempt = firstFuture.get(10L, TimeUnit.SECONDS);
            secondAttempt = secondFuture.get(10L, TimeUnit.SECONDS);
        }

        long startedCount = List.of(firstAttempt, secondAttempt).stream()
                .filter(attempt -> attempt.outcome() == StartOutcome.STARTED)
                .count();
        assertEquals(1L, startedCount);
        assertEquals(1L, List.of(firstAttempt, secondAttempt).stream()
                .filter(attempt -> attempt.outcome() == StartOutcome.LOCKED)
                .count());
        UUID startedEvent = firstAttempt.outcome() == StartOutcome.STARTED
                ? firstAttempt.eventId()
                : secondAttempt.eventId();
        assertEquals(startedEvent, setup.activeEventId().orElseThrow());
        assertEquals(1, setup.loadUnfinishedEvents().size());
    }

    @Test
    void terminalOperationAndLockReleaseAreIdempotent() {
        Path databaseFile = temporaryDirectory.resolve("terminal.sqlite");
        DefenseRepository repository = new DefenseRepository(new Database(databaseFile));
        Fixture fixture = createFixture(repository, UUID.randomUUID(), 0);
        UUID eventId = UUID.randomUUID();
        StartRequest request = startRequest(fixture, eventId);
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));

        DefenseSession session = DefenseSession.restore(request.session());
        session.completeCountdown(Set.of(fixture.ownerId()));
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(),
                        0L,
                        UUID.randomUUID(),
                        STARTED_AT.plusSeconds(1L)));
        session.startWave(1L);
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(),
                        1L,
                        UUID.randomUUID(),
                        STARTED_AT.plusSeconds(2L)));
        EnemyLedgerEntry unsettledEnemy = new EnemyLedgerEntry(
                eventId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "late_status_zombie",
                1,
                EnemyStatus.SPAWNED,
                "{}",
                1,
                STARTED_AT.plusSeconds(2L));
        repository.upsertEnemy(unsettledEnemy);
        assertTrue(session.abort());
        UUID operationId = UUID.randomUUID();
        Instant terminalAt = STARTED_AT.plusSeconds(5L);

        assertEquals(
                OperationOutcome.APPLIED,
                repository.finishEvent(session.snapshot(), 2L, operationId, terminalAt));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                repository.finishEvent(session.snapshot(), 2L, operationId, terminalAt));
        assertEquals(
                OperationOutcome.ALREADY_TERMINAL,
                repository.finishEvent(
                        session.snapshot(), UUID.randomUUID(), terminalAt.plusSeconds(1L)));

        assertTrue(repository.activeEventId().isEmpty());
        StoredDefenseEvent stored = repository.findEvent(eventId).orElseThrow();
        assertEquals(DefensePhase.ABORTED, stored.session().phase());
        assertEquals(3L, stored.revision());
        assertEquals(operationId, stored.terminalOperationId().orElseThrow());
        assertEquals(EnemyStatus.DESPAWNED, repository.loadEnemyLedger(eventId).getFirst().status());
        assertEquals(3, repository.loadTransitions(eventId).size());
        assertEquals(
                TeamProgress.initial(fixture.teamId()),
                repository.loadTeamProgress(fixture.teamId()));

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals(stored, reopened.findEvent(eventId).orElseThrow());
        assertTrue(reopened.activeEventId().isEmpty());
    }

    @Test
    void victoryAdvancesTeamStageUnlockInsideTheTerminalTransaction() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("victory-progress.sqlite")));
        Fixture fixture = createFixture(repository, UUID.randomUUID(), 0);
        UUID eventId = UUID.randomUUID();
        StartRequest request = startRequest(fixture, eventId);
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));

        DefenseSession session = DefenseSession.restore(request.session());
        session.completeCountdown(Set.of(fixture.ownerId()));
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(), 0L, UUID.randomUUID(), STARTED_AT.plusSeconds(1L)));
        long revision = 1L;
        for (int wave = 1; wave <= session.totalWaves(); wave++) {
            session.startWave(1L);
            assertEquals(
                    OperationOutcome.APPLIED,
                    repository.saveTransition(
                            session.snapshot(), revision, UUID.randomUUID(),
                            STARTED_AT.plusSeconds(wave * 2L)));
            revision++;
            session.spawnPendingEnemies(1L);
            assertTrue(session.recordEnemyDefeated(1L));
            if (wave < session.totalWaves()) {
                assertEquals(
                        OperationOutcome.APPLIED,
                        repository.saveTransition(
                                session.snapshot(), revision, UUID.randomUUID(),
                                STARTED_AT.plusSeconds(wave * 2L + 1L)));
                revision++;
            }
        }
        assertEquals(DefensePhase.VICTORY, session.phase());

        UUID operationId = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                repository.finishEvent(
                        session.snapshot(), revision, operationId, STARTED_AT.plusSeconds(20L)));
        assertEquals(
                new TeamProgress(fixture.teamId(), 1L, 2L, 0L),
                repository.loadTeamProgress(fixture.teamId()));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                repository.finishEvent(
                        session.snapshot(), revision, operationId, STARTED_AT.plusSeconds(21L)));
        assertEquals(
                new TeamProgress(fixture.teamId(), 1L, 2L, 0L),
                repository.loadTeamProgress(fixture.teamId()));
    }

    @Test
    void victoryIssuesOneTeamBoundCrystalBatchAndRedemptionIsIdempotent() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("research-crystal.sqlite")));
        Fixture fixture = createFixture(repository, UUID.randomUUID(), 0);
        UUID eventId = UUID.randomUUID();
        assertEquals(StartOutcome.STARTED, repository.tryStart(startRequest(fixture, eventId)));

        DefenseSession session = DefenseSession.restore(startRequest(fixture, eventId).session());
        session.completeCountdown(Set.of(fixture.ownerId()));
        long revision = 0L;
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(), revision, UUID.randomUUID(), STARTED_AT.plusSeconds(1L)));
        revision++;
        for (int wave = 1; wave <= session.totalWaves(); wave++) {
            session.startWave(1L);
            assertEquals(
                    OperationOutcome.APPLIED,
                    repository.saveTransition(
                            session.snapshot(), revision, UUID.randomUUID(),
                            STARTED_AT.plusSeconds(wave * 2L)));
            revision++;
            session.spawnPendingEnemies(1L);
            assertTrue(session.recordEnemyDefeated(1L));
            if (wave < session.totalWaves()) {
                assertEquals(
                        OperationOutcome.APPLIED,
                        repository.saveTransition(
                                session.snapshot(), revision, UUID.randomUUID(),
                                STARTED_AT.plusSeconds(wave * 2L + 1L)));
                revision++;
            }
        }
        UUID terminalOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                repository.finishEvent(
                        session.snapshot(), revision, terminalOperation, STARTED_AT.plusSeconds(20L)));

        EscrowRepository escrow = new EscrowRepository(
                new Database(temporaryDirectory.resolve("research-crystal.sqlite")));
        RewardQueueEntry crystalQueue = escrow.loadRewardQueue(eventId).stream()
                .filter(entry -> entry.itemId().equals("research_crystal"))
                .findFirst()
                .orElseThrow();
        assertEquals(100, crystalQueue.quantity());
        ResearchCrystalBatch batch = repository.findResearchCrystalBatch(
                        crystalQueue.sourceDropId())
                .orElseThrow();
        assertEquals(fixture.teamId(), batch.teamId());
        assertEquals(100, batch.remainingQuantity());

        UUID redemptionOperation = UUID.randomUUID();
        ResearchCrystalRedemption prepared = repository.prepareResearchCrystalRedemption(
                batch.batchId(),
                fixture.core().id(),
                fixture.ownerId(),
                100,
                redemptionOperation,
                STARTED_AT.plusSeconds(21L));
        assertEquals(ResearchCrystalRedemptionState.PREPARED, prepared.state());
        assertEquals(
                prepared,
                repository.prepareResearchCrystalRedemption(
                        batch.batchId(),
                        fixture.core().id(),
                        fixture.ownerId(),
                        100,
                        redemptionOperation,
                        STARTED_AT.plusSeconds(22L)));

        ResearchCrystalRedemptionResult applied = repository.applyResearchCrystalRedemption(
                redemptionOperation, STARTED_AT.plusSeconds(23L));
        assertEquals(OperationOutcome.APPLIED, applied.outcome());
        assertEquals(100L, applied.progress().researchPoints());
        assertEquals(ResearchCrystalBatchStatus.EXHAUSTED, applied.batch().status());
        ResearchCrystalRedemptionResult replay = repository.applyResearchCrystalRedemption(
                redemptionOperation, STARTED_AT.plusSeconds(24L));
        assertEquals(OperationOutcome.ALREADY_APPLIED, replay.outcome());
        assertEquals(100L, repository.loadTeamProgress(fixture.teamId()).researchPoints());
    }

    @Test
    void staleSamePhaseSnapshotCannotRollBackNewerCoreHp() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("stale-cas.sqlite")));
        Fixture fixture = createFixture(repository, UUID.randomUUID(), 0);
        UUID eventId = UUID.randomUUID();
        StartRequest request = startRequest(fixture, eventId);
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));

        DefenseSession session = DefenseSession.restore(request.session());
        session.completeCountdown(Set.of(fixture.ownerId()));
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(),
                        0L,
                        UUID.randomUUID(),
                        STARTED_AT.plusSeconds(1L)));
        session.startWave(1L);
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(),
                        1L,
                        UUID.randomUUID(),
                        STARTED_AT.plusSeconds(2L)));
        var staleSnapshot = session.snapshot();

        assertFalse(session.damageCore(20L));
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveSnapshot(
                        session.snapshot(), 2L, STARTED_AT.plusSeconds(3L)));
        assertEquals(
                OperationOutcome.STATE_MISMATCH,
                repository.saveSnapshot(
                        staleSnapshot, 2L, STARTED_AT.plusSeconds(4L)));
        assertEquals(
                OperationOutcome.STATE_MISMATCH,
                repository.saveSnapshot(staleSnapshot, STARTED_AT.plusSeconds(5L)));

        StoredDefenseEvent durable = repository.findEvent(eventId).orElseThrow();
        assertEquals(3L, durable.revision());
        assertEquals(60L, durable.session().coreState().currentHitPoints());
        assertEquals(60L, repository.findCore(fixture.core().id())
                .orElseThrow()
                .currentHitPoints());
    }

    @Test
    void operationUuidIsBoundToExactPayloadAndTargetRevision() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("operation-payload.sqlite")));
        Fixture fixture = createFixture(repository, UUID.randomUUID(), 0);
        UUID eventId = UUID.randomUUID();
        StartRequest request = startRequest(fixture, eventId);
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));

        DefenseSession first = DefenseSession.restore(request.session());
        first.completeCountdown(Set.of(fixture.ownerId()));
        DefenseSession different = DefenseSession.restore(request.session());
        UUID differentParticipant = UUID.randomUUID();
        different.completeCountdown(Set.of(differentParticipant));
        UUID operationId = UUID.randomUUID();

        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        first.snapshot(), 0L, operationId, STARTED_AT.plusSeconds(1L)));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                repository.saveTransition(
                        first.snapshot(), 0L, operationId, STARTED_AT.plusSeconds(2L)));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.saveTransition(
                        different.snapshot(),
                        0L,
                        operationId,
                        STARTED_AT.plusSeconds(3L)));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.saveTransition(
                        first.snapshot(),
                        1L,
                        operationId,
                        STARTED_AT.plusSeconds(4L)));

        StoredDefenseEvent durable = repository.findEvent(eventId).orElseThrow();
        assertEquals(1L, durable.revision());
        assertEquals(Set.of(fixture.ownerId()), durable.session().registeredParticipants());
        assertFalse(durable.session().registeredParticipants().contains(differentParticipant));
        assertEquals(1, repository.loadTransitions(eventId).size());
    }

    @Test
    void corePlacementIsRejectedWhileGlobalEventLockIsActive() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("placement-lock.sqlite")));
        UUID worldId = UUID.randomUUID();
        Fixture active = createFixture(repository, worldId, 0);
        StartRequest request = startRequest(active, UUID.randomUUID());
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));

        UUID waitingTeamId = UUID.randomUUID();
        repository.createSoloTeam(waitingTeamId, UUID.randomUUID(), STARTED_AT);
        CoreRecord waitingCore = new CoreRecord(
                UUID.randomUUID(),
                waitingTeamId,
                worldId,
                500,
                64,
                0,
                100L,
                100L,
                STARTED_AT,
                STARTED_AT);
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.placeCore(waitingCore, 192.0D));
        assertTrue(repository.findCoreByTeam(waitingTeamId).isEmpty());

        DefenseSession activeSession = DefenseSession.restore(request.session());
        assertTrue(activeSession.abort());
        assertEquals(
                OperationOutcome.APPLIED,
                repository.finishEvent(
                        activeSession.snapshot(),
                        0L,
                        UUID.randomUUID(),
                        STARTED_AT.plusSeconds(1L)));
        assertEquals(waitingCore, repository.placeCore(waitingCore, 192.0D));
    }

    @Test
    void reopensFullSnapshotAndRecoversCoreEnemyLedgerAndLockAtomically() {
        Path databaseFile = temporaryDirectory.resolve("recovery.sqlite");
        DefenseRepository repository = new DefenseRepository(new Database(databaseFile));
        Fixture fixture = createFixture(repository, UUID.randomUUID(), 0);
        UUID eventId = UUID.randomUUID();
        StartRequest request = startRequest(fixture, eventId);
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));

        UUID participant = fixture.ownerId();
        DefenseSession session = DefenseSession.restore(request.session());
        session.completeCountdown(Set.of(participant));
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(),
                        UUID.randomUUID(),
                        STARTED_AT.plusSeconds(1L)));
        session.startWave(2L);
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(
                        session.snapshot(),
                        UUID.randomUUID(),
                        STARTED_AT.plusSeconds(2L)));

        UUID enemyId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        repository.upsertEnemy(new EnemyLedgerEntry(
                eventId,
                enemyId,
                entityId,
                "basic_zombie",
                1,
                EnemyStatus.ALLOCATED,
                "{\"health\":20}",
                1,
                STARTED_AT.plusSeconds(2L)));
        repository.updateEnemyStatus(
                eventId,
                enemyId,
                entityId,
                EnemyStatus.SPAWNED,
                STARTED_AT.plusSeconds(3L));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.updateEnemyStatus(
                        eventId,
                        enemyId,
                        UUID.randomUUID(),
                        EnemyStatus.DEAD,
                        STARTED_AT.plusSeconds(4L)));

        session.spawnPendingEnemies(1L);
        assertFalse(session.damageCore(20L));
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveSnapshot(session.snapshot(), STARTED_AT.plusSeconds(4L)));

        StoredDefenseEvent beforeRecovery = repository.findEvent(eventId).orElseThrow();
        assertEquals(Set.of(participant), beforeRecovery.session().registeredParticipants());
        assertEquals(Set.of(participant), beforeRecovery.session().effectiveParticipants());
        assertEquals("{\"combatRadius\":80}", beforeRecovery.configSnapshot());
        assertEquals(7, beforeRecovery.configVersion());
        assertEquals(60L, beforeRecovery.session().coreState().currentHitPoints());
        assertEquals(EnemyStatus.SPAWNED, repository.loadEnemyLedger(eventId).getFirst().status());
        assertEquals(List.of(beforeRecovery), repository.loadUnfinishedEvents());

        UUID recoveryOperation = UUID.randomUUID();
        Instant recoveredAt = STARTED_AT.plusSeconds(10L);
        assertEquals(
                OperationOutcome.APPLIED,
                repository.recoverUnfinishedEvent(eventId, recoveryOperation, recoveredAt));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                repository.recoverUnfinishedEvent(eventId, recoveryOperation, recoveredAt));

        StoredDefenseEvent recovered = repository.findEvent(eventId).orElseThrow();
        assertEquals(DefensePhase.RECOVERY, recovered.session().phase());
        assertEquals(80L, recovered.session().coreState().currentHitPoints());
        assertEquals(80L, repository.findCore(fixture.core().id())
                .orElseThrow()
                .currentHitPoints());
        assertEquals(recoveryOperation, recovered.terminalOperationId().orElseThrow());
        assertTrue(repository.activeEventId().isEmpty());
        assertTrue(repository.loadUnfinishedEvents().isEmpty());
        assertEquals(
                EnemyStatus.RECOVERY_REMOVED,
                repository.loadEnemyLedger(eventId).getFirst().status());
        assertEquals(3, repository.loadTransitions(eventId).size());

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals(recovered, reopened.findEvent(eventId).orElseThrow());
        assertEquals(
                EnemyStatus.RECOVERY_REMOVED,
                reopened.loadEnemyLedger(eventId).getFirst().status());
        assertEquals(fixture.core().id(), reopened.loadAllCores().getFirst().id());
        assertTrue(reopened.loadUnfinishedEvents().isEmpty());
    }

    private static Attempt attemptStart(
            DefenseRepository repository,
            StartRequest request,
            CountDownLatch ready,
            CountDownLatch release) throws InterruptedException {
        ready.countDown();
        release.await();
        return new Attempt(request.session().eventId(), repository.tryStart(request));
    }

    private static Fixture createFixture(
            DefenseRepository repository, UUID worldId, int blockX) {
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, STARTED_AT.minusSeconds(10L));
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                worldId,
                blockX,
                64,
                0,
                80L,
                100L,
                STARTED_AT.minusSeconds(5L),
                STARTED_AT.minusSeconds(5L));
        repository.placeCore(core, 192.0D);
        return new Fixture(teamId, ownerId, core);
    }

    private static StartRequest startRequest(Fixture fixture, UUID eventId) {
        DefenseSession session = new DefenseSession(
                eventId,
                fixture.teamId(),
                1L,
                8,
                new CoreState(
                        fixture.core().maximumHitPoints(),
                        fixture.core().currentHitPoints(),
                        true));
        return new StartRequest(
                session.snapshot(),
                fixture.core().id(),
                "{\"combatRadius\":80}",
                7,
                STARTED_AT);
    }

    private record Fixture(UUID teamId, UUID ownerId, CoreRecord core) {
    }

    private record Attempt(UUID eventId, StartOutcome outcome) {
    }
}
