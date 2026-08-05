package io.github.takenoha.towerdefense.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** Applies ordered, in-process SQLite schema migrations. */
public final class SchemaMigrator {
    public static final int CURRENT_VERSION = 37;

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
                if (installedVersion < 16) {
                    applyVersionSixteen(connection);
                    recordMigration(connection, 16);
                }
                if (installedVersion < 17) {
                    applyVersionSeventeen(connection);
                    recordMigration(connection, 17);
                }
                if (installedVersion < 18) {
                    applyVersionEighteen(connection);
                    recordMigration(connection, 18);
                }
                if (installedVersion < 19) {
                    applyVersionNineteen(connection);
                    recordMigration(connection, 19);
                }
                if (installedVersion < 20) {
                    applyVersionTwenty(connection);
                    recordMigration(connection, 20);
                }
                if (installedVersion < 21) {
                    applyVersionTwentyOne(connection);
                    recordMigration(connection, 21);
                }
                if (installedVersion < 22) {
                    applyVersionTwentyTwo(connection);
                    recordMigration(connection, 22);
                }
                if (installedVersion < 23) {
                    applyVersionTwentyThree(connection);
                    recordMigration(connection, 23);
                }
                if (installedVersion < 24) {
                    applyVersionTwentyFour(connection);
                    recordMigration(connection, 24);
                }
                if (installedVersion < 25) {
                    applyVersionTwentyFive(connection);
                    recordMigration(connection, 25);
                }
                if (installedVersion < 26) {
                    applyVersionTwentySix(connection);
                    recordMigration(connection, 26);
                }
                if (installedVersion < 27) {
                    applyVersionTwentySeven(connection);
                    recordMigration(connection, 27);
                }
                if (installedVersion < 28) {
                    applyVersionTwentyEight(connection);
                    recordMigration(connection, 28);
                }
                if (installedVersion < 29) {
                    applyVersionTwentyNine(connection);
                    recordMigration(connection, 29);
                }
                if (installedVersion < 30) {
                    applyVersionThirty(connection);
                    recordMigration(connection, 30);
                }
                if (installedVersion < 31) {
                    applyVersionThirtyOne(connection);
                    recordMigration(connection, 31);
                }
                if (installedVersion < 32) {
                    applyVersionThirtyTwo(connection);
                    recordMigration(connection, 32);
                }
                if (installedVersion < 33) {
                    applyVersionThirtyThree(connection);
                    recordMigration(connection, 33);
                }
                if (installedVersion < 34) {
                    applyVersionThirtyFour(connection);
                    recordMigration(connection, 34);
                }
                if (installedVersion < 35) {
                    applyVersionThirtyFive(connection);
                    recordMigration(connection, 35);
                }
                if (installedVersion < 36) {
                    applyVersionThirtySix(connection);
                    recordMigration(connection, 36);
                }
                if (installedVersion < 37) {
                    applyVersionThirtySeven(connection);
                    recordMigration(connection, 37);
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

    /** Widens the tower type checks for databases created before the Cannon slice. */
    private static void applyVersionSixteen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS towers_team_idx");
            statement.executeUpdate("""
                    CREATE TABLE towers_cannon_new (
                        tower_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow', 'cannon')),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        target_priority TEXT NOT NULL DEFAULT 'core_nearest'
                            CHECK (target_priority IN (
                                'core_nearest', 'nearest', 'health_high', 'health_low', 'boss'
                            )),
                        entity_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (world_id, block_x, block_y, block_z)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO towers_cannon_new(
                        tower_id, team_id, world_id, block_x, block_y, block_z,
                        tower_type, individual_level, target_priority,
                        entity_id, created_at, updated_at)
                    SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                           tower_type, individual_level, target_priority,
                           entity_id, created_at, updated_at
                    FROM towers
                    """);
            statement.executeUpdate("DROP TABLE towers");
            statement.executeUpdate("ALTER TABLE towers_cannon_new RENAME TO towers");
            statement.executeUpdate("""
                    CREATE INDEX towers_team_idx ON towers(team_id, tower_id)
                    """);

            statement.executeUpdate("DROP INDEX IF EXISTS tower_placement_operations_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE tower_placement_operations_cannon_new (
                        operation_id TEXT PRIMARY KEY,
                        tower_id TEXT NOT NULL UNIQUE,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow', 'cannon')),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        target_priority TEXT NOT NULL DEFAULT 'core_nearest'
                            CHECK (target_priority IN (
                                'core_nearest', 'nearest', 'health_high', 'health_low', 'boss'
                            )),
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
                    INSERT INTO tower_placement_operations_cannon_new(
                        operation_id, tower_id, actor_id, team_id, world_id,
                        block_x, block_y, block_z, tower_type, individual_level,
                        target_priority, entity_id, state, prepared_at, applied_at, rolled_back_at)
                    SELECT operation_id, tower_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, tower_type, individual_level,
                           target_priority, entity_id, state, prepared_at, applied_at, rolled_back_at
                    FROM tower_placement_operations
                    """);
            statement.executeUpdate("DROP TABLE tower_placement_operations");
            statement.executeUpdate(
                    "ALTER TABLE tower_placement_operations_cannon_new "
                            + "RENAME TO tower_placement_operations");
            statement.executeUpdate("""
                    CREATE INDEX tower_placement_operations_state_idx
                    ON tower_placement_operations(state, prepared_at)
                    """);

            statement.executeUpdate("DROP INDEX IF EXISTS tower_removal_operations_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE tower_removal_operations_cannon_new (
                        operation_id TEXT PRIMARY KEY,
                        tower_id TEXT NOT NULL UNIQUE,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow', 'cannon')),
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
                    INSERT INTO tower_removal_operations_cannon_new(
                        operation_id, tower_id, actor_id, team_id, world_id,
                        block_x, block_y, block_z, tower_type, individual_level,
                        entity_id, state, prepared_at, applied_at, rolled_back_at)
                    SELECT operation_id, tower_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, tower_type, individual_level,
                           entity_id, state, prepared_at, applied_at, rolled_back_at
                    FROM tower_removal_operations
                    """);
            statement.executeUpdate("DROP TABLE tower_removal_operations");
            statement.executeUpdate(
                    "ALTER TABLE tower_removal_operations_cannon_new "
                            + "RENAME TO tower_removal_operations");
            statement.executeUpdate("""
                    CREATE INDEX tower_removal_operations_state_idx
                    ON tower_removal_operations(state, prepared_at)
                    """);
        }
    }

    /** Adds per-team tower research caps and their UUID-idempotent purchase ledger. */
    private static void applyVersionSeventeen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE tower_research (
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow', 'cannon')),
                        research_level INTEGER NOT NULL CHECK (research_level > 0),
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (team_id, tower_type)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO tower_research(team_id, tower_type, research_level, updated_at)
                    SELECT team_id, 'arrow', 1, created_at FROM teams
                    UNION ALL
                    SELECT team_id, 'cannon', 1, created_at FROM teams
                    """);
            statement.executeUpdate("""
                    CREATE INDEX tower_research_team_idx
                    ON tower_research(team_id, tower_type)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE tower_research_operations (
                        operation_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        actor_id TEXT NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN ('arrow', 'cannon')),
                        research_point_cost INTEGER NOT NULL CHECK (research_point_cost > 0),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX tower_research_operations_team_idx
                    ON tower_research_operations(team_id, applied_at)
                    """);
        }
    }

    /** Adds team-bound research-crystal issuance and two-phase redemption ledgers. */
    private static void applyVersionEighteen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE research_crystal_batches (
                        batch_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL UNIQUE
                            REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        stage_level INTEGER NOT NULL CHECK (stage_level > 0),
                        issued_quantity INTEGER NOT NULL CHECK (issued_quantity > 0),
                        redeemed_quantity INTEGER NOT NULL DEFAULT 0 CHECK (
                            redeemed_quantity >= 0 AND redeemed_quantity <= issued_quantity
                        ),
                        state TEXT NOT NULL CHECK (
                            state IN ('ISSUED', 'EXHAUSTED', 'VOIDED')
                        ),
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        CHECK ((state = 'EXHAUSTED' AND redeemed_quantity = issued_quantity)
                               OR (state IN ('ISSUED', 'VOIDED')))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX research_crystal_batches_team_idx
                    ON research_crystal_batches(team_id, state, created_at)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE research_crystal_redemptions (
                        operation_id TEXT PRIMARY KEY,
                        batch_id TEXT NOT NULL
                            REFERENCES research_crystal_batches(batch_id) ON DELETE RESTRICT,
                        core_id TEXT NOT NULL REFERENCES cores(core_id) ON DELETE RESTRICT,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        actor_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        payload_fingerprint TEXT NOT NULL,
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
                    CREATE INDEX research_crystal_redemptions_batch_idx
                    ON research_crystal_redemptions(batch_id, state, prepared_at)
                    """);
        }
    }

    /** Adds an event-scoped, idempotently mutated battle-funds ledger. */
    private static void applyVersionNineteen(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE event_battle_funds (
                        event_id TEXT PRIMARY KEY
                            REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        balance INTEGER NOT NULL CHECK (balance >= 0),
                        total_earned INTEGER NOT NULL CHECK (total_earned >= 0),
                        total_spent INTEGER NOT NULL CHECK (total_spent >= 0),
                        state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'SETTLED')),
                        updated_at TEXT NOT NULL,
                        CHECK (total_spent <= total_earned),
                        CHECK (state = 'ACTIVE' OR balance = 0)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_battle_fund_operations (
                        operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL
                            REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        actor_id TEXT,
                        operation_kind TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK (amount > 0),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL,
                        UNIQUE (event_id, operation_kind, payload_fingerprint)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_battle_fund_operations_event_idx
                    ON event_battle_fund_operations(event_id, applied_at)
                    """);
            statement.executeUpdate("""
                    INSERT INTO event_battle_funds(
                        event_id, team_id, balance, total_earned, total_spent, state, updated_at
                    )
                    SELECT event_id, team_id, 0, 0, 0,
                           CASE WHEN state IN ('VICTORY', 'DEFEAT', 'ABORTED', 'RECOVERY')
                                THEN 'SETTLED' ELSE 'ACTIVE' END,
                           updated_at
                    FROM defense_events
                    """);
        }
    }

    /** Adds two-phase, idempotent individual tower-level upgrade operations. */
    private static void applyVersionTwenty(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE tower_upgrade_operations (
                        operation_id TEXT PRIMARY KEY,
                        tower_id TEXT NOT NULL,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        from_level INTEGER NOT NULL CHECK (from_level > 0),
                        to_level INTEGER NOT NULL CHECK (to_level = from_level + 1),
                        defense_shard_cost INTEGER NOT NULL CHECK (defense_shard_cost > 0),
                        enhancement_core_cost INTEGER NOT NULL CHECK (enhancement_core_cost > 0),
                        payload_fingerprint TEXT NOT NULL,
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
                    CREATE INDEX tower_upgrade_operations_tower_idx
                    ON tower_upgrade_operations(tower_id, state, prepared_at)
                    """);
        }
    }

    /** Widens every persisted tower-type check for the five specialist towers. */
    private static void applyVersionTwentyOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS towers_team_idx");
            statement.executeUpdate("""
                    CREATE TABLE towers_specialists_new (
                        tower_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN (
                            'arrow', 'cannon', 'frost', 'lightning', 'support', 'sniper', 'flame'
                        )),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        target_priority TEXT NOT NULL DEFAULT 'core_nearest'
                            CHECK (target_priority IN (
                                'core_nearest', 'nearest', 'health_high', 'health_low', 'boss'
                            )),
                        entity_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (world_id, block_x, block_y, block_z)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO towers_specialists_new(
                        tower_id, team_id, world_id, block_x, block_y, block_z,
                        tower_type, individual_level, target_priority,
                        entity_id, created_at, updated_at)
                    SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                           tower_type, individual_level, target_priority,
                           entity_id, created_at, updated_at
                    FROM towers
                    """);
            statement.executeUpdate("DROP TABLE towers");
            statement.executeUpdate("ALTER TABLE towers_specialists_new RENAME TO towers");
            statement.executeUpdate("""
                    CREATE INDEX towers_team_idx ON towers(team_id, tower_id)
                    """);

            statement.executeUpdate("DROP INDEX IF EXISTS tower_placement_operations_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE tower_placement_operations_specialists_new (
                        operation_id TEXT PRIMARY KEY,
                        tower_id TEXT NOT NULL UNIQUE,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN (
                            'arrow', 'cannon', 'frost', 'lightning', 'support', 'sniper', 'flame'
                        )),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        target_priority TEXT NOT NULL DEFAULT 'core_nearest'
                            CHECK (target_priority IN (
                                'core_nearest', 'nearest', 'health_high', 'health_low', 'boss'
                            )),
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
                    INSERT INTO tower_placement_operations_specialists_new(
                        operation_id, tower_id, actor_id, team_id, world_id,
                        block_x, block_y, block_z, tower_type, individual_level,
                        target_priority, entity_id, state, prepared_at, applied_at, rolled_back_at)
                    SELECT operation_id, tower_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, tower_type, individual_level,
                           target_priority, entity_id, state, prepared_at, applied_at, rolled_back_at
                    FROM tower_placement_operations
                    """);
            statement.executeUpdate("DROP TABLE tower_placement_operations");
            statement.executeUpdate(
                    "ALTER TABLE tower_placement_operations_specialists_new "
                            + "RENAME TO tower_placement_operations");
            statement.executeUpdate("""
                    CREATE INDEX tower_placement_operations_state_idx
                    ON tower_placement_operations(state, prepared_at)
                    """);

            statement.executeUpdate("DROP INDEX IF EXISTS tower_removal_operations_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE tower_removal_operations_specialists_new (
                        operation_id TEXT PRIMARY KEY,
                        tower_id TEXT NOT NULL UNIQUE,
                        actor_id TEXT NOT NULL,
                        team_id TEXT NOT NULL,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN (
                            'arrow', 'cannon', 'frost', 'lightning', 'support', 'sniper', 'flame'
                        )),
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
                    INSERT INTO tower_removal_operations_specialists_new(
                        operation_id, tower_id, actor_id, team_id, world_id,
                        block_x, block_y, block_z, tower_type, individual_level,
                        entity_id, state, prepared_at, applied_at, rolled_back_at)
                    SELECT operation_id, tower_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, tower_type, individual_level,
                           entity_id, state, prepared_at, applied_at, rolled_back_at
                    FROM tower_removal_operations
                    """);
            statement.executeUpdate("DROP TABLE tower_removal_operations");
            statement.executeUpdate(
                    "ALTER TABLE tower_removal_operations_specialists_new "
                            + "RENAME TO tower_removal_operations");
            statement.executeUpdate("""
                    CREATE INDEX tower_removal_operations_state_idx
                    ON tower_removal_operations(state, prepared_at)
                    """);

            statement.executeUpdate("""
                    CREATE TABLE tower_research_specialists_new (
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        tower_type TEXT NOT NULL CHECK (tower_type IN (
                            'arrow', 'cannon', 'frost', 'lightning', 'support', 'sniper', 'flame'
                        )),
                        research_level INTEGER NOT NULL CHECK (research_level > 0),
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (team_id, tower_type)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO tower_research_specialists_new(
                        team_id, tower_type, research_level, updated_at)
                    SELECT team_id, tower_type, research_level, updated_at
                    FROM tower_research
                    """);
            for (String towerType : new String[] {
                "frost", "lightning", "support", "sniper", "flame"
            }) {
                statement.executeUpdate("""
                        INSERT INTO tower_research_specialists_new(
                            team_id, tower_type, research_level, updated_at)
                        SELECT team_id, '%s', 1, created_at
                        FROM teams
                        WHERE NOT EXISTS (
                            SELECT 1 FROM tower_research_specialists_new existing
                            WHERE existing.team_id = teams.team_id
                              AND existing.tower_type = '%s'
                        )
                        """.formatted(towerType, towerType));
            }
            statement.executeUpdate("DROP TABLE tower_research");
            statement.executeUpdate(
                    "ALTER TABLE tower_research_specialists_new RENAME TO tower_research");
            statement.executeUpdate("""
                    CREATE INDEX tower_research_team_idx
                    ON tower_research(team_id, tower_type)
                    """);

            statement.executeUpdate("""
                    CREATE TABLE tower_research_operations_specialists_new (
                        operation_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        actor_id TEXT NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN (
                            'arrow', 'cannon', 'frost', 'lightning', 'support', 'sniper', 'flame'
                        )),
                        research_point_cost INTEGER NOT NULL CHECK (research_point_cost > 0),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO tower_research_operations_specialists_new(
                        operation_id, team_id, actor_id, tower_type,
                        research_point_cost, payload_fingerprint, applied_at)
                    SELECT operation_id, team_id, actor_id, tower_type,
                           research_point_cost, payload_fingerprint, applied_at
                    FROM tower_research_operations
                    """);
            statement.executeUpdate("DROP TABLE tower_research_operations");
            statement.executeUpdate(
                    "ALTER TABLE tower_research_operations_specialists_new "
                            + "RENAME TO tower_research_operations");
            statement.executeUpdate("""
                    CREATE INDEX tower_research_operations_team_idx
                    ON tower_research_operations(team_id, applied_at)
                    """);
        }
    }

    /** Adds event-scoped, idempotent temporary tower boosts. */
    private static void applyVersionTwentyTwo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE event_tower_boosts (
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        tower_id TEXT NOT NULL,
                        boost_kind TEXT NOT NULL CHECK (
                            boost_kind IN ('power', 'speed', 'range')
                        ),
                        level INTEGER NOT NULL CHECK (level > 0),
                        multiplier REAL NOT NULL CHECK (multiplier > 0),
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (event_id, tower_id, boost_kind)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_tower_boosts_event_idx
                    ON event_tower_boosts(event_id, tower_id)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event_tower_boost_operations (
                        operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        actor_id TEXT NOT NULL,
                        tower_id TEXT NOT NULL,
                        boost_kind TEXT NOT NULL CHECK (
                            boost_kind IN ('power', 'speed', 'range')
                        ),
                        cost INTEGER NOT NULL CHECK (cost > 0),
                        boost_multiplier REAL NOT NULL CHECK (boost_multiplier > 0),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_tower_boost_operations_event_idx
                    ON event_tower_boost_operations(event_id, applied_at)
                    """);
        }
    }

    /** Adds durable current/max HP to each installed tower for battle-time repair. */
    private static void applyVersionTwentyThree(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS towers_team_idx");
            statement.executeUpdate("""
                    CREATE TABLE towers_health_new (
                        tower_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        world_id TEXT NOT NULL,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        tower_type TEXT NOT NULL CHECK (tower_type IN (
                            'arrow', 'cannon', 'frost', 'lightning', 'support', 'sniper', 'flame'
                        )),
                        individual_level INTEGER NOT NULL CHECK (individual_level > 0),
                        target_priority TEXT NOT NULL DEFAULT 'core_nearest'
                            CHECK (target_priority IN (
                                'core_nearest', 'nearest', 'health_high', 'health_low', 'boss'
                            )),
                        current_hp INTEGER NOT NULL CHECK (current_hp >= 0 AND current_hp <= max_hp),
                        max_hp INTEGER NOT NULL CHECK (max_hp > 0),
                        entity_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE (world_id, block_x, block_y, block_z)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO towers_health_new(
                        tower_id, team_id, world_id, block_x, block_y, block_z,
                        tower_type, individual_level, target_priority,
                        current_hp, max_hp, entity_id, created_at, updated_at)
                    SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                           tower_type, individual_level, target_priority,
                           100, 100, entity_id, created_at, updated_at
                    FROM towers
                    """);
            statement.executeUpdate("DROP TABLE towers");
            statement.executeUpdate("ALTER TABLE towers_health_new RENAME TO towers");
            statement.executeUpdate("""
                    CREATE INDEX towers_team_idx ON towers(team_id, tower_id)
                    """);
        }
    }

    /** Adds the UUID ledger for event-scoped tower repairs. */
    private static void applyVersionTwentyFour(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE event_tower_repair_operations (
                        operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        actor_id TEXT NOT NULL,
                        tower_id TEXT NOT NULL,
                        repaired_hit_points INTEGER NOT NULL CHECK (repaired_hit_points > 0),
                        cost INTEGER NOT NULL CHECK (cost > 0),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_tower_repair_operations_event_idx
                    ON event_tower_repair_operations(event_id, applied_at)
                    """);
        }
    }

    /** Adds an idempotent event ledger for destroyer damage against installed towers. */
    private static void applyVersionTwentyFive(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE event_tower_damage_operations (
                        operation_id TEXT PRIMARY KEY,
                        event_id TEXT NOT NULL REFERENCES defense_events(event_id) ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE RESTRICT,
                        attacker_enemy_id TEXT NOT NULL,
                        tower_id TEXT NOT NULL,
                        damage INTEGER NOT NULL CHECK (damage > 0),
                        remaining_hp INTEGER NOT NULL CHECK (remaining_hp >= 0),
                        destroyed INTEGER NOT NULL CHECK (destroyed IN (0, 1)),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL,
                        CHECK ((destroyed = 1 AND remaining_hp = 0)
                               OR (destroyed = 0 AND remaining_hp > 0))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_tower_damage_operations_event_idx
                    ON event_tower_damage_operations(event_id, applied_at)
                    """);
            statement.executeUpdate("""
                    CREATE INDEX event_tower_damage_operations_tower_idx
                    ON event_tower_damage_operations(tower_id, applied_at)
                    """);
        }
    }

    /** Adds named teams and reconnect-safe offline invitation records. */
    private static void applyVersionTwentySix(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE teams
                    ADD COLUMN display_name TEXT NOT NULL DEFAULT 'チーム'
                    """);
            statement.executeUpdate("""
                    CREATE TABLE team_profile_operations (
                        operation_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        actor_id TEXT NOT NULL,
                        operation_kind TEXT NOT NULL CHECK (operation_kind IN ('TEAM_RENAME')),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE team_invites (
                        invite_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        inviter_id TEXT NOT NULL,
                        invitee_id TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (
                            state IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED')
                        ),
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        resolved_at TEXT,
                        create_payload_fingerprint TEXT NOT NULL,
                        CHECK ((state = 'PENDING' AND resolved_at IS NULL)
                               OR (state <> 'PENDING' AND resolved_at IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX team_invites_recipient_state_idx
                    ON team_invites(invitee_id, state, expires_at)
                    """);
            statement.executeUpdate("""
                    CREATE INDEX team_invites_team_state_idx
                    ON team_invites(team_id, state, invitee_id)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE team_invite_operations (
                        operation_id TEXT PRIMARY KEY,
                        invite_id TEXT NOT NULL REFERENCES team_invites(invite_id) ON DELETE CASCADE,
                        actor_id TEXT NOT NULL,
                        operation_kind TEXT NOT NULL CHECK (
                            operation_kind IN ('TEAM_INVITE_CREATE', 'TEAM_INVITE_ACCEPT',
                                               'TEAM_INVITE_DECLINE')
                        ),
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL,
                        UNIQUE (invite_id, operation_kind)
                    )
                    """);
        }
    }

    /** Adds team-scoped point wallets and an idempotent debit/settlement ledger. */
    private static void applyVersionTwentySeven(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE team_resource_balances (
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        resource_type TEXT NOT NULL CHECK (
                            resource_type IN ('DEFENSE_POINTS', 'ENHANCEMENT_POINTS')
                        ),
                        balance INTEGER NOT NULL CHECK (balance >= 0),
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (team_id, resource_type)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO team_resource_balances(team_id, resource_type, balance, updated_at)
                    SELECT teams.team_id, resources.resource_type, 0, teams.created_at
                    FROM teams
                    CROSS JOIN (
                        SELECT 'DEFENSE_POINTS' AS resource_type
                        UNION ALL SELECT 'ENHANCEMENT_POINTS'
                    ) resources
                    """);
            statement.executeUpdate("""
                    CREATE TABLE team_resource_operations (
                        operation_id TEXT PRIMARY KEY,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        resource_type TEXT NOT NULL CHECK (
                            resource_type IN ('DEFENSE_POINTS', 'ENHANCEMENT_POINTS')
                        ),
                        operation_kind TEXT NOT NULL CHECK (
                            operation_kind IN ('EVENT_SETTLEMENT', 'DEBIT', 'CREDIT')
                        ),
                        source_id TEXT NOT NULL,
                        delta INTEGER NOT NULL,
                        payload_fingerprint TEXT NOT NULL,
                        applied_at TEXT NOT NULL,
                        UNIQUE (team_id, resource_type, operation_kind, source_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX team_resource_operations_team_idx
                    ON team_resource_operations(team_id, resource_type, applied_at)
                    """);
        }
    }

    /** Records whether management payments use the new wallet or legacy item path. */
    private static void applyVersionTwentyEight(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE management_operations
                    ADD COLUMN payment_mode TEXT NOT NULL DEFAULT 'LEGACY_ITEMS'
                    CHECK (payment_mode IN ('POINT_WALLET', 'LEGACY_ITEMS'))
                    """);
            statement.executeUpdate("""
                    ALTER TABLE tower_upgrade_operations
                    ADD COLUMN payment_mode TEXT NOT NULL DEFAULT 'LEGACY_ITEMS'
                    CHECK (payment_mode IN ('POINT_WALLET', 'LEGACY_ITEMS'))
                    """);
        }
    }

    /** Adds the prepared receipt boundary for core repairs paid with wallet points. */
    private static void applyVersionTwentyNine(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE core_repair_operations (
                        operation_id TEXT PRIMARY KEY,
                        core_id TEXT NOT NULL,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        actor_id TEXT NOT NULL,
                        expected_current_hp INTEGER NOT NULL CHECK (expected_current_hp > 0),
                        repair_amount INTEGER NOT NULL CHECK (repair_amount > 0),
                        defense_point_cost INTEGER NOT NULL CHECK (defense_point_cost >= 0),
                        payment_mode TEXT NOT NULL CHECK (
                            payment_mode IN ('POINT_WALLET', 'LEGACY_ITEMS')
                        ),
                        vanilla_material TEXT NOT NULL,
                        vanilla_material_amount INTEGER NOT NULL CHECK (vanilla_material_amount >= 0),
                        payload_fingerprint TEXT NOT NULL,
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
                    CREATE INDEX core_repair_operations_actor_state_idx
                    ON core_repair_operations(actor_id, state, prepared_at)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE core_repair_receipts (
                        operation_id TEXT PRIMARY KEY
                            REFERENCES core_repair_operations(operation_id) ON DELETE CASCADE,
                        player_id TEXT NOT NULL,
                        material TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('RESERVED', 'CLEARED', 'RESTORED')
                        ),
                        reserved_at TEXT NOT NULL,
                        resolved_at TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX core_repair_receipts_player_state_idx
                    ON core_repair_receipts(player_id, state, reserved_at)
                    """);
        }
    }

    /** Adds a durable handoff state for receipts secured in the player's inventory. */
    private static void applyVersionThirty(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE core_repair_receipts RENAME TO core_repair_receipts_v29");
            statement.executeUpdate("DROP INDEX IF EXISTS core_repair_receipts_player_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE core_repair_receipts (
                        operation_id TEXT PRIMARY KEY
                            REFERENCES core_repair_operations(operation_id) ON DELETE CASCADE,
                        player_id TEXT NOT NULL,
                        material TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('RESERVED', 'SECURED', 'CLEARED', 'RESTORED')
                        ),
                        reserved_at TEXT NOT NULL,
                        resolved_at TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO core_repair_receipts(
                        operation_id, player_id, material, quantity, state,
                        reserved_at, resolved_at)
                    SELECT operation_id, player_id, material, quantity, state,
                           reserved_at, resolved_at
                    FROM core_repair_receipts_v29
                    """);
            statement.executeUpdate("DROP TABLE core_repair_receipts_v29");
            statement.executeUpdate("""
                    CREATE INDEX core_repair_receipts_player_state_idx
                    ON core_repair_receipts(player_id, state, reserved_at)
                    """);
        }
    }

    /** Persists the physical defense-shard portion of legacy core-repair payments. */
    private static void applyVersionThirtyOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    ALTER TABLE core_repair_operations
                    ADD COLUMN legacy_defense_shard_amount INTEGER NOT NULL DEFAULT 0
                    CHECK (legacy_defense_shard_amount >= 0)
                    """);
        }
    }

    /** Adds a two-phase receipt table for legacy physical tower-upgrade materials. */
    private static void applyVersionThirtyTwo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE tower_upgrade_receipts (
                        operation_id TEXT NOT NULL
                            REFERENCES tower_upgrade_operations(operation_id) ON DELETE CASCADE,
                        material TEXT NOT NULL CHECK (
                            material IN ('DEFENSE_SHARD', 'ENHANCEMENT_CORE')
                        ),
                        player_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('RESERVED', 'SECURED', 'CLEARED', 'RESTORED')
                        ),
                        reserved_at TEXT NOT NULL,
                        resolved_at TEXT,
                        PRIMARY KEY (operation_id, material)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX tower_upgrade_receipts_player_state_idx
                    ON tower_upgrade_receipts(player_id, state, reserved_at)
                    """);
        }
    }

    /** Adds a durable marker for the physical-clear step of core repair receipts. */
    private static void applyVersionThirtyThree(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE core_repair_receipts RENAME TO core_repair_receipts_v32");
            statement.executeUpdate("DROP INDEX IF EXISTS core_repair_receipts_player_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE core_repair_receipts (
                        operation_id TEXT PRIMARY KEY
                            REFERENCES core_repair_operations(operation_id) ON DELETE CASCADE,
                        player_id TEXT NOT NULL,
                        material TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('RESERVED', 'SECURED', 'CLEAR_PENDING', 'CLEARED', 'RESTORED')
                        ),
                        reserved_at TEXT NOT NULL,
                        resolved_at TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO core_repair_receipts(
                        operation_id, player_id, material, quantity, state,
                        reserved_at, resolved_at)
                    SELECT operation_id, player_id, material, quantity, state,
                           reserved_at, resolved_at
                    FROM core_repair_receipts_v32
                    """);
            statement.executeUpdate("DROP TABLE core_repair_receipts_v32");
            statement.executeUpdate("""
                    CREATE INDEX core_repair_receipts_player_state_idx
                    ON core_repair_receipts(player_id, state, reserved_at)
                    """);
        }
    }

    /** Adds a durable marker for the physical-clear step of tower-upgrade receipts. */
    private static void applyVersionThirtyFour(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE tower_upgrade_receipts RENAME TO tower_upgrade_receipts_v32");
            statement.executeUpdate("DROP INDEX IF EXISTS tower_upgrade_receipts_player_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE tower_upgrade_receipts (
                        operation_id TEXT NOT NULL
                            REFERENCES tower_upgrade_operations(operation_id) ON DELETE CASCADE,
                        material TEXT NOT NULL CHECK (
                            material IN ('DEFENSE_SHARD', 'ENHANCEMENT_CORE')
                        ),
                        player_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('RESERVED', 'SECURED', 'CLEAR_PENDING', 'CLEARED', 'RESTORED')
                        ),
                        reserved_at TEXT NOT NULL,
                        resolved_at TEXT,
                        PRIMARY KEY (operation_id, material)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO tower_upgrade_receipts(
                        operation_id, material, player_id, quantity, state,
                        reserved_at, resolved_at)
                    SELECT operation_id, material, player_id, quantity, state,
                           reserved_at, resolved_at
                    FROM tower_upgrade_receipts_v32
                    """);
            statement.executeUpdate("DROP TABLE tower_upgrade_receipts_v32");
            statement.executeUpdate("""
                    CREATE INDEX tower_upgrade_receipts_player_state_idx
                    ON tower_upgrade_receipts(player_id, state, reserved_at)
                    """);
        }
    }

    /** Adds a durable return-pending state for physical receipt refunds. */
    private static void applyVersionThirtyFive(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE core_repair_receipts RENAME TO core_repair_receipts_v34");
            statement.executeUpdate("DROP INDEX IF EXISTS core_repair_receipts_player_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE core_repair_receipts (
                        operation_id TEXT PRIMARY KEY
                            REFERENCES core_repair_operations(operation_id) ON DELETE CASCADE,
                        player_id TEXT NOT NULL,
                        material TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('RESERVED', 'SECURED', 'RETURN_PENDING', 'CLEAR_PENDING',
                                      'CLEARED', 'RESTORED')
                        ),
                        reserved_at TEXT NOT NULL,
                        resolved_at TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO core_repair_receipts(
                        operation_id, player_id, material, quantity, state,
                        reserved_at, resolved_at)
                    SELECT operation_id, player_id, material, quantity, state,
                           reserved_at, resolved_at
                    FROM core_repair_receipts_v34
                    """);
            statement.executeUpdate("DROP TABLE core_repair_receipts_v34");
            statement.executeUpdate("""
                    CREATE INDEX core_repair_receipts_player_state_idx
                    ON core_repair_receipts(player_id, state, reserved_at)
                    """);

            statement.executeUpdate(
                    "ALTER TABLE tower_upgrade_receipts RENAME TO tower_upgrade_receipts_v34");
            statement.executeUpdate("DROP INDEX IF EXISTS tower_upgrade_receipts_player_state_idx");
            statement.executeUpdate("""
                    CREATE TABLE tower_upgrade_receipts (
                        operation_id TEXT NOT NULL
                            REFERENCES tower_upgrade_operations(operation_id) ON DELETE CASCADE,
                        material TEXT NOT NULL CHECK (
                            material IN ('DEFENSE_SHARD', 'ENHANCEMENT_CORE')
                        ),
                        player_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (
                            state IN ('RESERVED', 'SECURED', 'RETURN_PENDING', 'CLEAR_PENDING',
                                      'CLEARED', 'RESTORED')
                        ),
                        reserved_at TEXT NOT NULL,
                        resolved_at TEXT,
                        PRIMARY KEY (operation_id, material)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO tower_upgrade_receipts(
                        operation_id, material, player_id, quantity, state,
                        reserved_at, resolved_at)
                    SELECT operation_id, material, player_id, quantity, state,
                           reserved_at, resolved_at
                    FROM tower_upgrade_receipts_v34
                    """);
            statement.executeUpdate("DROP TABLE tower_upgrade_receipts_v34");
            statement.executeUpdate("""
                    CREATE INDEX tower_upgrade_receipts_player_state_idx
                    ON tower_upgrade_receipts(player_id, state, reserved_at)
                    """);
        }
    }

    /** Adds DB-backed resource vouchers and the delivery/redeem stop-boundary ledgers. */
    private static void applyVersionThirtySix(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE resource_vouchers (
                        voucher_id TEXT PRIMARY KEY,
                        withdrawal_operation_id TEXT NOT NULL UNIQUE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        resource_type TEXT NOT NULL CHECK (
                            resource_type IN ('DEFENSE_POINTS', 'ENHANCEMENT_POINTS')
                        ),
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        state TEXT NOT NULL CHECK (state IN (
                            'PENDING_DELIVERY', 'AVAILABLE', 'RESERVED', 'REDEEMED', 'VOIDED'
                        )),
                        delivery_recipient_player_id TEXT NOT NULL,
                        payload_fingerprint TEXT NOT NULL,
                        issued_at TEXT NOT NULL,
                        available_at TEXT,
                        reserved_at TEXT,
                        redeemed_at TEXT,
                        voided_at TEXT,
                        CHECK ((state = 'PENDING_DELIVERY' AND available_at IS NULL
                                AND reserved_at IS NULL AND redeemed_at IS NULL
                                AND voided_at IS NULL)
                               OR (state = 'AVAILABLE' AND available_at IS NOT NULL
                                   AND reserved_at IS NULL AND redeemed_at IS NULL
                                   AND voided_at IS NULL)
                               OR (state = 'RESERVED' AND available_at IS NOT NULL
                                   AND reserved_at IS NOT NULL AND redeemed_at IS NULL
                                   AND voided_at IS NULL)
                               OR (state = 'REDEEMED' AND available_at IS NOT NULL
                                   AND reserved_at IS NOT NULL AND redeemed_at IS NOT NULL
                                   AND voided_at IS NULL)
                               OR (state = 'VOIDED' AND voided_at IS NOT NULL))
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX resource_vouchers_delivery_idx
                    ON resource_vouchers(delivery_recipient_player_id, state, issued_at)
                    """);
            statement.executeUpdate("""
                    CREATE INDEX resource_vouchers_team_state_idx
                    ON resource_vouchers(team_id, state, voucher_id)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE resource_voucher_delivery_operations (
                        delivery_operation_id TEXT PRIMARY KEY,
                        voucher_id TEXT NOT NULL REFERENCES resource_vouchers(voucher_id)
                            ON DELETE CASCADE,
                        recipient_player_id TEXT NOT NULL,
                        payload_fingerprint TEXT NOT NULL,
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
                    CREATE INDEX resource_voucher_delivery_voucher_idx
                    ON resource_voucher_delivery_operations(voucher_id, state, prepared_at)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE resource_voucher_redeem_operations (
                        operation_id TEXT PRIMARY KEY,
                        voucher_id TEXT NOT NULL REFERENCES resource_vouchers(voucher_id)
                            ON DELETE CASCADE,
                        team_id TEXT NOT NULL REFERENCES teams(team_id) ON DELETE CASCADE,
                        actor_id TEXT NOT NULL,
                        resource_type TEXT NOT NULL CHECK (
                            resource_type IN ('DEFENSE_POINTS', 'ENHANCEMENT_POINTS')
                        ),
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        payload_fingerprint TEXT NOT NULL,
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
                    CREATE INDEX resource_voucher_redeem_voucher_idx
                    ON resource_voucher_redeem_operations(voucher_id, state, prepared_at)
                    """);
            statement.executeUpdate("""
                    CREATE INDEX resource_voucher_redeem_actor_idx
                    ON resource_voucher_redeem_operations(actor_id, state, prepared_at)
                    """);
        }
    }

    /** Adds per-delivery-segment redemption accounting for research-crystal copy protection. */
    private static void applyVersionThirtySeven(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE research_crystal_segments (
                        batch_id TEXT NOT NULL
                            REFERENCES research_crystal_batches(batch_id) ON DELETE RESTRICT,
                        segment_offset INTEGER NOT NULL CHECK (segment_offset >= 0),
                        segment_quantity INTEGER NOT NULL CHECK (segment_quantity > 0),
                        redeemed_quantity INTEGER NOT NULL DEFAULT 0 CHECK (
                            redeemed_quantity >= 0 AND redeemed_quantity <= segment_quantity
                        ),
                        PRIMARY KEY (batch_id, segment_offset)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX research_crystal_segments_batch_idx
                    ON research_crystal_segments(batch_id, segment_offset)
                    """);
            statement.executeUpdate(
                    "ALTER TABLE research_crystal_redemptions ADD COLUMN segment_offset INTEGER");
            statement.executeUpdate(
                    "ALTER TABLE research_crystal_redemptions ADD COLUMN segment_quantity INTEGER");
        }

        try (PreparedStatement batches = connection.prepareStatement(
                "SELECT batch_id, issued_quantity FROM research_crystal_batches");
                ResultSet resultSet = batches.executeQuery();
                PreparedStatement segments = connection.prepareStatement("""
                        INSERT INTO research_crystal_segments(
                            batch_id, segment_offset, segment_quantity)
                        VALUES (?, ?, ?)
                        ON CONFLICT(batch_id, segment_offset) DO NOTHING
                        """)) {
            while (resultSet.next()) {
                String batchId = resultSet.getString("batch_id");
                int issuedQuantity = resultSet.getInt("issued_quantity");
                for (int offset = 0; offset < issuedQuantity; offset += 64) {
                    segments.setString(1, batchId);
                    segments.setInt(2, offset);
                    segments.setInt(3, Math.min(64, issuedQuantity - offset));
                    segments.addBatch();
                }
            }
            segments.executeBatch();
        }
    }
}
