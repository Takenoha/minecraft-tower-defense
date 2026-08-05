package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerResearch;
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
                           tower_type, individual_level, target_priority,
                           entity_id, created_at, updated_at
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

    /** Loads all per-type research caps for one team in stable type order. */
    public List<TowerResearch> loadTowerResearch(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return read("load tower research", connection -> loadTowerResearch(connection, teamId));
    }

    public Optional<TowerResearch> findTowerResearch(UUID teamId, TowerType towerType) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(towerType, "towerType");
        return read(
                "load tower research",
                connection -> loadTowerResearch(connection, teamId, towerType));
    }

    /**
     * Spends team research points for exactly one level of one tower type.
     *
     * <p>The caller supplies the cost selected by the active balance/configuration layer. The
     * operation UUID and payload fingerprint make retries harmless while the global event lock
     * keeps research changes outside an active defense event.</p>
     */
    public TowerResearchMutationResult purchaseTowerResearch(
            UUID teamId,
            UUID actorId,
            TowerType towerType,
            long researchPointCost,
            UUID operationId,
            Instant appliedAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(towerType, "towerType");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (researchPointCost <= 0L) {
            throw new IllegalArgumentException("researchPointCost must be positive");
        }
        String fingerprint = researchFingerprint(
                teamId, actorId, towerType, researchPointCost);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<String> existingFingerprint = loadResearchOperationFingerprint(
                        connection, operationId);
                if (existingFingerprint.isPresent()) {
                    if (!existingFingerprint.orElseThrow().equals(fingerprint)) {
                        throw new PersistenceConflictException(
                                "The tower research operation UUID is already assigned to a different payload");
                    }
                    return new TowerResearchMutationResult(
                            OperationOutcome.ALREADY_APPLIED,
                            requireTeamProgress(connection, teamId),
                            requireTowerResearch(connection, teamId, towerType));
                }

                requireNoActiveEvent(connection, "purchase tower research");
                requireTeamMember(connection, teamId, actorId);
                TeamProgress progress = requireTeamProgress(connection, teamId);
                TowerResearch current = requireTowerResearch(connection, teamId, towerType);
                if (progress.researchPoints() < researchPointCost) {
                    throw new PersistenceConflictException(
                            "The team does not have enough research points for this purchase");
                }
                if (current.researchLevel() == Integer.MAX_VALUE) {
                    throw new PersistenceConflictException(
                            "The tower research level has reached its maximum value");
                }

                int nextLevel = current.researchLevel() + 1;
                TeamProgress updatedProgress = new TeamProgress(
                        progress.teamId(),
                        progress.highestClearedLevel(),
                        progress.unlockedLevel(),
                        progress.researchPoints() - researchPointCost);
                TowerResearch updatedResearch = new TowerResearch(
                        current.teamId(),
                        current.towerType(),
                        nextLevel,
                        appliedAt);
                updateTeamResearchPoints(connection, updatedProgress, appliedAt);
                updateTowerResearch(connection, updatedResearch);
                insertResearchOperation(
                        connection,
                        operationId,
                        teamId,
                        actorId,
                        towerType,
                        researchPointCost,
                        fingerprint,
                        appliedAt);
                return new TowerResearchMutationResult(
                        OperationOutcome.APPLIED, updatedProgress, updatedResearch);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower research purchase conflicts with persisted data", exception);
            }
            throw failure("purchase tower research", exception);
        }
    }

    /** Reserves one individual level upgrade before the physical materials are removed. */
    public TowerUpgrade prepareTowerUpgrade(TowerUpgrade upgrade) {
        Objects.requireNonNull(upgrade, "upgrade");
        if (upgrade.state() != TowerUpgradeState.PREPARED) {
            throw new IllegalArgumentException("A tower upgrade request must be PREPARED");
        }
        String fingerprint = upgradeFingerprint(upgrade);
        TowerUpgrade normalized = withFingerprint(upgrade, fingerprint);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerUpgrade> existing = loadTowerUpgrade(
                        connection, upgrade.operationId());
                if (existing.isPresent()) {
                    requireMatchingUpgrade(existing.orElseThrow(), normalized);
                    return existing.orElseThrow();
                }
                requireTowerPlacementWindow(
                        connection, upgrade.teamId(), "prepare a tower upgrade");
                requireTeamMember(connection, upgrade.teamId(), upgrade.actorId());
                TowerRecord tower = loadTower(connection, upgrade.towerId()).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The tower to upgrade does not exist"));
                if (!tower.teamId().equals(upgrade.teamId())
                        || tower.individualLevel() != upgrade.fromLevel()) {
                    throw new PersistenceConflictException(
                            "The tower level changed before the upgrade was prepared");
                }
                requireTowerResearch(
                        connection,
                        tower.teamId(),
                        tower.type(),
                        upgrade.toLevel());
                insertTowerUpgrade(connection, normalized);
                return normalized;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower upgrade conflicts with persisted tower data", exception);
            }
            throw failure("prepare a tower upgrade", exception);
        }
    }

    /** Applies the durable level mutation after the Paper materials were removed. */
    public TowerUpgradeResult applyTowerUpgrade(UUID operationId, Instant appliedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                TowerUpgrade upgrade = loadTowerUpgrade(connection, operationId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The prepared tower upgrade does not exist"));
                if (upgrade.state() == TowerUpgradeState.APPLIED) {
                    return new TowerUpgradeResult(
                            OperationOutcome.ALREADY_APPLIED,
                            loadTower(connection, upgrade.towerId()));
                }
                if (upgrade.state() == TowerUpgradeState.ROLLED_BACK) {
                    throw new PersistenceConflictException(
                            "The prepared tower upgrade was already rolled back");
                }
                requireTowerPlacementWindow(
                        connection, upgrade.teamId(), "apply a tower upgrade");
                requireTeamMember(connection, upgrade.teamId(), upgrade.actorId());
                TowerRecord current = loadTower(connection, upgrade.towerId()).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The tower to upgrade does not exist"));
                if (!current.teamId().equals(upgrade.teamId())
                        || current.individualLevel() != upgrade.fromLevel()) {
                    throw new PersistenceConflictException(
                            "The tower level changed before the upgrade was applied");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE towers
                        SET individual_level = ?, updated_at = ?
                        WHERE tower_id = ? AND individual_level = ?
                        """)) {
                    statement.setInt(1, upgrade.toLevel());
                    statement.setString(2, appliedAt.toString());
                    statement.setString(3, upgrade.towerId().toString());
                    statement.setInt(4, upgrade.fromLevel());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The tower upgrade lost its compare-and-set race");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tower_upgrade_operations
                        SET state = 'APPLIED', applied_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, appliedAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower upgrade apply affected no rows");
                    }
                }
                return new TowerUpgradeResult(
                        OperationOutcome.APPLIED,
                        loadTower(connection, upgrade.towerId()));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower upgrade conflicts with persisted tower data", exception);
            }
            throw failure("apply a tower upgrade", exception);
        }
    }

    /** Rolls back a reservation when the Paper material handoff did not complete. */
    public Optional<TowerUpgrade> rollbackTowerUpgrade(
            UUID operationId,
            Instant rolledBackAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerUpgrade> loaded = loadTowerUpgrade(connection, operationId);
                if (loaded.isEmpty()
                        || loaded.orElseThrow().state() != TowerUpgradeState.PREPARED) {
                    return loaded;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tower_upgrade_operations
                        SET state = 'ROLLED_BACK', rolled_back_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, rolledBackAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower upgrade rollback affected no rows");
                    }
                }
                TowerUpgrade upgrade = loaded.orElseThrow();
                return Optional.of(new TowerUpgrade(
                        upgrade.operationId(),
                        upgrade.towerId(),
                        upgrade.actorId(),
                        upgrade.teamId(),
                        upgrade.fromLevel(),
                        upgrade.toLevel(),
                        upgrade.defenseShardCost(),
                        upgrade.enhancementCoreCost(),
                        upgrade.payloadFingerprint(),
                        TowerUpgradeState.ROLLED_BACK,
                        upgrade.preparedAt(),
                        null,
                        rolledBackAt));
            });
        } catch (SQLException exception) {
            throw failure("roll back a tower upgrade", exception);
        }
    }

    /** Updates one tower's target-selection mode after validating team membership. */
    public TowerRecord updateTargetPriority(
            UUID towerId,
            UUID actorId,
            TowerTargetPriority targetPriority,
            Instant updatedAt) {
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetPriority, "targetPriority");
        Objects.requireNonNull(updatedAt, "updatedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                TowerRecord current = loadTower(connection, towerId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The tower to update does not exist"));
                requireTeamMember(connection, current.teamId(), actorId);
                if (current.targetPriority() == targetPriority) {
                    return current;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE towers
                        SET target_priority = ?, updated_at = ?
                        WHERE tower_id = ?
                        """)) {
                    statement.setString(1, targetPriority.id());
                    statement.setString(2, updatedAt.toString());
                    statement.setString(3, towerId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower target priority update affected no rows");
                    }
                }
                return loadTower(connection, towerId).orElseThrow(
                        () -> new SQLException(
                                "The tower disappeared after target priority update"));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower target priority is invalid", exception);
            }
            throw failure("update a tower target priority", exception);
        }
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
                           target_priority,
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

    /** Loads removal operations whose physical stop window was not completed. */
    public List<TowerRemoval> loadPendingTowerRemovals() {
        return read("load pending tower removals", connection -> loadRemovals(connection, "PREPARED"));
    }

    /** Loads applied removals so a restart can finish deleting stale physical entities. */
    public List<TowerRemoval> loadAppliedTowerRemovals() {
        return read("load applied tower removals", connection -> loadRemovals(connection, "APPLIED"));
    }

    /** Reserves one installed tower for removal after checking the global defense lock. */
    public TowerRemoval prepareTowerRemoval(TowerRemoval removal) {
        Objects.requireNonNull(removal, "removal");
        if (removal.state() != TowerRemovalState.PREPARED) {
            throw new IllegalArgumentException("A tower removal request must be PREPARED");
        }
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerRemoval> existing = loadRemoval(connection, removal.operationId());
                if (existing.isPresent()) {
                    requireMatchingRemoval(existing.orElseThrow(), removal);
                    return existing.orElseThrow();
                }
                requireNoActiveEvent(connection, "prepare a tower removal");
                requireTeamMember(connection, removal.teamId(), removal.actorId());
                TowerRecord tower = loadTower(connection, removal.towerId()).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The tower to remove does not exist"));
                requireMatchingTower(tower, removal);
                insertRemoval(connection, removal);
                return removal;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower removal conflicts with persisted tower data", exception);
            }
            throw failure("prepare a tower removal", exception);
        }
    }

    /** Deletes the durable tower row after the returned item has been secured physically. */
    public TowerRemoval applyTowerRemoval(UUID operationId, Instant appliedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                TowerRemoval removal = loadRemoval(connection, operationId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The prepared tower removal does not exist"));
                if (removal.state() == TowerRemovalState.APPLIED) {
                    return removal;
                }
                if (removal.state() == TowerRemovalState.ROLLED_BACK) {
                    throw new PersistenceConflictException(
                            "The prepared tower removal was already rolled back");
                }
                requireNoActiveEvent(connection, "apply a tower removal");
                requireTeamMember(connection, removal.teamId(), removal.actorId());
                TowerRecord tower = loadTower(connection, removal.towerId()).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The tower to remove does not exist"));
                requireMatchingTower(tower, removal);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM towers WHERE tower_id = ? AND entity_id = ?")) {
                    statement.setString(1, removal.towerId().toString());
                    statement.setString(2, removal.entityId().toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower removal deleted no rows");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tower_removal_operations
                        SET state = 'APPLIED', applied_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, appliedAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower removal apply affected no rows");
                    }
                }
                return new TowerRemoval(
                        removal.operationId(),
                        removal.towerId(),
                        removal.actorId(),
                        removal.teamId(),
                        removal.worldId(),
                        removal.blockX(),
                        removal.blockY(),
                        removal.blockZ(),
                        removal.type(),
                        removal.individualLevel(),
                        removal.entityId(),
                        TowerRemovalState.APPLIED,
                        removal.preparedAt(),
                        appliedAt,
                        null);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower removal conflicts with persisted tower data", exception);
            }
            throw failure("apply a tower removal", exception);
        }
    }

    /** Rolls back a prepared removal when its physical stop window did not complete. */
    public Optional<TowerRemoval> rollbackTowerRemoval(
            UUID operationId,
            Instant rolledBackAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerRemoval> loaded = loadRemoval(connection, operationId);
                if (loaded.isEmpty() || loaded.orElseThrow().state() != TowerRemovalState.PREPARED) {
                    return loaded;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tower_removal_operations
                        SET state = 'ROLLED_BACK', rolled_back_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, rolledBackAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The tower removal rollback affected no rows");
                    }
                }
                TowerRemoval removal = loaded.orElseThrow();
                return Optional.of(new TowerRemoval(
                        removal.operationId(),
                        removal.towerId(),
                        removal.actorId(),
                        removal.teamId(),
                        removal.worldId(),
                        removal.blockX(),
                        removal.blockY(),
                        removal.blockZ(),
                        removal.type(),
                        removal.individualLevel(),
                        removal.entityId(),
                        TowerRemovalState.ROLLED_BACK,
                        removal.preparedAt(),
                        null,
                        rolledBackAt));
            });
        } catch (SQLException exception) {
            throw failure("roll back a prepared tower removal", exception);
        }
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
                requireTowerResearch(
                        connection,
                        placement.teamId(),
                        placement.type(),
                        placement.individualLevel());
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
                requireTowerResearch(
                        connection,
                        placement.teamId(),
                        placement.type(),
                        placement.individualLevel());
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
                        placement.targetPriority(),
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
                        placement.targetPriority(),
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

    private static void requireTowerResearch(
            Connection connection,
            UUID teamId,
            TowerType towerType,
            int individualLevel) throws SQLException {
        TowerResearch research = requireTowerResearch(connection, teamId, towerType);
        if (individualLevel > research.researchLevel()) {
            throw new PersistenceConflictException(
                    "The team's " + towerType.id()
                            + " research only permits tower level " + research.researchLevel());
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
                       tower_type, individual_level, target_priority,
                       entity_id, created_at, updated_at
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
                       tower_type, individual_level, target_priority,
                       entity_id, created_at, updated_at
                FROM towers WHERE tower_id = ?
                """)) {
            statement.setString(1, towerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(towerFromRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static List<TowerRemoval> loadRemovals(
            Connection connection,
            String state) throws SQLException {
        List<TowerRemoval> removals = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, tower_id, actor_id, team_id, world_id,
                       block_x, block_y, block_z, tower_type, individual_level,
                       entity_id, state, prepared_at, applied_at, rolled_back_at
                FROM tower_removal_operations
                WHERE state = ?
                ORDER BY prepared_at, operation_id
                """)) {
            statement.setString(1, state);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    removals.add(removalFromRow(resultSet));
                }
            }
        }
        return List.copyOf(removals);
    }

    private static Optional<TowerRemoval> loadRemoval(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, tower_id, actor_id, team_id, world_id,
                       block_x, block_y, block_z, tower_type, individual_level,
                       entity_id, state, prepared_at, applied_at, rolled_back_at
                FROM tower_removal_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(removalFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<TowerPlacement> loadPlacement(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, tower_id, actor_id, team_id, world_id,
                       block_x, block_y, block_z, tower_type, individual_level,
                       target_priority,
                       state, prepared_at, applied_at, rolled_back_at
                FROM tower_placement_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(placementFromRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static Optional<TowerUpgrade> loadTowerUpgrade(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, tower_id, actor_id, team_id, from_level, to_level,
                       defense_shard_cost, enhancement_core_cost, payload_fingerprint,
                       state, prepared_at, applied_at, rolled_back_at
                FROM tower_upgrade_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(towerUpgradeFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static TowerUpgrade towerUpgradeFromRow(ResultSet resultSet) throws SQLException {
        return new TowerUpgrade(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("tower_id")),
                uuid(resultSet.getString("actor_id")),
                uuid(resultSet.getString("team_id")),
                resultSet.getInt("from_level"),
                resultSet.getInt("to_level"),
                resultSet.getInt("defense_shard_cost"),
                resultSet.getInt("enhancement_core_cost"),
                resultSet.getString("payload_fingerprint"),
                TowerUpgradeState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static void insertTowerUpgrade(
            Connection connection,
            TowerUpgrade upgrade) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tower_upgrade_operations(
                    operation_id, tower_id, actor_id, team_id, from_level, to_level,
                    defense_shard_cost, enhancement_core_cost, payload_fingerprint,
                    state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, upgrade.operationId().toString());
            statement.setString(2, upgrade.towerId().toString());
            statement.setString(3, upgrade.actorId().toString());
            statement.setString(4, upgrade.teamId().toString());
            statement.setInt(5, upgrade.fromLevel());
            statement.setInt(6, upgrade.toLevel());
            statement.setInt(7, upgrade.defenseShardCost());
            statement.setInt(8, upgrade.enhancementCoreCost());
            statement.setString(9, upgrade.payloadFingerprint());
            statement.setString(10, upgrade.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static TowerUpgrade withFingerprint(TowerUpgrade upgrade, String fingerprint) {
        return new TowerUpgrade(
                upgrade.operationId(),
                upgrade.towerId(),
                upgrade.actorId(),
                upgrade.teamId(),
                upgrade.fromLevel(),
                upgrade.toLevel(),
                upgrade.defenseShardCost(),
                upgrade.enhancementCoreCost(),
                fingerprint,
                upgrade.state(),
                upgrade.preparedAt(),
                upgrade.appliedAt(),
                upgrade.rolledBackAt());
    }

    private static String upgradeFingerprint(TowerUpgrade upgrade) {
        return upgrade.towerId() + "|" + upgrade.actorId() + "|" + upgrade.teamId()
                + "|" + upgrade.fromLevel() + "|" + upgrade.toLevel()
                + "|" + upgrade.defenseShardCost() + "|" + upgrade.enhancementCoreCost();
    }

    private static void requireMatchingUpgrade(
            TowerUpgrade existing,
            TowerUpgrade requested) {
        if (!existing.towerId().equals(requested.towerId())
                || !existing.actorId().equals(requested.actorId())
                || !existing.teamId().equals(requested.teamId())
                || existing.fromLevel() != requested.fromLevel()
                || existing.toLevel() != requested.toLevel()
                || existing.defenseShardCost() != requested.defenseShardCost()
                || existing.enhancementCoreCost() != requested.enhancementCoreCost()
                || !existing.payloadFingerprint().equals(requested.payloadFingerprint())) {
            throw new PersistenceConflictException(
                    "The tower upgrade operation UUID is already assigned to another payload");
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

    private static List<TowerResearch> loadTowerResearch(
            Connection connection,
            UUID teamId) throws SQLException {
        List<TowerResearch> research = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, tower_type, research_level, updated_at
                FROM tower_research
                WHERE team_id = ?
                ORDER BY tower_type
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    research.add(towerResearchFromRow(resultSet));
                }
            }
        }
        return List.copyOf(research);
    }

    private static Optional<TowerResearch> loadTowerResearch(
            Connection connection,
            UUID teamId,
            TowerType towerType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, tower_type, research_level, updated_at
                FROM tower_research
                WHERE team_id = ? AND tower_type = ?
                """)) {
            statement.setString(1, teamId.toString());
            statement.setString(2, towerType.id());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(towerResearchFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static TowerResearch requireTowerResearch(
            Connection connection,
            UUID teamId,
            TowerType towerType) throws SQLException {
        return loadTowerResearch(connection, teamId, towerType).orElseThrow(
                () -> new PersistenceConflictException(
                        "Team " + teamId + " has no research row for " + towerType.id()));
    }

    private static TeamProgress requireTeamProgress(
            Connection connection,
            UUID teamId) throws SQLException {
        return loadTeamProgress(connection, teamId).orElseThrow(
                () -> new PersistenceConflictException(
                        "Team " + teamId + " has no progression row"));
    }

    private static Optional<String> loadResearchOperationFingerprint(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_fingerprint
                FROM tower_research_operations
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(resultSet.getString("payload_fingerprint"))
                        : Optional.empty();
            }
        }
    }

    private static void updateTeamResearchPoints(
            Connection connection,
            TeamProgress progress,
            Instant updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE team_progress
                SET research_points = ?, updated_at = ?
                WHERE team_id = ?
                """)) {
            statement.setLong(1, progress.researchPoints());
            statement.setString(2, updatedAt.toString());
            statement.setString(3, progress.teamId().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The research point update affected no rows");
            }
        }
    }

    private static void updateTowerResearch(
            Connection connection,
            TowerResearch research) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tower_research
                SET research_level = ?, updated_at = ?
                WHERE team_id = ? AND tower_type = ?
                """)) {
            statement.setInt(1, research.researchLevel());
            statement.setString(2, research.updatedAt().toString());
            statement.setString(3, research.teamId().toString());
            statement.setString(4, research.towerType().id());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The tower research update affected no rows");
            }
        }
    }

    private static void insertResearchOperation(
            Connection connection,
            UUID operationId,
            UUID teamId,
            UUID actorId,
            TowerType towerType,
            long researchPointCost,
            String fingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tower_research_operations(
                    operation_id, team_id, actor_id, tower_type,
                    research_point_cost, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, actorId.toString());
            statement.setString(4, towerType.id());
            statement.setLong(5, researchPointCost);
            statement.setString(6, fingerprint);
            statement.setString(7, appliedAt.toString());
            statement.executeUpdate();
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
                    target_priority, state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
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
            statement.setString(11, placement.targetPriority().id());
            statement.setString(12, placement.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void insertTower(Connection connection, TowerRecord tower) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO towers(
                    tower_id, team_id, world_id, block_x, block_y, block_z,
                    tower_type, individual_level, target_priority,
                    entity_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, tower.id().toString());
            statement.setString(2, tower.teamId().toString());
            statement.setString(3, tower.worldId().toString());
            statement.setInt(4, tower.blockX());
            statement.setInt(5, tower.blockY());
            statement.setInt(6, tower.blockZ());
            statement.setString(7, tower.type().id());
            statement.setInt(8, tower.individualLevel());
            statement.setString(9, tower.targetPriority().id());
            statement.setString(10, tower.entityId().toString());
            statement.setString(11, tower.createdAt().toString());
            statement.setString(12, tower.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void insertRemoval(Connection connection, TowerRemoval removal)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tower_removal_operations(
                    operation_id, tower_id, actor_id, team_id, world_id,
                    block_x, block_y, block_z, tower_type, individual_level,
                    entity_id, state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, removal.operationId().toString());
            statement.setString(2, removal.towerId().toString());
            statement.setString(3, removal.actorId().toString());
            statement.setString(4, removal.teamId().toString());
            statement.setString(5, removal.worldId().toString());
            statement.setInt(6, removal.blockX());
            statement.setInt(7, removal.blockY());
            statement.setInt(8, removal.blockZ());
            statement.setString(9, removal.type().id());
            statement.setInt(10, removal.individualLevel());
            statement.setString(11, removal.entityId().toString());
            statement.setString(12, removal.preparedAt().toString());
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
                TowerTargetPriority.fromId(resultSet.getString("target_priority")),
                uuid(resultSet.getString("entity_id")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")));
    }

    private static TowerResearch towerResearchFromRow(ResultSet resultSet) throws SQLException {
        return new TowerResearch(
                uuid(resultSet.getString("team_id")),
                TowerType.fromId(resultSet.getString("tower_type")),
                resultSet.getInt("research_level"),
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
                TowerTargetPriority.fromId(resultSet.getString("target_priority")),
                TowerPlacementState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static TowerRemoval removalFromRow(ResultSet resultSet) throws SQLException {
        return new TowerRemoval(
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
                uuid(resultSet.getString("entity_id")),
                TowerRemovalState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static void requireMatchingRemoval(
            TowerRemoval existing,
            TowerRemoval requested) {
        if (!existing.towerId().equals(requested.towerId())
                || !existing.actorId().equals(requested.actorId())
                || !existing.teamId().equals(requested.teamId())
                || !existing.worldId().equals(requested.worldId())
                || existing.blockX() != requested.blockX()
                || existing.blockY() != requested.blockY()
                || existing.blockZ() != requested.blockZ()
                || existing.type() != requested.type()
                || existing.individualLevel() != requested.individualLevel()
                || !existing.entityId().equals(requested.entityId())) {
            throw new PersistenceConflictException(
                    "The tower removal operation UUID is already assigned to another payload");
        }
    }

    private static void requireMatchingTower(TowerRecord tower, TowerRemoval removal) {
        if (!tower.id().equals(removal.towerId())
                || !tower.teamId().equals(removal.teamId())
                || !tower.worldId().equals(removal.worldId())
                || tower.blockX() != removal.blockX()
                || tower.blockY() != removal.blockY()
                || tower.blockZ() != removal.blockZ()
                || tower.type() != removal.type()
                || tower.individualLevel() != removal.individualLevel()
                || !tower.entityId().equals(removal.entityId())) {
            throw new PersistenceConflictException(
                    "The installed tower does not match the removal request");
        }
    }

    private static void requireNoActiveEvent(
            Connection connection,
            String operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM event_lock WHERE singleton = 1")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new PersistenceConflictException(
                            "Cannot " + operation + " while a defense event is active");
                }
            }
        }
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
                || existing.individualLevel() != requested.individualLevel()
                || existing.targetPriority() != requested.targetPriority()) {
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

    private static String researchFingerprint(
            UUID teamId,
            UUID actorId,
            TowerType towerType,
            long researchPointCost) {
        return teamId + "|" + actorId + "|" + towerType.id() + "|" + researchPointCost;
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
