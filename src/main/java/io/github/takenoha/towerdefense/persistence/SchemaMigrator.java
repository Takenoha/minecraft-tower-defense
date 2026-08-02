package io.github.takenoha.towerdefense.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** Applies ordered, in-process SQLite schema migrations. */
public final class SchemaMigrator {
    public static final int CURRENT_VERSION = 2;

    private SchemaMigrator() {
    }

    static void migrate(Database database) {
        try {
            database.inImmediateTransaction(connection -> {
                createMigrationTable(connection);
                int installedVersion = installedVersion(connection);
                if (installedVersion > CURRENT_VERSION) {
                    throw new SQLException(
                            "Database schema version " + installedVersion
                                    + " is newer than supported version " + CURRENT_VERSION);
                }
                if (installedVersion < 1) {
                    applyVersionOne(connection);
                    recordMigration(connection, 1);
                }
                if (installedVersion < 2) {
                    applyVersionTwo(connection);
                    recordMigration(connection, 2);
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new PersistenceException("Could not migrate the SQLite schema", exception);
        }
    }

    private static void createMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static int installedVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static void recordMigration(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
            statement.setInt(1, version);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static void applyVersionOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE teams (
                        team_id TEXT PRIMARY KEY,
                        owner_player_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE team_members (
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        player_id TEXT NOT NULL UNIQUE,
                        role TEXT NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
                        joined_at TEXT NOT NULL,
                        PRIMARY KEY (team_id, player_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE cores (
                        core_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL UNIQUE REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        current_hp INTEGER NOT NULL CHECK (current_hp >= 0),
                        max_hp INTEGER NOT NULL CHECK (max_hp > 0 AND current_hp <= max_hp),
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (world_id, block_x, block_y, block_z)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX cores_world_position_idx
                    ON cores(world_id, block_x, block_z)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE defense_events (
                        event_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        core_id TEXT NOT NULL REFERENCES cores(core_id) ON DELETE RESTRICT,
                        state TEXT NOT NULL CHECK (state IN (
                            'IDLE', 'COUNTDOWN', 'PREPARATION', 'WAVE_ACTIVE',
                            'INTERMISSION', 'VICTORY', 'DEFEAT', 'ABORTED', 'RECOVERY'
                        )),
                        stage_level INTEGER NOT NULL CHECK (stage_level > 0),
                        total_waves INTEGER NOT NULL CHECK (total_waves > 0),
                        participant_limit INTEGER NOT NULL CHECK (participant_limit > 0),
                        participants_frozen INTEGER NOT NULL CHECK (participants_frozen IN (0, 1)),
                        wave_index INTEGER NOT NULL CHECK (wave_index >= 0),
                        pending_enemies INTEGER NOT NULL CHECK (pending_enemies >= 0),
                        alive_enemies INTEGER NOT NULL CHECK (alive_enemies >= 0),
                        start_core_hp INTEGER NOT NULL CHECK (start_core_hp > 0),
                        start_core_max_hp INTEGER NOT NULL CHECK (
                            start_core_max_hp > 0 AND start_core_hp <= start_core_max_hp
                        ),
                        core_hp INTEGER NOT NULL CHECK (core_hp >= 0),
                        core_max_hp INTEGER NOT NULL CHECK (
                            core_max_hp > 0 AND core_hp <= core_max_hp
                        ),
                        core_present INTEGER NOT NULL CHECK (core_present IN (0, 1)),
                        core_world_id TEXT NOT NULL,
                        core_block_x INTEGER NOT NULL,
                        core_block_y INTEGER NOT NULL,
                        core_block_z INTEGER NOT NULL,
                        config_snapshot TEXT NOT NULL,
                        config_version INTEGER NOT NULL CHECK (config_version > 0),
                        started_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        terminal_at TEXT,
                        terminal_operation_id TEXT UNIQUE,
                        CHECK (core_present = (core_hp > 0)),
                        CHECK ((terminal_at IS NULL) = (terminal_operation_id IS NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX defense_events_state_idx ON defense_events(state)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_lock (
                        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                        event_id TEXT NOT NULL UNIQUE REFERENCES defense_events(event_id) ON DELETE RESTRICT,
                        acquired_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_participants (
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        player_id TEXT NOT NULL,
                        registered INTEGER NOT NULL CHECK (registered IN (0, 1)),
                        effective INTEGER NOT NULL CHECK (effective IN (0, 1)),
                        joined_at TEXT NOT NULL,
                        PRIMARY KEY (event_id, player_id),
                        CHECK (effective >= registered)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_enemies (
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        enemy_id TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        enemy_type TEXT NOT NULL,
                        wave_index INTEGER NOT NULL CHECK (wave_index > 0),
                        status TEXT NOT NULL CHECK (status IN (
                            'ALLOCATED', 'SPAWNED', 'DEAD', 'DESPAWNED', 'RECOVERY_REMOVED'
                        )),
                        snapshot TEXT NOT NULL,
                        snapshot_version INTEGER NOT NULL CHECK (snapshot_version > 0),
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (event_id, enemy_id),
                        UNIQUE (event_id, entity_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_enemies_status_idx
                    ON event_enemies(event_id, status)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_transitions (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        operation_id TEXT NOT NULL UNIQUE
                            REFERENCES event_operations(operation_id) ON DELETE RESTRICT,
                        from_state TEXT NOT NULL,
                        to_state TEXT NOT NULL,
                        wave_index INTEGER NOT NULL CHECK (wave_index >= 0),
                        pending_enemies INTEGER NOT NULL CHECK (pending_enemies >= 0),
                        alive_enemies INTEGER NOT NULL CHECK (alive_enemies >= 0),
                        occurred_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_transitions_event_idx
                    ON event_transitions(event_id, sequence)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_operations (
                        operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        operation_kind TEXT NOT NULL CHECK (operation_kind IN (
                            'TRANSITION', 'TERMINATE', 'RECOVER'
                        )),
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static void applyVersionTwo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE defense_events
                    ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0)
                    """);
            statement.executeUpdate("""
                    ALTER TABLE event_operations
                    ADD COLUMN target_revision INTEGER NOT NULL DEFAULT 0
                        CHECK (target_revision >= 0)
                    """);
            statement.executeUpdate("""
                    ALTER TABLE event_operations
                    ADD COLUMN payload_fingerprint TEXT NOT NULL DEFAULT ''
                    """);
        }
    }
}
