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
}
