package io.github.takenoha.towerdefense.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** Applies ordered, in-process SQLite schema migrations. */
public final class SchemaMigrator {
    public static final int CURRENT_VERSION = 15;

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
                if (installedVersion < 6) {
                    applyVersionSix(connection);
                    recordMigration(connection, 6);
                }
                if (installedVersion < 7) {
                    applyVersionSeven(connection);
                    recordMigration(connection, 7);
                }
                if (installedVersion < 8) {
                    applyVersionEight(connection);
                    recordMigration(connection, 8);
                }
                if (installedVersion < 9) {
                    applyVersionNine(connection);
                    recordMigration(connection, 9);
                }
                if (installedVersion < 10) {
                    applyVersionTen(connection);
                    recordMigration(connection, 10);
                }
                if (installedVersion < 11) {
                    applyVersionEleven(connection);
                    recordMigration(connection, 11);
                }
                if (installedVersion < 12) {
                    applyVersionTwelve(connection);
                    recordMigration(connection, 12);
                }
                if (installedVersion < 13) {
                    applyVersionThirteen(connection);
                    recordMigration(connection, 13);
                }
                if (installedVersion < 14) {
                    applyVersionFourteen(connection);
                    recordMigration(connection, 14);
                }
                if (installedVersion < 15) {
                    applyVersionFifteen(connection);
                    recordMigration(connection, 15);
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

    private static void applyVersionSix(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS event_mutation_operations_event_idx");
            statement.executeUpdate(
                    "ALTER TABLE event_mutation_operations RENAME TO event_mutation_operations_v5");
            statement.executeUpdate("""
                    CREATE TABLE event_mutation_operations (
                        operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        operation_kind TEXT NOT NULL CHECK (operation_kind IN (
                            'BLOCK_APPLY', 'BLOCK_ROLLBACK', 'BLOCK_SETTLE', 'DROP_CLAIM', 'DROP_SETTLE',
                            'DROP_VOID', 'REWARD_ISSUE'
                        )),
                        target_id TEXT NOT NULL,
                        payload_fingerprint TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (state IN ('PREPARED', 'APPLIED')),
                        prepared_at TEXT NOT NULL,
                        applied_at TEXT,
                        rollback_decision TEXT CHECK (
                            rollback_decision IS NULL OR rollback_decision IN (
                                'RESTORE', 'SKIP_ALREADY_BEFORE', 'CONFLICT'
                            )
                        ),
                        UNIQUE (event_id, operation_kind, target_id),
                        CHECK ((state = 'PREPARED' AND applied_at IS NULL)
                               OR (state = 'APPLIED' AND applied_at IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO event_mutation_operations(
                        operation_id, event_id, operation_kind, target_id,
                        payload_fingerprint, state, prepared_at, applied_at, rollback_decision
                    )
                    SELECT operation_id, event_id, operation_kind, target_id,
                           payload_fingerprint, state, prepared_at, applied_at, rollback_decision
                    FROM event_mutation_operations_v5
                    """);
            statement.executeUpdate("DROP TABLE event_mutation_operations_v5");
            statement.executeUpdate("""
                    CREATE INDEX event_mutation_operations_event_idx
                    ON event_mutation_operations(event_id, operation_kind, state)
                    """);
            statement.executeUpdate("DROP INDEX IF EXISTS event_block_changes_recovery_idx");
            statement.executeUpdate(
                    "ALTER TABLE event_block_changes RENAME TO event_block_changes_v5");
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
                            status IN ('PREPARED', 'APPLIED', 'SETTLED', 'ROLLED_BACK', 'CONFLICT')
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
                        CHECK ((status IN ('SETTLED', 'ROLLED_BACK', 'CONFLICT')
                                    AND resolved_at IS NOT NULL)
                               OR (status IN ('PREPARED', 'APPLIED') AND resolved_at IS NULL))
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO event_block_changes(
                        change_id, event_id, world_id, block_x, block_y, block_z,
                        change_kind, generation, before_block_data, before_block_state,
                        expected_after_block_data, expected_after_block_state, status,
                        prepare_operation_id, apply_operation_id, rollback_operation_id,
                        prepared_at, applied_at, resolved_at
                    )
                    SELECT change_id, event_id, world_id, block_x, block_y, block_z,
                           change_kind, generation, before_block_data, before_block_state,
                           expected_after_block_data, expected_after_block_state, status,
                           prepare_operation_id, apply_operation_id, rollback_operation_id,
                           prepared_at, applied_at, resolved_at
                    FROM event_block_changes_v5
                    """);
            statement.executeUpdate("DROP TABLE event_block_changes_v5");
            statement.executeUpdate("""
                    CREATE INDEX event_block_changes_recovery_idx
                    ON event_block_changes(event_id, status, generation DESC)
                    """);
        }
    }

    private static void applyVersionSeven(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE event_reward_delivery_operations (
                        operation_id TEXT PRIMARY KEY,
                        queue_id TEXT NOT NULL UNIQUE REFERENCES event_reward_queue(queue_id)
                            ON DELETE RESTRICT,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id)
                            ON DELETE CASCADE,
                        player_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_reward_delivery_operations_event_idx
                    ON event_reward_delivery_operations(event_id, player_id)
                    """);
        }
    }

    private static void applyVersionEight(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS event_reward_delivery_operations_event_idx");
            statement.executeUpdate(
                    "ALTER TABLE event_reward_delivery_operations RENAME TO "
                            + "event_reward_delivery_operations_v7");
            statement.executeUpdate("""
                    CREATE TABLE event_reward_delivery_operations (
                        operation_id TEXT PRIMARY KEY,
                        queue_id TEXT NOT NULL UNIQUE REFERENCES event_reward_queue(queue_id)
                            ON DELETE RESTRICT,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id)
                            ON DELETE CASCADE,
                        player_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        payload_fingerprint TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (state IN ('PREPARED', 'APPLIED')),
                        prepared_at TEXT NOT NULL,
                        applied_at TEXT,
                        CHECK ((state = 'PREPARED' AND applied_at IS NULL)
                               OR (state = 'APPLIED' AND applied_at IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO event_reward_delivery_operations(
                        operation_id, queue_id, event_id, player_id, quantity,
                        payload_fingerprint, state, prepared_at, applied_at
                    )
                    SELECT operation_id, queue_id, event_id, player_id, quantity,
                           payload_fingerprint, 'APPLIED', applied_at, applied_at
                    FROM event_reward_delivery_operations_v7
                    """);
            statement.executeUpdate("DROP TABLE event_reward_delivery_operations_v7");
            statement.executeUpdate("""
                    CREATE INDEX event_reward_delivery_operations_event_idx
                    ON event_reward_delivery_operations(event_id, player_id, state)
                    """);
        }
    }

    private static void applyVersionNine(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE event_block_changes
                    ADD COLUMN before_tile_nbt TEXT NOT NULL DEFAULT ''
                    """);
            statement.executeUpdate("""
                    ALTER TABLE event_block_changes
                    ADD COLUMN expected_after_tile_nbt TEXT NOT NULL DEFAULT ''
                    """);
        }
    }

    private static void applyVersionTen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE event_reward_queue ADD COLUMN team_claim_deadline TEXT");
            statement.executeUpdate("""
                    CREATE INDEX event_reward_queue_team_deadline_idx
                    ON event_reward_queue(scope, status, team_claim_deadline)
                    """);
        }
    }

    private static void applyVersionEleven(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE core_placement_operations (
                        operation_id TEXT PRIMARY KEY,
                        item_id TEXT NOT NULL,
                        core_id TEXT NOT NULL,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        max_hp INTEGER NOT NULL CHECK (max_hp > 0),
                        minimum_core_distance REAL NOT NULL CHECK (minimum_core_distance > 0),
                        rebuilding_destroyed_core INTEGER NOT NULL CHECK (
                            rebuilding_destroyed_core IN (0, 1)
                        ),
                        previous_block_data TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (
                            state IN ('PREPARED', 'APPLIED', 'ROLLED_BACK')
                        ),
                        prepared_at TEXT NOT NULL,
                        applied_at TEXT,
                        rolled_back_at TEXT,
                        CHECK ((state = 'PREPARED' AND applied_at IS NULL AND rolled_back_at IS NULL)
                               OR (state = 'APPLIED' AND applied_at IS NOT NULL
                                   AND rolled_back_at IS NULL)
                               OR (state = 'ROLLED_BACK' AND applied_at IS NULL
                                   AND rolled_back_at IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX core_placement_operations_state_idx
                    ON core_placement_operations(state, prepared_at)
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX core_placement_operations_active_team_idx
                    ON core_placement_operations(team_id)
                    WHERE state = 'PREPARED'
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX core_placement_operations_applied_item_idx
                    ON core_placement_operations(item_id)
                    WHERE state = 'APPLIED'
                    """);
        }
    }

    private static void applyVersionTwelve(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE team_progress (
                        team_id TEXT PRIMARY KEY REFERENCES teams(team_id) ON DELETE CASCADE,
                        highest_cleared_level INTEGER NOT NULL CHECK (highest_cleared_level >= 0),
                        unlocked_level INTEGER NOT NULL CHECK (unlocked_level > 0),
                        research_points INTEGER NOT NULL CHECK (research_points >= 0),
                        updated_at TEXT NOT NULL,
                        CHECK (unlocked_level >= highest_cleared_level + 1
                               OR highest_cleared_level = 9223372036854775807)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO team_progress(
                        team_id, highest_cleared_level, unlocked_level, research_points, updated_at
                    )
                    SELECT team_id, 0, 1, 0, created_at FROM teams
                    """);
            statement.executeUpdate("""
                    ALTER TABLE core_placement_operations
                    ADD COLUMN relocating_existing_core INTEGER NOT NULL DEFAULT 0
                    CHECK (relocating_existing_core IN (0, 1))
                    """);
        }
    }

    private static void applyVersionThirteen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE towers (
                        tower_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow')),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        entity_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (world_id, block_x, block_y, block_z)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX towers_team_idx ON towers(team_id, tower_id)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE tower_placement_operations (
                        operation_id TEXT PRIMARY KEY,
                        tower_id TEXT NOT NULL UNIQUE,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow')),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        entity_id TEXT UNIQUE,
                        state TEXT NOT NULL CHECK (
                            state IN ('PREPARED', 'APPLIED', 'ROLLED_BACK')
                        ),
                        prepared_at TEXT NOT NULL,
                        applied_at TEXT,
                        rolled_back_at TEXT,
                        CHECK ((state = 'PREPARED' AND applied_at IS NULL
                                AND rolled_back_at IS NULL)
                               OR (state = 'APPLIED' AND applied_at IS NOT NULL
                                   AND rolled_back_at IS NULL AND entity_id IS NOT NULL)
                               OR (state = 'ROLLED_BACK' AND applied_at IS NULL
                                   AND rolled_back_at IS NOT NULL AND entity_id IS NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX tower_placement_operations_state_idx
                    ON tower_placement_operations(state, prepared_at)
                    """);
        }
    }

    private static void applyVersionFourteen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE tower_removal_operations (
                        operation_id TEXT PRIMARY KEY,
                        tower_id TEXT NOT NULL UNIQUE,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow')),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        entity_id TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (
                            state IN ('PREPARED', 'APPLIED', 'ROLLED_BACK')
                        ),
                        prepared_at TEXT NOT NULL,
                        applied_at TEXT,
                        rolled_back_at TEXT,
                        CHECK ((state = 'PREPARED' AND applied_at IS NULL
                                AND rolled_back_at IS NULL)
                               OR (state = 'APPLIED' AND applied_at IS NOT NULL
                                   AND rolled_back_at IS NULL)
                               OR (state = 'ROLLED_BACK' AND applied_at IS NULL
                                   AND rolled_back_at IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX tower_removal_operations_state_idx
                    ON tower_removal_operations(state, prepared_at)
                    """);
        }
    }

    private static void applyVersionFifteen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE towers
                    ADD COLUMN target_priority TEXT NOT NULL DEFAULT 'core_nearest'
                    CHECK (target_priority IN (
                        'core_nearest', 'nearest', 'health_high', 'health_low', 'boss'
                    ))
                    """);
            statement.executeUpdate("""
                    ALTER TABLE tower_placement_operations
                    ADD COLUMN target_priority TEXT NOT NULL DEFAULT 'core_nearest'
                    CHECK (target_priority IN (
                        'core_nearest', 'nearest', 'health_high', 'health_low', 'boss'
                    ))
                    """);
        }
    }
}
