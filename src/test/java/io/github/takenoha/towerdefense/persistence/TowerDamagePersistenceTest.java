package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TowerDamagePersistenceTest {
    private static final Instant STARTED_AT = Instant.parse("2026-08-05T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void destroyerDamageIsAtomicIdempotentAndDeletesInvestedTowerAtZero() {
        Database database = new Database(temporaryDirectory.resolve("tower-damage.sqlite"));
        DefenseRepository defense = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        defense.createSoloTeam(teamId, ownerId, STARTED_AT.minusSeconds(10L));
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                worldId,
                0,
                64,
                0,
                100L,
                100L,
                STARTED_AT.minusSeconds(5L),
                STARTED_AT.minusSeconds(5L));
        defense.placeCore(core, 192.0D);
        TowerRecord tower = installTower(towers, teamId, ownerId, worldId);

        UUID eventId = UUID.randomUUID();
        DefenseSession session = new DefenseSession(
                eventId,
                teamId,
                1L,
                8,
                new CoreState(100L, 100L, true));
        assertEquals(
                StartOutcome.STARTED,
                defense.tryStart(new StartRequest(
                        session.snapshot(),
                        core.id(),
                        "{}",
                        1,
                        STARTED_AT)));
        session.completeCountdown(Set.of(ownerId));
        assertEquals(
                OperationOutcome.APPLIED,
                defense.saveTransition(
                        session.snapshot(), 0L, UUID.randomUUID(), STARTED_AT.plusSeconds(1L)));
        session.startWave(1L);
        assertEquals(
                OperationOutcome.APPLIED,
                defense.saveTransition(
                        session.snapshot(), 1L, UUID.randomUUID(), STARTED_AT.plusSeconds(2L)));

        UUID logicalEnemyId = UUID.randomUUID();
        UUID firstOperation = UUID.randomUUID();
        TowerDamageMutationResult first = defense.damageTowerByEnemy(
                eventId,
                teamId,
                logicalEnemyId,
                tower.id(),
                40L,
                firstOperation,
                STARTED_AT.plusSeconds(3L));
        assertEquals(OperationOutcome.APPLIED, first.outcome());
        assertEquals(60L, first.remainingHitPoints());
        assertTrue(!first.destroyed());
        assertEquals(60L, towers.findTower(tower.id()).orElseThrow().currentHitPoints());
        TowerDamageMutationResult firstRetry = defense.damageTowerByEnemy(
                eventId,
                teamId,
                logicalEnemyId,
                tower.id(),
                40L,
                firstOperation,
                STARTED_AT.plusSeconds(4L));
        assertEquals(OperationOutcome.ALREADY_APPLIED, firstRetry.outcome());
        assertEquals(first.remainingHitPoints(), firstRetry.remainingHitPoints());
        assertEquals(first.destroyed(), firstRetry.destroyed());

        UUID destroyOperation = UUID.randomUUID();
        TowerDamageMutationResult destroyed = defense.damageTowerByEnemy(
                eventId,
                teamId,
                logicalEnemyId,
                tower.id(),
                60L,
                destroyOperation,
                STARTED_AT.plusSeconds(5L));
        assertEquals(OperationOutcome.APPLIED, destroyed.outcome());
        assertEquals(0L, destroyed.remainingHitPoints());
        assertTrue(destroyed.destroyed());
        assertTrue(towers.findTower(tower.id()).isEmpty());
        TowerDamageMutationResult destroyedRetry = defense.damageTowerByEnemy(
                eventId,
                teamId,
                logicalEnemyId,
                tower.id(),
                60L,
                destroyOperation,
                STARTED_AT.plusSeconds(6L));
        assertEquals(OperationOutcome.ALREADY_APPLIED, destroyedRetry.outcome());
        assertEquals(destroyed.remainingHitPoints(), destroyedRetry.remainingHitPoints());
        assertEquals(destroyed.destroyed(), destroyedRetry.destroyed());
        assertThrows(
                PersistenceConflictException.class,
                () -> defense.damageTowerByEnemy(
                        eventId,
                        teamId,
                        logicalEnemyId,
                        tower.id(),
                        59L,
                        destroyOperation,
                        STARTED_AT.plusSeconds(7L)));
    }

    private static TowerRecord installTower(
            TowerRepository towers,
            UUID teamId,
            UUID ownerId,
            UUID worldId) {
        TowerPlacement placement = TowerPlacement.prepared(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ownerId,
                teamId,
                worldId,
                1,
                64,
                0,
                TowerType.ARROW,
                1,
                STARTED_AT);
        towers.prepareTowerPlacement(placement, TowerSettings.defaults());
        return towers.applyTowerPlacement(
                placement.operationId(),
                UUID.randomUUID(),
                TowerSettings.defaults(),
                STARTED_AT.plusSeconds(1L));
    }
}
