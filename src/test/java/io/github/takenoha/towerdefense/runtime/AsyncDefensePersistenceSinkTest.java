package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.EnemyLedgerEntry;
import io.github.takenoha.towerdefense.persistence.EnemyStatus;
import io.github.takenoha.towerdefense.persistence.StartOutcome;
import io.github.takenoha.towerdefense.persistence.StartRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncDefensePersistenceSinkTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void serializesTransitionsSnapshotsAndEnemyLedger() throws Exception {
        Fixture fixture = fixture("state.db");
        try (DatabaseExecutor executor = new DatabaseExecutor("state-test-")) {
            AsyncDefensePersistenceSink sink = new AsyncDefensePersistenceSink(
                    fixture.repository(), executor);
            fixture.session().completeCountdown(Set.of(fixture.ownerId()));
            await(sink.persistState(fixture.session().snapshot(), UUID.randomUUID()));
            assertEquals(
                    DefensePhase.PREPARATION,
                    fixture.repository().findEvent(fixture.session().eventId())
                            .orElseThrow().session().phase());

            fixture.session().startWave(1L);
            await(sink.persistState(fixture.session().snapshot(), UUID.randomUUID()));
            UUID logicalEnemyId = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();
            await(sink.recordEnemySpawned(new EnemyLedgerEntry(
                    fixture.session().eventId(),
                    logicalEnemyId,
                    entityId,
                    "FOUNDATION_NORMAL",
                    1,
                    EnemyStatus.SPAWNED,
                    "{}",
                    1,
                    Instant.now())));
            fixture.session().spawnPendingEnemies(1L);
            await(sink.persistState(fixture.session().snapshot(), UUID.randomUUID()));
            await(sink.recordEnemyStatus(
                    fixture.session().eventId(),
                    logicalEnemyId,
                    entityId,
                    EnemyStatus.DEAD));

            assertEquals(
                    EnemyStatus.DEAD,
                    fixture.repository().loadEnemyLedger(fixture.session().eventId())
                            .getFirst().status());
            assertEquals(
                    1L,
                    fixture.repository().findEvent(fixture.session().eventId())
                            .orElseThrow().session().aliveEnemies());
        }
    }

    @Test
    void technicalFinishRestoresCoreAndReleasesGlobalLock() throws Exception {
        Fixture fixture = fixture("recovery.db");
        try (DatabaseExecutor executor = new DatabaseExecutor("recovery-test-")) {
            AsyncDefensePersistenceSink sink = new AsyncDefensePersistenceSink(
                    fixture.repository(), executor);
            fixture.session().completeCountdown(Set.of(fixture.ownerId()));
            await(sink.persistState(fixture.session().snapshot(), UUID.randomUUID()));
            fixture.session().startWave(1L);
            fixture.session().spawnPendingEnemies(1L);
            fixture.session().damageCore(25L);
            await(sink.persistState(fixture.session().snapshot(), UUID.randomUUID()));
            fixture.session().enterRecovery();
            await(sink.finish(fixture.session().snapshot(), UUID.randomUUID()));

            assertEquals(
                    DefensePhase.RECOVERY,
                    fixture.repository().findEvent(fixture.session().eventId())
                            .orElseThrow().session().phase());
            assertEquals(
                    fixture.core().currentHitPoints(),
                    fixture.repository().findCore(fixture.core().id())
                            .orElseThrow().currentHitPoints());
            assertFalse(fixture.repository().activeEventId().isPresent());
        }
    }

    private Fixture fixture(String databaseName) {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve(databaseName)));
        UUID ownerId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        repository.createSoloTeam(teamId, ownerId, Instant.now());
        Instant now = Instant.now();
        CoreRecord core = repository.placeCore(new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                100L,
                100L,
                now,
                now), 192.0d);
        DefenseSession session = new DefenseSession(
                UUID.randomUUID(), teamId, 1L, 8, CoreState.intact(100L));
        assertEquals(
                StartOutcome.STARTED,
                repository.tryStart(new StartRequest(
                        session.snapshot(), core.id(), "{}", 1, Instant.now())));
        return new Fixture(repository, ownerId, core, session);
    }

    private static void await(java.util.concurrent.CompletionStage<Void> stage) throws Exception {
        stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);
    }

    private record Fixture(
            DefenseRepository repository,
            UUID ownerId,
            CoreRecord core,
            DefenseSession session) {
    }
}
