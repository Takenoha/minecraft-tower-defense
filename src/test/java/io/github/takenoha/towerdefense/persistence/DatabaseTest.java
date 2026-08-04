package io.github.takenoha.towerdefense.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesMigrationsAndConnectionPragmasOnEveryOpen() throws SQLException {
        Path databaseFile = temporaryDirectory.resolve("defense.sqlite");
        Database database = new Database(databaseFile);

        assertConnectionConfiguration(database);
        assertConnectionConfiguration(database);

        Database reopened = new Database(databaseFile);
        assertConnectionConfiguration(reopened);
        try (Connection connection = reopened.openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*), MAX(version) FROM schema_migrations")) {
            resultSet.next();
            assertEquals(SchemaMigrator.CURRENT_VERSION, resultSet.getInt(1));
            assertEquals(SchemaMigrator.CURRENT_VERSION, resultSet.getInt(2));
        }
        assertTrue(columnExists(reopened, "defense_events", "revision"));
        assertTrue(columnExists(reopened, "event_operations", "target_revision"));
        assertTrue(columnExists(reopened, "event_operations", "payload_fingerprint"));
        assertTrue(columnExists(
                reopened, "event_mutation_operations", "rollback_decision"));
        assertTrue(columnExists(reopened, "event_block_changes", "before_tile_nbt"));
        assertTrue(columnExists(reopened, "event_block_changes", "expected_after_tile_nbt"));
        assertTrue(columnExists(reopened, "event_reward_queue", "team_claim_deadline"));
        assertTrue(tableExists(reopened, "management_operations"));
        assertTrue(tableExists(reopened, "tower_removal_operations"));
        assertTrue(columnExists(reopened, "towers", "target_priority"));
        assertTrue(columnExists(
                reopened, "tower_placement_operations", "target_priority"));
    }

    private static void assertConnectionConfiguration(Database database) throws SQLException {
        try (Connection connection = database.openConnection()) {
            assertEquals("wal", textPragma(connection, "journal_mode"));
            assertEquals(1, integerPragma(connection, "foreign_keys"));
            assertEquals(
                    Database.DEFAULT_BUSY_TIMEOUT_MILLIS,
                    integerPragma(connection, "busy_timeout"));
        }
    }

    private static String textPragma(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static int integerPragma(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static boolean columnExists(Database database, String table, String column)
            throws SQLException {
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equals(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean tableExists(Database database, String table) throws SQLException {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
