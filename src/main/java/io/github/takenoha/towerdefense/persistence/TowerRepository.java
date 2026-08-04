package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transactional persistence boundary for installed towers and their placement stop window. */
public final class TowerRepository {
    private final Database database;

    public TowerRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public List<TowerRecord> loadAllTowers() {
        return read("load all towers", connection -> {
            List<TowerRecord> towers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                           tower_type, individual_level, entity_id, created_at, updated_at
                    FROM towers
                    ORDER BY tower_id
                    """);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    towers.add(towerFromRow(resultSet));
                }
            }
            return List.copyOf(towers);
        });
    }

    public Optional<TowerRecord> findTower(UUID towerId) {
        Objects.requireNonNull(towerId, "towerId");
        return read("load a tower", connection -> loadTower(connection, towerId));
    }

    /** Loads physical tower identities whose item handoff may have been interrupted. */
    public List<UUID> loadAppliedTowerIds() {
        return read("load applied tower identities", connection -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT tower_id FROM towers ORDER BY created_at, tower_id
                    """);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(uuid(resultSet.getString("tower_id")));
                }
            }
            return List.copyOf(ids);
        });
    }

    public List<TowerPlacement> loadPendingTowerPlacements() {
        return read("load pending tower placements", connection -> {
            List<TowerPlacement> placements = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT operation_id, tower_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, tower_type, individual_level,
                           state, prepared_at, applied_at, rolled_back_at
                    FROM tower_placement_operations
                    WHERE state = 'PREPARED'
                    ORDER BY prepared_at, operation_id
                    """);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    placements.add(placementFromRow(resultSet));
                }
            }
            return List.copyOf(placements);
        });
    }

    /**
     * Reserves an item identity and validates team membership, event lock, and tower capacity.
     */
    public TowerPlacement prepareTowerPlacement(
            TowerPlacement placement,
            TowerSettings settings) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(settings, "settings");
        if (placement.state() != TowerPlacementState.PREPARED) {
            throw new IllegalArgumentException("A tower placement request must be PREPARED");
        }
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerPlacement> existing = loadPlacement(
                        connection, placement.operationId());
                if (existing.isPresent()) {
                    requireMatchingPlacement(existing.orElseThrow(), placement);
                    return existing.orElseThrow();
                }
                requireTowerPlacementWindow(
                        connection, placement.teamId(), "prepare a tower placement");
                requireTeamMember(connection, placement.teamId(), placement.actorId());
                requireTowerCapacity(connection, placement.teamId(), settings);
                if (loadTower(connection, placement.towerId()).isPresent()) {
                    throw new PersistenceConflictException(
                            "The tower item identity has already been used");
                }
                if (towerAt(connection, placement.worldId(), placement.blockX(),
                        placement.blockY(), placement.blockZ()).isPresent()) {
                    throw new PersistenceConflictException(
                            "Another tower already occupies that position");
                }
                insertPlacement(connection, placement);
                return placement;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower placement conflicts with persisted tower data", exception);
            }
            throw failure("prepare a tower placement", exception);
        }
    }

    /** Applies the database side after the Paper entity has been spawned and tagged. */
    public TowerRecord applyTowerPlacement(
            UUID operationId,
            UUID entityId,
            TowerSettings settings,
            Instant appliedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                TowerPlacement placement = loadPlacement(connection, operationId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The prepared tower placement does not exist"));
                if (placement.state() == TowerPlacementState.APPLIED) {
                    TowerRecord tower = loadTower(connection, placement.towerId()).orElseThrow(
                            () -> new PersistenceConflictException(
                                    "An applied tower placement has no tower row"));
                    UUID appliedEntity = loadPlacementEntityId(connection, operationId).orElseThrow(
                            () -> new PersistenceConflictException(
                                    "An applied tower placement has no entity identity"));
                    if (!appliedEntity.equals(entityId)) {
                        throw new PersistenceConflictException(
                                "The tower placement entity identity does not match");
                    }
                    return tower;
                }
                if (placement.state() == TowerPlacementState.ROLLED_BACK) {
                    throw new PersistenceConflictException(
                            "The prepared tower placement was already rolled back");
                }
                requireTowerPlacementWindow(
                        connection, placement.teamId(), "apply a tower placement");
                requireTeamMember(connection, placement.teamId(), placement.actorId());
                requireTowerCapacity(connection, placement.teamId(), settings);
                if (loadTower(connection, placement.towerId()).isPresent()) {
                    throw new PersistenceConflictException(
                            "The tower item identity has already been applied");
                }
                if (towerAt(connection, placement.worldId(), placement.blockX(),
                        placement.blockY(), placement.blockZ()).isPresent()) {
                    throw new PersistenceConflictException(
                            "Another tower already occupies that position");
                }
                TowerRecord tower = new TowerRecord(
                        placement.towerId(),
                        placement.teamId(),
                        placement.worldId(),
                        placement.blockX(),
                        placement.blockY(),
                        placement.blockZ(),
                        placement.type(),
                        placement.individualLevel(),
                        entityId,
                        appliedAt,
                        appliedAt);
                insertTower(connection, tower);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tower_placement_operations
                        SET entity_id = ?, state = 'APPLIED', applied_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, entityId.toString());
                    statement.setString(2, appliedAt.toString());
                    statement.setString(3, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower placement apply affected no rows");
                    }
                }
                return tower;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower placement conflicts with persisted tower data", exception);
            }
            throw failure("apply a tower placement", exception);
        }
    }

    /** Marks a prepared operation rolled back after the physical entity was removed. */
    public Optional<TowerPlacement> rollbackTowerPlacement(
            UUID operationId,
            Instant rolledBackAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerPlacement> loaded = loadPlacement(connection, operationId);
                if (loaded.isEmpty() || loaded.orElseThrow().state() != TowerPlacementState.PREPARED) {
                    return loaded;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tower_placement_operations
                        SET state = 'ROLLED_BACK', rolled_back_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, rolledBackAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower placement rollback affected no rows");
                    }
                }
                TowerPlacement placement = loaded.orElseThrow();
                return Optional.of(new TowerPlacement(
                        placement.operationId(),
                        placement.towerId(),
                        placement.actorId(),
                        placement.teamId(),
                        placement.worldId(),
                        placement.blockX(),
                        placement.blockY(),
                        placement.blockZ(),
                        placement.type(),
                        placement.individualLevel(),
                        TowerPlacementState.ROLLED_BACK,
                        placement.preparedAt(),
                        null,
                        rolledBackAt));
            });
        } catch (SQLException exception) {
            throw failure("roll back a prepared tower placement", exception);
        }
    }

    private static void requireTowerCapacity(
            Connection connection,
            UUID teamId,
            TowerSettings settings) throws SQLException {
        TeamProgress progress = loadTeamProgress(connection, teamId).orElseThrow(
                () -> new PersistenceConflictException(
                        "Team " + teamId + " has no progression row"));
        int count = countTowers(connection, teamId);
        if (count >= settings.limitFor(progress.highestClearedLevel())) {
            throw new PersistenceConflictException(
                    "The team's tower limit has been reached (" + count + ")");
        }
    }

    private static int countTowers(Connection connection, UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM towers WHERE team_id = ?")) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0;
                }
                return Math.toIntExact(resultSet.getLong(1));
            }
        }
    }

    private static Optional<TowerRecord> towerAt(
            Connection connection,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                       tower_type, individual_level, entity_id, created_at, updated_at
                FROM towers
                WHERE world_id = ? AND block_x = ? AND block_y = ? AND block_z = ?
                """)) {
            statement.setString(1, worldId.toString());
            statement.setInt(2, blockX);
            statement.setInt(3, blockY);
            statement.setInt(4, blockZ);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(towerFromRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static Optional<TowerRecord> loadTower(Connection connection, UUID towerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                       tower_type, individual_level, entity_id, created_at, updated_at
                FROM towers WHERE tower_id = ?
                """)) {
            statement.setString(1, towerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(towerFromRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static Optional<TowerPlacement> loadPlacement(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, tower_id, actor_id, team_id, world_id,
                       block_x, block_y, block_z, tower_type, individual_level,
                       state, prepared_at, applied_at, rolled_back_at
                FROM tower_placement_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(placementFromRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static Optional<UUID> loadPlacementEntityId(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT entity_id FROM tower_placement_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getString("entity_id") == null) {
                    return Optional.empty();
                }
                return Optional.of(uuid(resultSet.getString("entity_id")));
            }
        }
    }

    private static Optional<TeamProgress> loadTeamProgress(
            Connection connection,
            UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, highest_cleared_level, unlocked_level, research_points
                FROM team_progress WHERE team_id = ?
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new TeamProgress(
                                uuid(resultSet.getString("team_id")),
                                resultSet.getLong("highest_cleared_level"),
                                resultSet.getLong("unlocked_level"),
                                resultSet.getLong("research_points")))
                        : Optional.empty();
            }
        }
    }

    private static void requireTeamMember(
            Connection connection,
            UUID teamId,
            UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM team_members WHERE team_id = ? AND player_id = ?
                """)) {
            statement.setString(1, teamId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException(
                            "Player " + playerId + " is not a member of team " + teamId);
                }
            }
        }
    }

    private static void requireTowerPlacementWindow(
            Connection connection,
            UUID teamId,
            String operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT e.team_id, e.state
                FROM event_lock lock
                JOIN defense_events e ON e.event_id = lock.event_id
                WHERE lock.singleton = 1
                """);
                ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                DefensePhase phase = DefensePhase.valueOf(resultSet.getString("state"));
                UUID eventTeamId = uuid(resultSet.getString("team_id"));
                if (!eventTeamId.equals(teamId)
                        || (phase != DefensePhase.PREPARATION
                                && phase != DefensePhase.INTERMISSION)) {
                    throw new PersistenceConflictException(
                            "Cannot " + operation
                                    + " outside the team's PREPARATION or INTERMISSION window");
                }
            }
        }
    }

    private static void insertPlacement(Connection connection, TowerPlacement placement)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tower_placement_operations(
                    operation_id, tower_id, actor_id, team_id, world_id,
                    block_x, block_y, block_z, tower_type, individual_level,
                    state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, placement.operationId().toString());
            statement.setString(2, placement.towerId().toString());
            statement.setString(3, placement.actorId().toString());
            statement.setString(4, placement.teamId().toString());
            statement.setString(5, placement.worldId().toString());
            statement.setInt(6, placement.blockX());
            statement.setInt(7, placement.blockY());
            statement.setInt(8, placement.blockZ());
            statement.setString(9, placement.type().id());
            statement.setInt(10, placement.individualLevel());
            statement.setString(11, placement.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void insertTower(Connection connection, TowerRecord tower) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO towers(
                    tower_id, team_id, world_id, block_x, block_y, block_z,
                    tower_type, individual_level, entity_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, tower.id().toString());
            statement.setString(2, tower.teamId().toString());
            statement.setString(3, tower.worldId().toString());
            statement.setInt(4, tower.blockX());
            statement.setInt(5, tower.blockY());
            statement.setInt(6, tower.blockZ());
            statement.setString(7, tower.type().id());
            statement.setInt(8, tower.individualLevel());
            statement.setString(9, tower.entityId().toString());
            statement.setString(10, tower.createdAt().toString());
            statement.setString(11, tower.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    private static TowerRecord towerFromRow(ResultSet resultSet) throws SQLException {
        return new TowerRecord(
                uuid(resultSet.getString("tower_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("world_id")),
                resultSet.getInt("block_x"),
                resultSet.getInt("block_y"),
                resultSet.getInt("block_z"),
                TowerType.fromId(resultSet.getString("tower_type")),
                resultSet.getInt("individual_level"),
                uuid(resultSet.getString("entity_id")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")));
    }

    private static TowerPlacement placementFromRow(ResultSet resultSet) throws SQLException {
        return new TowerPlacement(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("tower_id")),
                uuid(resultSet.getString("actor_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("world_id")),
                resultSet.getInt("block_x"),
                resultSet.getInt("block_y"),
                resultSet.getInt("block_z"),
                TowerType.fromId(resultSet.getString("tower_type")),
                resultSet.getInt("individual_level"),
                TowerPlacementState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static void requireMatchingPlacement(
            TowerPlacement existing,
            TowerPlacement requested) {
        if (!existing.towerId().equals(requested.towerId())
                || !existing.actorId().equals(requested.actorId())
                || !existing.teamId().equals(requested.teamId())
                || !existing.worldId().equals(requested.worldId())
                || existing.blockX() != requested.blockX()
                || existing.blockY() != requested.blockY()
                || existing.blockZ() != requested.blockZ()
                || existing.type() != requested.type()
                || existing.individualLevel() != requested.individualLevel()) {
            throw new PersistenceConflictException(
                    "The tower placement operation UUID is already assigned to another payload");
        }
    }

    private <T> T read(String operation, Database.SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    private static PersistenceException failure(String operation, SQLException exception) {
        return new PersistenceException("Could not " + operation, exception);
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("constraint")
                || message.contains("UNIQUE")
                || message.contains("CHECK"));
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalidUuid) {
            throw new PersistenceException("Invalid UUID in tower persistence", invalidUuid);
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalidInstant) {
            throw new PersistenceException("Invalid timestamp in tower persistence", invalidInstant);
        }
    }

    private static Instant nullableInstant(String value) {
        return value == null ? null : instant(value);
    }
}
