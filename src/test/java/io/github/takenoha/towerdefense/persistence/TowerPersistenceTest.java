package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerResearch;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

    @Test
    void targetPrioritySurvivesPlacementUpdateAndReload() {
        Path databaseFile = temporaryDirectory.resolve("priority.sqlite");
        Database database = new Database(databaseFile);
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
                5,
                65,
                5,
                TowerType.CANNON,
                1,
                TowerTargetPriority.BOSS,
                NOW);
        towers.prepareTowerPlacement(placement, TowerSettings.defaults());
        TowerRecord installed = towers.applyTowerPlacement(
                placement.operationId(),
                UUID.randomUUID(),
                TowerSettings.defaults(),
                NOW.plusSeconds(1L));
        assertEquals(TowerType.CANNON, installed.type());
        assertEquals(TowerTargetPriority.BOSS, installed.targetPriority());

        TowerRecord updated = towers.updateTargetPriority(
                installed.id(),
                ownerId,
                TowerTargetPriority.HEALTH_LOW,
                NOW.plusSeconds(2L));
        assertEquals(TowerTargetPriority.HEALTH_LOW, updated.targetPriority());
        assertEquals(
                TowerTargetPriority.HEALTH_LOW,
                new TowerRepository(new Database(databaseFile))
                        .findTower(installed.id())
                        .orElseThrow()
                        .targetPriority());
    }

    @Test
    void targetPriorityUpdateRequiresTeamMembership() {
        Database database = new Database(temporaryDirectory.resolve("priority-auth.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        TowerRecord installed = installTower(towers, teamId, ownerId, NOW);

        assertThrows(
                PersistenceConflictException.class,
                () -> towers.updateTargetPriority(
                        installed.id(),
                        UUID.randomUUID(),
                        TowerTargetPriority.BOSS,
                        NOW.plusSeconds(2L)));
    }

    @Test
    void researchPurchasePersistsCapAndIsIdempotent() throws SQLException {
        Database database = new Database(temporaryDirectory.resolve("research.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);

        assertEquals(
                java.util.List.of(
                        TowerResearch.initial(teamId, TowerType.ARROW, NOW),
                        TowerResearch.initial(teamId, TowerType.CANNON, NOW),
                        TowerResearch.initial(teamId, TowerType.FLAME, NOW),
                        TowerResearch.initial(teamId, TowerType.FROST, NOW),
                        TowerResearch.initial(teamId, TowerType.LIGHTNING, NOW),
                        TowerResearch.initial(teamId, TowerType.SNIPER, NOW),
                        TowerResearch.initial(teamId, TowerType.SUPPORT, NOW)),
                towers.loadTowerResearch(teamId));
        addResearchPoints(database, teamId, 12L);
        UUID operationId = UUID.randomUUID();

        TowerResearchMutationResult applied = towers.purchaseTowerResearch(
                teamId,
                ownerId,
                TowerType.ARROW,
                10L,
                operationId,
                NOW.plusSeconds(1L));
        assertEquals(OperationOutcome.APPLIED, applied.outcome());
        assertEquals(2, applied.research().researchLevel());
        assertEquals(2L, applied.progress().researchPoints());
        TowerResearchMutationResult retried = towers.purchaseTowerResearch(
                teamId,
                ownerId,
                TowerType.ARROW,
                10L,
                operationId,
                NOW.plusSeconds(2L));
        assertEquals(OperationOutcome.ALREADY_APPLIED, retried.outcome());
        assertEquals(applied.progress(), retried.progress());
        assertEquals(applied.research(), retried.research());
        assertThrows(
                PersistenceConflictException.class,
                () -> towers.purchaseTowerResearch(
                        teamId,
                        ownerId,
                        TowerType.ARROW,
                        9L,
                        operationId,
                        NOW.plusSeconds(3L)));
    }

    @Test
    void individualUpgradeUsesResearchCapAndTwoPhaseIdempotency() throws SQLException {
        Database database = new Database(temporaryDirectory.resolve("upgrade.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        setTowerResearchLevel(database, teamId, TowerType.ARROW, 2);
        TowerRecord installed = installTower(towers, teamId, ownerId, NOW);

        TowerUpgrade request = TowerUpgrade.prepared(
                UUID.randomUUID(), installed, ownerId, 2, 1, NOW.plusSeconds(2L));
        TowerUpgrade prepared = towers.prepareTowerUpgrade(request);
        assertEquals(TowerUpgradeState.PREPARED, prepared.state());
        assertEquals(prepared, towers.prepareTowerUpgrade(request));

        TowerUpgradeResult applied = towers.applyTowerUpgrade(
                prepared.operationId(), NOW.plusSeconds(3L));
        assertEquals(OperationOutcome.APPLIED, applied.outcome());
        assertEquals(2, applied.tower().orElseThrow().individualLevel());
        TowerUpgradeResult replay = towers.applyTowerUpgrade(
                prepared.operationId(), NOW.plusSeconds(4L));
        assertEquals(OperationOutcome.ALREADY_APPLIED, replay.outcome());
        assertEquals(2, replay.tower().orElseThrow().individualLevel());
        assertThrows(
                PersistenceConflictException.class,
                () -> towers.prepareTowerUpgrade(
                        TowerUpgrade.prepared(
                                prepared.operationId(),
                                installed,
                                ownerId,
                                3,
                                1,
                                NOW.plusSeconds(5L))));
    }

    @Test
    void preparedUpgradeCanBeRolledBackWithoutChangingTheTower() throws SQLException {
        Database database = new Database(temporaryDirectory.resolve("upgrade-rollback.sqlite"));
        DefenseRepository teams = new DefenseRepository(database);
        TowerRepository towers = new TowerRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        teams.createSoloTeam(teamId, ownerId, NOW);
        setTowerResearchLevel(database, teamId, TowerType.ARROW, 2);
        TowerRecord installed = installTower(towers, teamId, ownerId, NOW);
        TowerUpgrade prepared = towers.prepareTowerUpgrade(
                TowerUpgrade.prepared(
                        UUID.randomUUID(), installed, ownerId, 2, 1, NOW.plusSeconds(2L)));

        TowerUpgrade rolledBack = towers.rollbackTowerUpgrade(
                prepared.operationId(), NOW.plusSeconds(3L)).orElseThrow();
        assertEquals(TowerUpgradeState.ROLLED_BACK, rolledBack.state());
        assertEquals(1, towers.findTower(installed.id()).orElseThrow().individualLevel());
        assertTrue(towers.rollbackTowerUpgrade(
                prepared.operationId(), NOW.plusSeconds(4L)).orElseThrow().state()
                == TowerUpgradeState.ROLLED_BACK);
    }

    @Test
    void placementCannotExceedTheTeamResearchCap() throws SQLException {
        Database database = new Database(temporaryDirectory.resolve("research-gate.sqlite"));
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
                2,
                NOW);

        assertThrows(
                PersistenceConflictException.class,
                () -> towers.prepareTowerPlacement(placement, TowerSettings.defaults()));
        setTowerResearchLevel(database, teamId, TowerType.ARROW, 2);
        assertEquals(placement, towers.prepareTowerPlacement(placement, TowerSettings.defaults()));
        setTowerResearchLevel(database, teamId, TowerType.ARROW, 1);
        assertThrows(
                PersistenceConflictException.class,
                () -> towers.applyTowerPlacement(
                        placement.operationId(),
                        UUID.randomUUID(),
                        TowerSettings.defaults(),
                        NOW.plusSeconds(1L)));
        setTowerResearchLevel(database, teamId, TowerType.ARROW, 2);
        assertEquals(
                TowerType.ARROW,
                towers.applyTowerPlacement(
                                placement.operationId(),
                                UUID.randomUUID(),
                                TowerSettings.defaults(),
                                NOW.plusSeconds(2L))
                        .type());
    }

    private static void addResearchPoints(Database database, UUID teamId, long points)
            throws SQLException {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE team_progress SET research_points = ? WHERE team_id = ?
                        """)) {
            statement.setLong(1, points);
            statement.setString(2, teamId.toString());
            statement.executeUpdate();
        }
    }

    private static void setTowerResearchLevel(
            Database database, UUID teamId, TowerType towerType, int level) throws SQLException {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tower_research
                        SET research_level = ?
                        WHERE team_id = ? AND tower_type = ?
                        """)) {
            statement.setInt(1, level);
            statement.setString(2, teamId.toString());
            statement.setString(3, towerType.id());
            statement.executeUpdate();
        }
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
