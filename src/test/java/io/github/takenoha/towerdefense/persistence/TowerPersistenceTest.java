package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TowerPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void preparedPlacementIsIdempotentlyAppliedAndReloadable() {
        Database database = new Database(temporaryDirectory.resolve("tower.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);

        TowerPlacement placement = TowerPlacement.prepared(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ownerId,
                teamId,
                UUID.randomUUID(),
                10,
                65,
                -4,
                TowerType.ARROW,
                1,
                NOW);
        TowerPlacement prepared = towers.prepareTowerPlacement(
                placement, TowerSettings.defaults());
        assertEquals(placement, prepared);
        assertEquals(placement, towers.prepareTowerPlacement(
                placement, TowerSettings.defaults()));

        UUID entityId = UUID.randomUUID();
        TowerRecord applied = towers.applyTowerPlacement(
                placement.operationId(), entityId, TowerSettings.defaults(), NOW.plusSeconds(1L));
        assertEquals(applied, towers.applyTowerPlacement(
                placement.operationId(), entityId, TowerSettings.defaults(), NOW.plusSeconds(2L)));
        assertEquals(applied, towers.findTower(placement.towerId()).orElseThrow());
        assertEquals(java.util.List.of(applied), towers.loadAllTowers());
        assertTrue(towers.loadPendingTowerPlacements().isEmpty());
    }

    @Test
    void rollbackLeavesNoInstalledTower() {
        Database database = new Database(temporaryDirectory.resolve("rollback.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        TowerPlacement placement = TowerPlacement.prepared(
                UUID.randomUUID(), UUID.randomUUID(), ownerId, teamId, UUID.randomUUID(),
                1, 65, 1, TowerType.ARROW, NOW);

        towers.prepareTowerPlacement(placement, TowerSettings.defaults());
        TowerPlacement rolledBack = towers.rollbackTowerPlacement(
                placement.operationId(), NOW.plusSeconds(1L)).orElseThrow();

        assertEquals(TowerPlacementState.ROLLED_BACK, rolledBack.state());
        assertTrue(towers.loadAllTowers().isEmpty());
    }

    @Test
    void preparedRemovalIsIdempotentlyAppliedAndReloadable() {
        Database database = new Database(temporaryDirectory.resolve("removal.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);

        TowerRecord installed = installTower(towers, teamId, ownerId, NOW);
        TowerRemoval removal = TowerRemoval.prepared(
                UUID.randomUUID(), installed, ownerId, NOW.plusSeconds(2L));

        assertEquals(removal, towers.prepareTowerRemoval(removal));
        assertEquals(removal, towers.prepareTowerRemoval(removal));

        TowerRemoval applied = towers.applyTowerRemoval(
                removal.operationId(), NOW.plusSeconds(3L));
        assertEquals(applied, towers.applyTowerRemoval(
                removal.operationId(), NOW.plusSeconds(4L)));
        assertTrue(towers.findTower(installed.id()).isEmpty());
        assertTrue(towers.loadPendingTowerRemovals().isEmpty());
        assertEquals(java.util.List.of(applied), towers.loadAppliedTowerRemovals());
    }

    @Test
    void rolledBackRemovalKeepsInstalledTower() {
        Database database = new Database(temporaryDirectory.resolve("removal-rollback.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);

        TowerRecord installed = installTower(towers, teamId, ownerId, NOW);
        TowerRemoval removal = TowerRemoval.prepared(
                UUID.randomUUID(), installed, ownerId, NOW.plusSeconds(2L));
        towers.prepareTowerRemoval(removal);

        TowerRemoval rolledBack = towers.rollbackTowerRemoval(
                removal.operationId(), NOW.plusSeconds(3L)).orElseThrow();

        assertEquals(TowerRemovalState.ROLLED_BACK, rolledBack.state());
        assertEquals(installed, towers.findTower(installed.id()).orElseThrow());
        assertTrue(towers.loadPendingTowerRemovals().isEmpty());
        assertTrue(towers.loadAppliedTowerRemovals().isEmpty());
    }

    private static TowerRecord installTower(
            TowerRepository towers,
            UUID teamId,
            UUID ownerId,
            Instant preparedAt) {
        TowerPlacement placement = TowerPlacement.prepared(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ownerId,
                teamId,
                UUID.randomUUID(),
                10,
                65,
                -4,
                TowerType.ARROW,
                1,
                preparedAt);
        towers.prepareTowerPlacement(placement, TowerSettings.defaults());
        return towers.applyTowerPlacement(
                placement.operationId(),
                UUID.randomUUID(),
                TowerSettings.defaults(),
                preparedAt.plusSeconds(1L));
    }
}
