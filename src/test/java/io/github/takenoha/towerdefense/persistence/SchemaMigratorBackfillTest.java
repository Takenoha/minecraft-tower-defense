package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TeamProgress;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigratorBackfillTest {
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void realV26TeamAndResearchDataSurviveTheV27ToCurrentBackfill() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("v26-backfill.sqlite");
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        createV26Database(databaseFile, teamId, ownerId);

        Database database = new Database(databaseFile);
        DefenseRepository repository = new DefenseRepository(database);
        TeamRecord team = repository.findTeam(teamId).orElseThrow();
        TeamProgress progress = repository.loadTeamProgress(teamId);

        assertEquals("旧チーム", team.displayName());
        assertEquals(4, progress.highestClearedLevel());
        assertEquals(7, progress.researchPoints());
        assertEquals(
                0L,
                new ResourceRepository(database).load(teamId, ownerId).defensePoints());

        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT MAX(version) FROM schema_migrations");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            assertEquals(SchemaMigrator.CURRENT_VERSION, resultSet.getInt(1));
        }
    }

    @Test
    void v38TacticalUnlockRowsAreCopiedIntoTheV39NodeUnlockTable() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("v38-tactical-backfill.sqlite");
        UUID teamId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID startOperationId = UUID.randomUUID();
        UUID unlockOperationId = UUID.randomUUID();
        createV38TacticalDatabase(
                databaseFile,
                teamId,
                sessionId,
                startOperationId,
                unlockOperationId);

        Database database = new Database(databaseFile);
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT node_id FROM tactical_build_node_unlocks
                        WHERE tactical_session_id = ?
                        """)) {
            statement.setString(1, sessionId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("legacy-tier-1", resultSet.getString("node_id"));
            }
        }
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT MAX(version) FROM schema_migrations");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            assertEquals(SchemaMigrator.CURRENT_VERSION, resultSet.getInt(1));
        }
    }

    private static void createV26Database(
            Path databaseFile,
            UUID teamId,
            UUID ownerId) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("BEGIN IMMEDIATE");
                statement.execute("""
                        CREATE TABLE schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at TEXT NOT NULL
                        )
                        """);
            }
            for (int version = 1; version <= 26; version++) {
                invokeMigration(connection, version);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                    statement.setInt(1, version);
                    statement.setString(2, CREATED_AT.toString());
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO teams(team_id, owner_player_id, created_at, display_name)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setString(1, teamId.toString());
                statement.setString(2, ownerId.toString());
                statement.setString(3, CREATED_AT.toString());
                statement.setString(4, "旧チーム");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO team_members(team_id, player_id, role, joined_at)
                    VALUES (?, ?, 'OWNER', ?)
                    """)) {
                statement.setString(1, teamId.toString());
                statement.setString(2, ownerId.toString());
                statement.setString(3, CREATED_AT.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO team_progress(
                        team_id, highest_cleared_level, unlocked_level, research_points, updated_at)
                    VALUES (?, 4, 5, 7, ?)
                    """)) {
                statement.setString(1, teamId.toString());
                statement.setString(2, CREATED_AT.toString());
                statement.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
        }
    }

    private static void createV38TacticalDatabase(
            Path databaseFile,
            UUID teamId,
            UUID sessionId,
            UUID startOperationId,
            UUID unlockOperationId) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("BEGIN IMMEDIATE");
                statement.execute("""
                        CREATE TABLE schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at TEXT NOT NULL
                        )
                        """);
            }
            for (int version = 1; version <= 38; version++) {
                invokeMigration(connection, version);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                    statement.setInt(1, version);
                    statement.setString(2, CREATED_AT.toString());
                    statement.executeUpdate();
                }
            }
            UUID ownerId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO teams(team_id, owner_player_id, created_at, display_name)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setString(1, teamId.toString());
                statement.setString(2, ownerId.toString());
                statement.setString(3, CREATED_AT.toString());
                statement.setString(4, "v38 tactical team");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tactical_build_sessions(
                        tactical_session_id, start_operation_id, team_id, stage, seed,
                        generator_version, state, highest_unlocked_tier, created_at, updated_at)
                    VALUES (?, ?, ?, 1, 42, 1, 'ACTIVE', 1, ?, ?)
                    """)) {
                statement.setString(1, sessionId.toString());
                statement.setString(2, startOperationId.toString());
                statement.setString(3, teamId.toString());
                statement.setString(4, CREATED_AT.toString());
                statement.setString(5, CREATED_AT.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tactical_build_operations(
                        operation_id, tactical_session_id, operation_kind,
                        payload_fingerprint, applied_at)
                    VALUES (?, ?, 'UNLOCK', 'legacy', ?)
                    """)) {
                statement.setString(1, unlockOperationId.toString());
                statement.setString(2, sessionId.toString());
                statement.setString(3, CREATED_AT.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tactical_build_unlocked_nodes(
                        tactical_session_id, tier, node_id, operation_id, unlocked_at)
                    VALUES (?, 1, 'legacy-tier-1', ?, ?)
                    """)) {
                statement.setString(1, sessionId.toString());
                statement.setString(2, unlockOperationId.toString());
                statement.setString(3, CREATED_AT.toString());
                statement.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
        }
    }

    private static void invokeMigration(Connection connection, int version) throws Exception {
        String suffix = switch (version) {
            case 1 -> "One";
            case 2 -> "Two";
            case 3 -> "Three";
            case 4 -> "Four";
            case 5 -> "Five";
            case 6 -> "Six";
            case 7 -> "Seven";
            case 8 -> "Eight";
            case 9 -> "Nine";
            case 10 -> "Ten";
            case 11 -> "Eleven";
            case 12 -> "Twelve";
            case 13 -> "Thirteen";
            case 14 -> "Fourteen";
            case 15 -> "Fifteen";
            case 16 -> "Sixteen";
            case 17 -> "Seventeen";
            case 18 -> "Eighteen";
            case 19 -> "Nineteen";
            case 20 -> "Twenty";
            case 21 -> "TwentyOne";
            case 22 -> "TwentyTwo";
            case 23 -> "TwentyThree";
            case 24 -> "TwentyFour";
            case 25 -> "TwentyFive";
            case 26 -> "TwentySix";
            case 27 -> "TwentySeven";
            case 28 -> "TwentyEight";
            case 29 -> "TwentyNine";
            case 30 -> "Thirty";
            case 31 -> "ThirtyOne";
            case 32 -> "ThirtyTwo";
            case 33 -> "ThirtyThree";
            case 34 -> "ThirtyFour";
            case 35 -> "ThirtyFive";
            case 36 -> "ThirtySix";
            case 37 -> "ThirtySeven";
            case 38 -> "ThirtyEight";
            default -> throw new IllegalArgumentException("unsupported fixture version");
        };
        Method method = SchemaMigrator.class.getDeclaredMethod(
                "applyVersion" + suffix,
                Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(null, connection);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }
}
