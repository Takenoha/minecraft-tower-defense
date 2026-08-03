package io.github.takenoha.towerdefense.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** Applies ordered, in-process SQLite schema migrations. */
public final class SchemaMigrator {
    public static final int CURRENT_VERSION = 5;

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
                if (installedVersion < 3) {
                    applyVersionThree(connection);
                    recordMigration(connection, 3);
                }
                if (installedVersion < 4) {
                    applyVersionFour(connection);
                    recordMigration(connection, 4);
                }
                if (installedVersion < 5) {
                    applyVersionFive(connection);
                    recordMigration(connection, 5);
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

    private static void applyVersionThree(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE event_mutation_operations (
                        operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        operation_kind TEXT NOT NULL CHECK (operation_kind IN (
                            'BLOCK_APPLY', 'BLOCK_ROLLBACK', 'DROP_CLAIM', 'DROP_SETTLE',
                            'DROP_VOID', 'REWARD_ISSUE'
                        )),
                        target_id TEXT NOT NULL,
                        payload_fingerprint TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (state IN ('PREPARED', 'APPLIED')),
                        prepared_at TEXT NOT NULL,
                        applied_at TEXT,
                        UNIQUE (event_id, operation_kind, target_id),
                        CHECK ((state = 'PREPARED' AND applied_at IS NULL)
                               OR (state = 'APPLIED' AND applied_at IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_mutation_operations_event_idx
                    ON event_mutation_operations(event_id, operation_kind, state)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_block_changes (
                        change_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        change_kind TEXT NOT NULL CHECK (
                            change_kind IN ('EVENT_BLOCK', 'TEMPORARY_BLOCK')
                        ),
                        generation INTEGER NOT NULL CHECK (generation > 0),
                        before_block_data TEXT NOT NULL,
                        before_block_state TEXT NOT NULL,
                        expected_after_block_data TEXT NOT NULL,
                        expected_after_block_state TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (
                            status IN ('PREPARED', 'APPLIED', 'ROLLED_BACK', 'CONFLICT')
                        ),
                        prepare_operation_id TEXT NOT NULL UNIQUE,
                        apply_operation_id TEXT UNIQUE,
                        rollback_operation_id TEXT UNIQUE,
                        prepared_at TEXT NOT NULL,
                        applied_at TEXT,
                        resolved_at TEXT,
                        UNIQUE (event_id, world_id, block_x, block_y, block_z, generation),
                        CHECK ((status = 'PREPARED' AND applied_at IS NULL)
                               OR (status <> 'PREPARED' AND applied_at IS NOT NULL)),
                        CHECK ((status IN ('ROLLED_BACK', 'CONFLICT') AND resolved_at IS NOT NULL)
                               OR (status IN ('PREPARED', 'APPLIED') AND resolved_at IS NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_block_changes_recovery_idx
                    ON event_block_changes(event_id, status, generation DESC)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_drop_escrow (
                        drop_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        source_kind TEXT NOT NULL CHECK (source_kind IN ('ENEMY', 'BLOCK')),
                        source_id TEXT NOT NULL,
                        item_id TEXT NOT NULL,
                        item_payload TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        claimed_quantity INTEGER NOT NULL DEFAULT 0 CHECK (
                            claimed_quantity >= 0 AND claimed_quantity <= quantity
                        ),
                        status TEXT NOT NULL CHECK (status IN ('HELD', 'SETTLED', 'VOIDED')),
                        display_entity_id TEXT,
                        create_operation_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (event_id, drop_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_drop_escrow_event_idx
                    ON event_drop_escrow(event_id, status)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_drop_claims (
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        drop_id TEXT NOT NULL REFERENCES event_drop_escrow(drop_id) ON DELETE CASCADE,
                        recipient_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        operation_id TEXT NOT NULL UNIQUE,
                        claimed_at TEXT NOT NULL,
                        PRIMARY KEY (event_id, drop_id, recipient_id),
                        FOREIGN KEY (event_id, recipient_id)
                            REFERENCES event_participants(event_id, player_id)
                            ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_reward_queue (
                        queue_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        scope TEXT NOT NULL CHECK (scope IN ('PLAYER', 'TEAM')),
                        recipient_id TEXT NOT NULL,
                        item_id TEXT NOT NULL,
                        item_payload TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        source_drop_id TEXT NOT NULL REFERENCES event_drop_escrow(drop_id)
                            ON DELETE RESTRICT,
                        status TEXT NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'VOIDED')),
                        issued_operation_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (event_id, source_drop_id, scope, recipient_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_reward_queue_recipient_idx
                    ON event_reward_queue(scope, recipient_id, status)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE raid_seals (
                        seal_id TEXT PRIMARY KEY,
                        owner_player_id TEXT NOT NULL,
                        stage_level INTEGER NOT NULL CHECK (stage_level > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('AVAILABLE', 'RESERVED', 'CONSUMED', 'REFUNDED')
                        ),
                        event_id TEXT REFERENCES defense_events(event_id) ON DELETE RESTRICT,
                        reservation_operation_id TEXT UNIQUE,
                        consumption_operation_id TEXT UNIQUE,
                        refund_operation_id TEXT UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        CHECK ((state = 'AVAILABLE' AND event_id IS NULL
                                AND reservation_operation_id IS NULL
                                AND consumption_operation_id IS NULL)
                               OR (state = 'RESERVED' AND event_id IS NOT NULL
                                   AND reservation_operation_id IS NOT NULL
                                   AND consumption_operation_id IS NULL)
                               OR (state = 'CONSUMED' AND event_id IS NOT NULL
                                   AND reservation_operation_id IS NOT NULL
                                   AND consumption_operation_id IS NOT NULL)
                               OR (state = 'REFUNDED' AND event_id IS NOT NULL
                                   AND refund_operation_id IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX raid_seals_active_event_idx
                    ON raid_seals(event_id)
                    WHERE event_id IS NOT NULL AND state IN ('RESERVED', 'CONSUMED')
                    """);
            statement.executeUpdate("""
                    CREATE TABLE raid_seal_returns (
                        return_operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        original_seal_id TEXT NOT NULL REFERENCES raid_seals(seal_id)
                            ON DELETE RESTRICT,
                        returned_seal_id TEXT NOT NULL UNIQUE REFERENCES raid_seals(seal_id)
                            ON DELETE RESTRICT,
                        owner_player_id TEXT NOT NULL,
                        stage_level INTEGER NOT NULL CHECK (stage_level > 0),
                        issued_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static void applyVersionFour(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE management_operations (
                        operation_id TEXT PRIMARY KEY,
                        resource_type TEXT NOT NULL CHECK (resource_type IN ('TEAM', 'CORE')),
                        resource_id TEXT NOT NULL,
                        operation_kind TEXT NOT NULL CHECK (operation_kind IN (
                            'TEAM_ADD_MEMBER', 'TEAM_REMOVE_MEMBER', 'TEAM_TRANSFER_OWNER',
                            'TEAM_LEAVE', 'TEAM_DISBAND', 'CORE_REPAIR', 'CORE_RELOCATE',
                            'CORE_REBUILD', 'CORE_PLACE'
                        )),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX management_operations_resource_idx
                    ON management_operations(resource_type, resource_id, operation_kind)
                    """);
        }
    }

    private static void applyVersionFive(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE event_mutation_operations
                    ADD COLUMN rollback_decision TEXT CHECK (
                        rollback_decision IS NULL OR rollback_decision IN (
                            'RESTORE', 'SKIP_ALREADY_BEFORE', 'CONFLICT'
                        )
                    )
                    """);
        }
    }
}
