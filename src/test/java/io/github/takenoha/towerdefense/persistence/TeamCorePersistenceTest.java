package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TeamCorePersistenceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void soloOwnerAndCoreRegistrySurviveReopen() {
        Path databaseFile = temporaryDirectory.resolve("reopen.sqlite");
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(), teamId, worldId, 12, 70, -25, 80L, 100L, NOW, NOW);

        DefenseRepository initial = new DefenseRepository(new Database(databaseFile));
        TeamRecord team = initial.createSoloTeam(teamId, ownerId, NOW);
        initial.placeCore(core, 192.0D);

        assertEquals(ownerId, team.ownerId());
        assertEquals(java.util.Set.of(ownerId), team.members());

        DefenseRepository reopened = new DefenseRepository(new Database(databaseFile));
        assertEquals(team, reopened.findTeamByOwner(ownerId).orElseThrow());
        assertEquals(core, reopened.findCoreByTeam(teamId).orElseThrow());
        assertEquals(java.util.List.of(core), reopened.loadAllCores());
    }

    @Test
    void enforcesOneCorePerTeamAndMinimumSameWorldDistance() {
        DefenseRepository repository = new DefenseRepository(
                new Database(temporaryDirectory.resolve("constraints.sqlite")));
        UUID worldId = UUID.randomUUID();
        UUID firstTeam = createTeam(repository);
        UUID secondTeam = createTeam(repository);
        UUID thirdTeam = createTeam(repository);

        CoreRecord first = core(firstTeam, worldId, 0, 0);
        repository.placeCore(first, 192.0D);

        assertThrows(
                PersistenceConflictException.class,
                () -> repository.placeCore(core(firstTeam, worldId, 500, 0), 192.0D));
        assertThrows(
                PersistenceConflictException.class,
                () -> repository.placeCore(core(secondTeam, worldId, 191, 0), 192.0D));

        CoreRecord boundary = core(secondTeam, worldId, 192, 0);
        repository.placeCore(boundary, 192.0D);
        CoreRecord otherWorld = core(thirdTeam, UUID.randomUUID(), 0, 0);
        repository.placeCore(otherWorld, 192.0D);

        assertTrue(repository.findDistanceConflict(worldId, 191, 0, 192.0D).isPresent());
        assertEquals(3, repository.loadAllCores().size());
    }

    @Test
    void databaseUniqueConstraintRejectsSecondCoreForTeam() throws SQLException {
        Database database = new Database(temporaryDirectory.resolve("raw-unique.sqlite"));
        DefenseRepository repository = new DefenseRepository(database);
        UUID teamId = createTeam(repository);
        CoreRecord first = core(teamId, UUID.randomUUID(), 0, 0);
        repository.placeCore(first, 192.0D);

        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO cores(
                            core_id, team_id, world_id, block_x, block_y, block_z,
                            current_hp, max_hp, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, UUID.randomUUID().toString());
            statement.setInt(4, 1_000);
            statement.setInt(5, 64);
            statement.setInt(6, 1_000);
            statement.setLong(7, 100L);
            statement.setLong(8, 100L);
            statement.setString(9, NOW.toString());
            statement.setString(10, NOW.toString());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private static UUID createTeam(DefenseRepository repository) {
        UUID teamId = UUID.randomUUID();
        repository.createSoloTeam(teamId, UUID.randomUUID(), NOW);
        return teamId;
    }

    private static CoreRecord core(UUID teamId, UUID worldId, int x, int z) {
        return new CoreRecord(
                UUID.randomUUID(), teamId, worldId, x, 64, z, 100L, 100L, NOW, NOW);
    }
}
