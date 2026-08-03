package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RollbackEscrowPersistenceTest {
    private static final Instant START = Instant.parse("2026-08-03T04:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void writeAheadLedgerIsIdempotentAndRecoveryNeverOverwritesPlayerChanges() {
        Fixture fixture = activeFixture("ledger.sqlite");
        BlockChangeRepository ledger = new BlockChangeRepository(fixture.database());
        UUID changeId = UUID.randomUUID();
        BlockChange change = new BlockChange(
                fixture.eventId(),
                changeId,
                UUID.randomUUID(),
                12,
                65,
                -4,
                BlockChangeKind.EVENT_BLOCK,
                1L,
                "minecraft:stone",
                "{}",
                "minecraft:air",
                "{}");
        UUID prepareOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                ledger.prepare(change, prepareOperation, START));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                ledger.prepare(change, prepareOperation, START.plusSeconds(1L)));
        assertEquals(BlockChangeStatus.PREPARED, ledger.loadChanges(fixture.eventId()).getFirst().status());

        BlockRollbackPlanner planner = new BlockRollbackPlanner();
        StoredBlockChange prepared = ledger.loadChanges(fixture.eventId()).getFirst();
        assertEquals(
                BlockRollbackDecision.RESTORE,
                planner.decide(prepared, new BlockStateSnapshot("minecraft:air", "{}")));
        assertEquals(
                BlockRollbackDecision.SKIP_ALREADY_BEFORE,
                planner.decide(prepared, new BlockStateSnapshot("minecraft:stone", "{}")));
        assertEquals(
                BlockRollbackDecision.CONFLICT,
                planner.decide(prepared, new BlockStateSnapshot("minecraft:gold_block", "{}")));

        UUID applyOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                ledger.prepareApply(fixture.eventId(), changeId, applyOperation, START));
        assertEquals(
                OperationOutcome.APPLIED,
                ledger.apply(fixture.eventId(), changeId, applyOperation, START.plusSeconds(1L)));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                ledger.apply(fixture.eventId(), changeId, applyOperation, START.plusSeconds(2L)));

        UUID rollbackOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                ledger.prepareRollback(
                        fixture.eventId(),
                        changeId,
                        rollbackOperation,
                        BlockRollbackDecision.RESTORE,
                        START.plusSeconds(3L)));
        assertEquals(
                OperationOutcome.APPLIED,
                ledger.applyRollback(
                        fixture.eventId(),
                        changeId,
                        rollbackOperation,
                        BlockRollbackDecision.RESTORE,
                        START.plusSeconds(4L)));
        assertEquals(
                OperationOutcome.ALREADY_APPLIED,
                ledger.applyRollback(
                        fixture.eventId(),
                        changeId,
                        rollbackOperation,
                        BlockRollbackDecision.RESTORE,
                        START.plusSeconds(5L)));
        assertEquals(BlockChangeStatus.ROLLED_BACK, ledger.loadChanges(fixture.eventId()).getFirst().status());
        assertTrue(ledger.loadUnresolvedChanges(fixture.eventId()).isEmpty());

        UUID conflictChangeId = UUID.randomUUID();
        BlockChange conflictChange = new BlockChange(
                fixture.eventId(),
                conflictChangeId,
                change.worldId(),
                change.blockX(),
                change.blockY(),
                change.blockZ(),
                BlockChangeKind.TEMPORARY_BLOCK,
                2L,
                "minecraft:air",
                "{}",
                "minecraft:glass",
                "{}");
        ledger.prepare(conflictChange, UUID.randomUUID(), START.plusSeconds(6L));
        UUID conflictOperation = UUID.randomUUID();
        ledger.prepareRollback(
                fixture.eventId(),
                conflictChangeId,
                conflictOperation,
                BlockRollbackDecision.CONFLICT,
                START.plusSeconds(7L));
        ledger.applyRollback(
                fixture.eventId(),
                conflictChangeId,
                conflictOperation,
                BlockRollbackDecision.CONFLICT,
                START.plusSeconds(8L));
        assertEquals(
                BlockChangeStatus.CONFLICT,
                ledger.loadChanges(fixture.eventId()).getFirst().status());
    }

    @Test
    void eventRecoveryRefusesUnresolvedBlockChangesUntilAWorldDecisionIsRecorded() {
        Fixture fixture = activeFixture("ledger-guard.sqlite");
        BlockChangeRepository ledger = new BlockChangeRepository(fixture.database());
        BlockChange change = new BlockChange(
                fixture.eventId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                64,
                0,
                BlockChangeKind.TEMPORARY_BLOCK,
                1L,
                "minecraft:air",
                "{}",
                "minecraft:glass",
                "{}");
        ledger.prepare(change, UUID.randomUUID(), START);
        assertThrows(
                PersistenceConflictException.class,
                () -> fixture.repository().recoverUnfinishedEvent(
                        fixture.eventId(), UUID.randomUUID(), START.plusSeconds(1L)));

        UUID rollbackOperation = UUID.randomUUID();
        ledger.prepareRollback(
                fixture.eventId(),
                change.changeId(),
                rollbackOperation,
                BlockRollbackDecision.SKIP_ALREADY_BEFORE,
                START.plusSeconds(2L));
        ledger.applyRollback(
                fixture.eventId(),
                change.changeId(),
                rollbackOperation,
                BlockRollbackDecision.SKIP_ALREADY_BEFORE,
                START.plusSeconds(3L));
        assertEquals(
                OperationOutcome.APPLIED,
                fixture.repository().recoverUnfinishedEvent(
                        fixture.eventId(), UUID.randomUUID(), START.plusSeconds(4L)));
        assertEquals(DefensePhase.RECOVERY, fixture.repository()
                .findEvent(fixture.eventId()).orElseThrow().session().phase());
    }

    @Test
    void preparedRollbackDecisionSurvivesDatabaseReopen() {
        Path databaseFile = temporaryDirectory.resolve("prepared-rollback.sqlite");
        Database database = new Database(databaseFile);
        DefenseRepository repository = new DefenseRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, START.minusSeconds(10L));
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                100L,
                100L,
                START.minusSeconds(5L),
                START.minusSeconds(5L));
        repository.placeCore(core, 192.0D);
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(), teamId, 1L, 8, CoreState.intact(100L));
        assertEquals(
                StartOutcome.STARTED,
                repository.tryStart(new StartRequest(
                        session.snapshot(), core.id(), "{}", 1, START)));

        BlockChangeRepository ledger = new BlockChangeRepository(database);
        BlockChange change = new BlockChange(
                session.eventId(),
                UUID.randomUUID(),
                core.worldId(),
                4,
                65,
                4,
                BlockChangeKind.TEMPORARY_BLOCK,
                1L,
                "minecraft:air",
                "minecraft:air",
                "minecraft:glass",
                "minecraft:glass");
        ledger.prepare(change, UUID.randomUUID(), START);
        UUID rollbackOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                ledger.prepareRollback(
                        session.eventId(),
                        change.changeId(),
                        rollbackOperation,
                        BlockRollbackDecision.RESTORE,
                        START.plusSeconds(1L)));
        assertEquals(
                Optional.of(new PreparedRollback(rollbackOperation, BlockRollbackDecision.RESTORE)),
                ledger.loadPreparedRollback(session.eventId(), change.changeId()));

        BlockChangeRepository reopened = new BlockChangeRepository(new Database(databaseFile));
        assertEquals(
                Optional.of(new PreparedRollback(rollbackOperation, BlockRollbackDecision.RESTORE)),
                reopened.loadPreparedRollback(session.eventId(), change.changeId()));
    }

    @Test
    void escrowClaimsOnlyRegisteredParticipantsAndNormalAbortSettlesWithoutDuplication() {
        Fixture fixture = activeFixture("escrow-abort.sqlite");
        EscrowRepository escrow = new EscrowRepository(fixture.database());
        UUID dropId = UUID.randomUUID();
        EscrowDrop drop = new EscrowDrop(
                fixture.eventId(),
                dropId,
                DropSourceKind.ENEMY,
                UUID.randomUUID(),
                "defense_shard",
                "{\"schema\":1}",
                5,
                Optional.of(UUID.randomUUID()));
        UUID createOperation = UUID.randomUUID();
        assertEquals(OperationOutcome.APPLIED, escrow.prepare(drop, createOperation, START));
        assertEquals(OperationOutcome.ALREADY_APPLIED, escrow.prepare(drop, createOperation, START));

        UUID claimOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                escrow.prepareClaim(
                        fixture.eventId(),
                        dropId,
                        fixture.ownerId(),
                        2,
                        claimOperation,
                        START.plusSeconds(1L)));
        assertEquals(
                new EscrowClaimResult(OperationOutcome.APPLIED, 2),
                escrow.applyClaim(
                        fixture.eventId(),
                        dropId,
                        fixture.ownerId(),
                        2,
                        claimOperation,
                        START.plusSeconds(2L)));
        assertEquals(
                new EscrowClaimResult(OperationOutcome.ALREADY_APPLIED, 2),
                escrow.applyClaim(
                        fixture.eventId(),
                        dropId,
                        fixture.ownerId(),
                        2,
                        claimOperation,
                        START.plusSeconds(3L)));
        assertThrows(
                PersistenceConflictException.class,
                () -> escrow.claim(
                        fixture.eventId(),
                        dropId,
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        START.plusSeconds(4L)));

        assertTrue(fixture.session().abort());
        UUID finishOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                fixture.repository().finishEvent(
                        fixture.session().snapshot(),
                        1L,
                        finishOperation,
                        START.plusSeconds(5L)));
        StoredEscrowDrop settled = escrow.loadDrops(fixture.eventId()).getFirst();
        assertEquals(EscrowDropStatus.SETTLED, settled.status());
        assertEquals(2, settled.claimedQuantity());
        assertEquals(1, escrow.loadRewardQueue(fixture.eventId()).size());
        assertEquals(
                RewardQueueScope.PLAYER,
                escrow.loadRewardQueue(fixture.eventId()).getFirst().scope());
        assertEquals(
                OperationOutcome.ALREADY_TERMINAL,
                fixture.repository().finishEvent(
                        fixture.session().snapshot(),
                        1L,
                        UUID.randomUUID(),
                        START.plusSeconds(6L)));
        assertEquals(1, escrow.loadRewardQueue(fixture.eventId()).size());
    }

    @Test
    void victoryMovesUnclaimedEscrowToOneTeamQueue() {
        Fixture fixture = activeFixture("escrow-victory.sqlite");
        EscrowRepository escrow = new EscrowRepository(fixture.database());
        EscrowDrop drop = new EscrowDrop(
                fixture.eventId(),
                UUID.randomUUID(),
                DropSourceKind.BLOCK,
                UUID.randomUUID(),
                "defense_shard",
                "{\"schema\":1}",
                3,
                Optional.empty());
        escrow.prepare(drop, UUID.randomUUID(), START);

        long revision = 1L;
        for (int wave = 1; wave <= fixture.session().totalWaves(); wave++) {
            fixture.session().startWave(1L);
            assertEquals(
                    OperationOutcome.APPLIED,
                    fixture.repository().saveTransition(
                            fixture.session().snapshot(),
                            revision,
                            UUID.randomUUID(),
                            START.plusSeconds(revision)));
            revision++;
            fixture.session().spawnPendingEnemies(1L);
            assertEquals(
                    OperationOutcome.APPLIED,
                    fixture.repository().saveSnapshot(
                            fixture.session().snapshot(), revision, START.plusSeconds(revision)));
            revision++;
            fixture.session().recordEnemyDefeated(1L);
            if (wave < fixture.session().totalWaves()) {
                assertEquals(
                        OperationOutcome.APPLIED,
                        fixture.repository().saveTransition(
                                fixture.session().snapshot(),
                                revision,
                                UUID.randomUUID(),
                                START.plusSeconds(revision)));
                revision++;
            }
        }
        assertEquals(DefensePhase.VICTORY, fixture.session().phase());
        assertEquals(
                OperationOutcome.APPLIED,
                fixture.repository().finishEvent(
                        fixture.session().snapshot(),
                        revision,
                        UUID.randomUUID(),
                        START.plusSeconds(revision)));
        assertEquals(1, escrow.loadRewardQueue(fixture.eventId()).size());
        RewardQueueEntry queue = escrow.loadRewardQueue(fixture.eventId()).getFirst();
        assertEquals(RewardQueueScope.TEAM, queue.scope());
        assertEquals(fixture.teamId(), queue.recipientId());
        assertEquals(3, queue.quantity());
    }

    @Test
    void consumedSealIsNeverReenabledAndTechnicalRefundCreatesOneNewUuid() {
        Path databaseFile = temporaryDirectory.resolve("seals.sqlite");
        Database database = new Database(databaseFile);
        DefenseRepository repository = new DefenseRepository(database);
        RaidSealRepository seals = new RaidSealRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, START.minusSeconds(10L));
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                100L,
                100L,
                START.minusSeconds(5L),
                START.minusSeconds(5L));
        repository.placeCore(core, 192.0D);
        UUID sealId = UUID.randomUUID();
        seals.register(sealId, ownerId, 1L, START.minusSeconds(2L));
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
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));
        assertEquals(RaidSealStatus.CONSUMED, seals.find(sealId).orElseThrow().status());
        EscrowRepository escrow = new EscrowRepository(database);
        EscrowDrop pendingDrop = new EscrowDrop(
                session.eventId(),
                UUID.randomUUID(),
                DropSourceKind.ENEMY,
                UUID.randomUUID(),
                "defense_shard",
                "{\"schema\":1}",
                1,
                Optional.of(UUID.randomUUID()));
        escrow.prepare(pendingDrop, UUID.randomUUID(), START);

        UUID recoveryOperation = UUID.randomUUID();
        assertEquals(
                OperationOutcome.APPLIED,
                repository.recoverUnfinishedEvent(
                        session.eventId(), recoveryOperation, START.plusSeconds(1L)));
        RaidSealRefundResult refund = seals.refund(
                session.eventId(), recoveryOperation, START.plusSeconds(2L));
        assertEquals(OperationOutcome.ALREADY_APPLIED, refund.outcome());
        assertNotEquals(sealId, refund.returnedSeal().sealId());
        assertEquals(RaidSealStatus.REFUNDED, seals.find(sealId).orElseThrow().status());
        assertEquals(RaidSealStatus.AVAILABLE, refund.returnedSeal().status());
        assertEquals(EscrowDropStatus.VOIDED, escrow.loadDrops(session.eventId()).getFirst().status());
        assertEquals(
                1,
                seals.loadForOwner(ownerId).stream()
                        .filter(seal -> seal.status() == RaidSealStatus.AVAILABLE)
                        .count());
    }

    private Fixture activeFixture(String fileName) {
        Database database = new Database(temporaryDirectory.resolve(fileName));
        DefenseRepository repository = new DefenseRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, START.minusSeconds(10L));
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                100L,
                100L,
                START.minusSeconds(5L),
                START.minusSeconds(5L));
        repository.placeCore(core, 192.0D);
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                teamId,
                1L,
                8,
                new CoreState(100L, 100L, true));
        StartRequest request = new StartRequest(
                session.snapshot(), core.id(), "{}", 1, START);
        assertEquals(StartOutcome.STARTED, repository.tryStart(request));
        DefenseSession active = DefenseSession.restore(request.session());
        active.completeCountdown(Set.of(ownerId));
        assertEquals(
                OperationOutcome.APPLIED,
                repository.saveTransition(active.snapshot(), 0L, UUID.randomUUID(), START));
        return new Fixture(database, repository, teamId, ownerId, active);
    }

    private record Fixture(
            Database database,
            DefenseRepository repository,
            UUID teamId,
            UUID ownerId,
            DefenseSession session) {
        private UUID eventId() {
            return session.eventId();
        }
    }
}
