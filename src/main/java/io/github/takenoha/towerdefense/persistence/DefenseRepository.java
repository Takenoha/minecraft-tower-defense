package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional SQLite repository for teams, cores, and defense sessions.
 *
 * <p>The single-row {@code event_lock} table is the global event-lock source of truth. Session
 * creation, terminal persistence, recovery, core HP writes, and lock mutation are kept inside
 * immediate SQLite transactions.</p>
 */
public final class DefenseRepository {
    private static final String TERMINAL_PHASES_SQL =
            "('VICTORY', 'DEFEAT', 'ABORTED', 'RECOVERY')";

    private final Database database;

    public DefenseRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Creates a one-player team and its mandatory owner membership atomically. */
    public TeamRecord createSoloTeam(UUID teamId, UUID ownerPlayerId, Instant createdAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(createdAt, "createdAt");
        try {
            return database.inImmediateTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO teams(team_id, owner_player_id, created_at)
                        VALUES (?, ?, ?)
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, ownerPlayerId.toString());
                    statement.setString(3, createdAt.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO team_members(team_id, player_id, role, joined_at)
                        VALUES (?, ?, 'OWNER', ?)
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, ownerPlayerId.toString());
                    statement.setString(3, createdAt.toString());
                    statement.executeUpdate();
                }
                return new TeamRecord(teamId, ownerPlayerId, Set.of(ownerPlayerId), createdAt);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The team or owner already belongs to a persisted team", exception);
            }
            throw failure("create a solo team", exception);
        }
    }

    public Optional<TeamRecord> findTeam(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return read("load a team", connection -> loadTeam(connection, teamId));
    }

    /** Looks up the deterministic solo team owned by a player. */
    public Optional<TeamRecord> findTeamByOwner(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return read("load a team by owner", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT team_id FROM teams WHERE owner_player_id = ?
                    """)) {
                statement.setString(1, ownerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return loadTeam(connection, uuid(resultSet.getString("team_id")));
                }
            }
        });
    }

    /** Looks up the team to which a player currently belongs. */
    public Optional<TeamRecord> findTeamByMember(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return read("load a team by member", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT team_id FROM team_members WHERE player_id = ?
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return loadTeam(connection, uuid(resultSet.getString("team_id")));
                }
            }
        });
    }

    /** Adds a member when the actor is the owner and no event is active. */
    public TeamMutationResult addTeamMember(
            UUID teamId,
            UUID actorId,
            UUID memberId,
            UUID operationId,
            Instant joinedAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(joinedAt, "joinedAt");
        String fingerprint = managementFingerprint(
                "TEAM_ADD_MEMBER", teamId, actorId, memberId);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(), "TEAM", teamId, "TEAM_ADD_MEMBER", fingerprint);
                    return new TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "change team membership");
                TeamRecord team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                if (team.members().contains(memberId)) {
                    insertManagementOperation(
                            connection,
                            operationId,
                            "TEAM",
                            teamId,
                            "TEAM_ADD_MEMBER",
                            fingerprint,
                            joinedAt);
                    return new TeamMutationResult(
                            ManagementOutcome.APPLIED,
                            loadTeam(connection, teamId));
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO team_members(team_id, player_id, role, joined_at)
                        VALUES (?, ?, 'MEMBER', ?)
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, memberId.toString());
                    statement.setString(3, joinedAt.toString());
                    statement.executeUpdate();
                }
                insertManagementOperation(
                        connection,
                        operationId,
                        "TEAM",
                        teamId,
                        "TEAM_ADD_MEMBER",
                        fingerprint,
                        joinedAt);
                return new TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The player already belongs to another team", exception);
            }
            throw failure("add a team member", exception);
        }
    }

    /** Removes a non-owner member when the actor is the owner and no event is active. */
    public TeamMutationResult removeTeamMember(
            UUID teamId,
            UUID actorId,
            UUID memberId,
            UUID operationId,
            Instant removedAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(removedAt, "removedAt");
        String fingerprint = managementFingerprint(
                "TEAM_REMOVE_MEMBER", teamId, actorId, memberId);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "TEAM",
                            teamId,
                            "TEAM_REMOVE_MEMBER",
                            fingerprint);
                    return new TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "change team membership");
                TeamRecord team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                if (!team.members().contains(memberId)) {
                    throw new PersistenceConflictException(
                            "Player " + memberId + " is not a member of team " + teamId);
                }
                if (team.ownerId().equals(memberId)) {
                    throw new PersistenceConflictException("The team owner cannot be removed");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM team_members WHERE team_id = ? AND player_id = ?
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, memberId.toString());
                    statement.executeUpdate();
                }
                insertManagementOperation(
                        connection,
                        operationId,
                        "TEAM",
                        teamId,
                        "TEAM_REMOVE_MEMBER",
                        fingerprint,
                        removedAt);
                return new TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (SQLException exception) {
            throw failure("remove a team member", exception);
        }
    }

    /** Transfers ownership to an existing member while the team is idle. */
    public TeamMutationResult transferTeamOwnership(
            UUID teamId,
            UUID actorId,
            UUID newOwnerId,
            UUID operationId,
            Instant transferredAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(newOwnerId, "newOwnerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(transferredAt, "transferredAt");
        String fingerprint = managementFingerprint(
                "TEAM_TRANSFER_OWNER", teamId, actorId, newOwnerId);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "TEAM",
                            teamId,
                            "TEAM_TRANSFER_OWNER",
                            fingerprint);
                    return new TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "transfer team ownership");
                TeamRecord team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                if (!team.members().contains(newOwnerId)) {
                    throw new PersistenceConflictException(
                            "Ownership can only be transferred to an existing team member");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE team_members SET role = 'MEMBER'
                        WHERE team_id = ? AND player_id = ?
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, actorId.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE team_members SET role = 'OWNER'
                        WHERE team_id = ? AND player_id = ?
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, newOwnerId.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE teams SET owner_player_id = ? WHERE team_id = ?
                        """)) {
                    statement.setString(1, newOwnerId.toString());
                    statement.setString(2, teamId.toString());
                    statement.executeUpdate();
                }
                insertManagementOperation(
                        connection,
                        operationId,
                        "TEAM",
                        teamId,
                        "TEAM_TRANSFER_OWNER",
                        fingerprint,
                        transferredAt);
                return new TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (SQLException exception) {
            throw failure("transfer team ownership", exception);
        }
    }

    /** Leaves a team; a sole owner may leave only by removing an empty team. */
    public TeamMutationResult leaveTeam(
            UUID teamId,
            UUID playerId,
            UUID operationId,
            Instant leftAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(leftAt, "leftAt");
        String fingerprint = managementFingerprint("TEAM_LEAVE", teamId, playerId);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(), "TEAM", teamId, "TEAM_LEAVE", fingerprint);
                    return new TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "leave a team");
                TeamRecord team = requireTeam(connection, teamId);
                if (!team.members().contains(playerId)) {
                    throw new PersistenceConflictException(
                            "Player " + playerId + " is not a member of team " + teamId);
                }
                if (team.ownerId().equals(playerId)) {
                    if (team.members().size() > 1) {
                        throw new PersistenceConflictException(
                                "The owner must transfer ownership before leaving");
                    }
                    requireTeamCanBeDeleted(connection, teamId);
                    deleteTeam(connection, teamId);
                } else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            DELETE FROM team_members WHERE team_id = ? AND player_id = ?
                            """)) {
                        statement.setString(1, teamId.toString());
                        statement.setString(2, playerId.toString());
                        statement.executeUpdate();
                    }
                }
                insertManagementOperation(
                        connection,
                        operationId,
                        "TEAM",
                        teamId,
                        "TEAM_LEAVE",
                        fingerprint,
                        leftAt);
                return new TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The team still has persisted references", exception);
            }
            throw failure("leave a team", exception);
        }
    }

    /** Disbands an idle, empty team after verifying owner authority. */
    public TeamMutationResult disbandTeam(
            UUID teamId,
            UUID actorId,
            UUID operationId,
            Instant disbandedAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(disbandedAt, "disbandedAt");
        String fingerprint = managementFingerprint("TEAM_DISBAND", teamId, actorId);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "TEAM",
                            teamId,
                            "TEAM_DISBAND",
                            fingerprint);
                    return new TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "disband a team");
                TeamRecord team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                requireTeamCanBeDeleted(connection, teamId);
                deleteTeam(connection, teamId);
                insertManagementOperation(
                        connection,
                        operationId,
                        "TEAM",
                        teamId,
                        "TEAM_DISBAND",
                        fingerprint,
                        disbandedAt);
                return new TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.empty());
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The team still has persisted references", exception);
            }
            throw failure("disband a team", exception);
        }
    }

    /**
     * Places a core after checking the one-core-per-team and horizontal-distance invariants under
     * the same write lock as the insert.
     */
    public CoreRecord placeCore(CoreRecord core, double minimumCoreDistance) {
        Objects.requireNonNull(core, "core");
        requireDistance(minimumCoreDistance);
        try {
            return database.inImmediateTransaction(connection -> {
                return placeCore(connection, core, minimumCoreDistance);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The core conflicts with persisted ownership or position data", exception);
            }
            throw failure("place a core", exception);
        }
    }

    /** Places a core after verifying that the actor belongs to its team. */
    public CoreRecord placeCore(
            UUID actorId, CoreRecord core, double minimumCoreDistance) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(core, "core");
        requireDistance(minimumCoreDistance);
        try {
            return database.inImmediateTransaction(connection -> {
                requireTeamMember(connection, core.teamId(), actorId);
                return placeCore(connection, core, minimumCoreDistance);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The core conflicts with persisted ownership or position data", exception);
            }
            throw failure("place a core for a team member", exception);
        }
    }

    /** Places a core with an operation UUID so a Paper retry cannot create a second core. */
    public CoreMutationResult placeCore(
            UUID actorId,
            CoreRecord core,
            double minimumCoreDistance,
            UUID operationId,
            Instant placedAt) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(placedAt, "placedAt");
        requireDistance(minimumCoreDistance);
        String fingerprint = managementFingerprint(
                "CORE_PLACE",
                core.id(),
                actorId,
                core.teamId(),
                core.worldId(),
                core.blockX(),
                core.blockY(),
                core.blockZ(),
                core.maximumHitPoints(),
                minimumCoreDistance);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            core.id(),
                            "CORE_PLACE",
                            fingerprint);
                    return new CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, core.id()));
                }
                requireTeamMember(connection, core.teamId(), actorId);
                placeCore(connection, core, minimumCoreDistance);
                insertManagementOperation(
                        connection,
                        operationId,
                        "CORE",
                        core.id(),
                        "CORE_PLACE",
                        fingerprint,
                        placedAt);
                return new CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(core));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The core conflicts with persisted ownership or position data", exception);
            }
            throw failure("place a core with an operation", exception);
        }
    }

    public Optional<CoreRecord> findCore(UUID coreId) {
        Objects.requireNonNull(coreId, "coreId");
        return read("load a core", connection -> loadCore(connection, coreId));
    }

    public Optional<CoreRecord> findCoreByTeam(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return read("load a team's core", connection -> loadCoreByTeam(connection, teamId));
    }

    /** Loads the complete durable core registry in stable UUID order. */
    public List<CoreRecord> loadAllCores() {
        return read("load all cores", connection -> {
            List<CoreRecord> cores = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT core_id, team_id, world_id, block_x, block_y, block_z,
                           current_hp, max_hp, created_at, updated_at
                    FROM cores
                    ORDER BY core_id
                    """);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    cores.add(coreFromRow(resultSet));
                }
            }
            return List.copyOf(cores);
        });
    }

    /** Returns the first same-world core strictly nearer than the configured distance. */
    public Optional<CoreRecord> findDistanceConflict(
            UUID worldId, int blockX, int blockZ, double minimumCoreDistance) {
        Objects.requireNonNull(worldId, "worldId");
        requireDistance(minimumCoreDistance);
        return read(
                "check core distance",
                connection -> findDistanceConflict(
                        connection, worldId, blockX, blockZ, minimumCoreDistance));
    }

    /** Repairs a present core outside an active defense event. */
    public CoreMutationResult repairCore(
            UUID coreId,
            UUID actorId,
            long amount,
            UUID operationId,
            Instant repairedAt) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(repairedAt, "repairedAt");
        if (amount <= 0L) {
            throw new IllegalArgumentException("repair amount must be positive");
        }
        String fingerprint = managementFingerprint("CORE_REPAIR", coreId, actorId, amount);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            coreId,
                            "CORE_REPAIR",
                            fingerprint);
                    return new CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, coreId));
                }
                requireNoActiveEvent(connection, "repair a core");
                CoreRecord core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() == 0L) {
                    throw new PersistenceConflictException(
                            "A destroyed core must be rebuilt before it can be repaired");
                }
                long missingHitPoints = core.maximumHitPoints() - core.currentHitPoints();
                long repairedHitPoints = core.currentHitPoints()
                        + Math.min(amount, missingHitPoints);
                CoreRecord updated = new CoreRecord(
                        core.id(),
                        core.teamId(),
                        core.worldId(),
                        core.blockX(),
                        core.blockY(),
                        core.blockZ(),
                        repairedHitPoints,
                        core.maximumHitPoints(),
                        core.createdAt(),
                        repairedAt);
                updateCoreHealth(connection, updated);
                insertManagementOperation(
                        connection,
                        operationId,
                        "CORE",
                        coreId,
                        "CORE_REPAIR",
                        fingerprint,
                        repairedAt);
                return new CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(updated));
            });
        } catch (SQLException exception) {
            throw failure("repair a core", exception);
        }
    }

    /** Moves an intact core after checking ownership, idle state, and world separation. */
    public CoreMutationResult relocateCore(
            UUID coreId,
            UUID actorId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            double minimumCoreDistance,
            UUID operationId,
            Instant relocatedAt) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(relocatedAt, "relocatedAt");
        requireDistance(minimumCoreDistance);
        String fingerprint = managementFingerprint(
                "CORE_RELOCATE",
                coreId,
                actorId,
                worldId,
                blockX,
                blockY,
                blockZ,
                minimumCoreDistance);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            coreId,
                            "CORE_RELOCATE",
                            fingerprint);
                    return new CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, coreId));
                }
                requireNoActiveEvent(connection, "relocate a core");
                CoreRecord core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() != core.maximumHitPoints()) {
                    throw new PersistenceConflictException(
                            "A core can only be relocated at full HP");
                }
                Optional<CoreRecord> nearby = findDistanceConflict(
                        connection,
                        worldId,
                        blockX,
                        blockZ,
                        minimumCoreDistance,
                        coreId);
                if (nearby.isPresent()) {
                    throw new PersistenceConflictException(
                            "Core position is too close to core " + nearby.orElseThrow().id());
                }
                CoreRecord updated = new CoreRecord(
                        core.id(),
                        core.teamId(),
                        worldId,
                        blockX,
                        blockY,
                        blockZ,
                        core.currentHitPoints(),
                        core.maximumHitPoints(),
                        core.createdAt(),
                        relocatedAt);
                updateCorePosition(connection, updated);
                insertManagementOperation(
                        connection,
                        operationId,
                        "CORE",
                        coreId,
                        "CORE_RELOCATE",
                        fingerprint,
                        relocatedAt);
                return new CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(updated));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The core position conflicts with persisted data", exception);
            }
            throw failure("relocate a core", exception);
        }
    }

    /** Rebuilds a destroyed core in place as a new full-health placement. */
    public CoreMutationResult rebuildCore(
            UUID coreId,
            UUID actorId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            long maximumHitPoints,
            double minimumCoreDistance,
            UUID operationId,
            Instant rebuiltAt) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rebuiltAt, "rebuiltAt");
        if (maximumHitPoints <= 0L) {
            throw new IllegalArgumentException("maximumHitPoints must be positive");
        }
        requireDistance(minimumCoreDistance);
        String fingerprint = managementFingerprint(
                "CORE_REBUILD",
                coreId,
                actorId,
                worldId,
                blockX,
                blockY,
                blockZ,
                maximumHitPoints,
                minimumCoreDistance);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ManagementOperation> existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            coreId,
                            "CORE_REBUILD",
                            fingerprint);
                    return new CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, coreId));
                }
                requireNoActiveEvent(connection, "rebuild a core");
                CoreRecord core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() != 0L) {
                    throw new PersistenceConflictException(
                            "Only a destroyed core can be rebuilt");
                }
                Optional<CoreRecord> nearby = findDistanceConflict(
                        connection,
                        worldId,
                        blockX,
                        blockZ,
                        minimumCoreDistance,
                        coreId);
                if (nearby.isPresent()) {
                    throw new PersistenceConflictException(
                            "Core position is too close to core " + nearby.orElseThrow().id());
                }
                CoreRecord updated = new CoreRecord(
                        core.id(),
                        core.teamId(),
                        worldId,
                        blockX,
                        blockY,
                        blockZ,
                        maximumHitPoints,
                        maximumHitPoints,
                        rebuiltAt,
                        rebuiltAt);
                updateCore(connection, updated);
                insertManagementOperation(
                        connection,
                        operationId,
                        "CORE",
                        coreId,
                        "CORE_REBUILD",
                        fingerprint,
                        rebuiltAt);
                return new CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(updated));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The rebuilt core conflicts with persisted data", exception);
            }
            throw failure("rebuild a core", exception);
        }
    }

    /**
     * Atomically inserts the complete start snapshot and acquires the global DB lock. Nothing is
     * inserted when another event owns the lock.
     */
    public StartOutcome tryStart(StartRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return database.inImmediateTransaction(connection -> {
                if (loadActiveEventId(connection).isPresent()) {
                    return StartOutcome.LOCKED;
                }

                DefenseSessionSnapshot snapshot = request.session();
                if (eventExists(connection, snapshot.eventId())) {
                    throw new PersistenceConflictException(
                            "Event " + snapshot.eventId() + " already exists");
                }
                CoreRecord core = loadCore(connection, request.coreId()).orElseThrow(
                        () -> new PersistenceConflictException(
                                "Core " + request.coreId() + " does not exist"));
                validateStartCore(snapshot, core);

                insertEvent(connection, request, core);
                replaceParticipants(connection, snapshot, request.startedAt());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO event_lock(singleton, event_id, acquired_at)
                        VALUES (1, ?, ?)
                        """)) {
                    statement.setString(1, snapshot.eventId().toString());
                    statement.setString(2, request.startedAt().toString());
                    statement.executeUpdate();
                }
                RaidSealRepository.consumeForStart(connection, request);
                return StartOutcome.STARTED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The event start conflicts with persisted data", exception);
            }
            throw failure("start a defense event", exception);
        }
    }

    public Optional<UUID> activeEventId() {
        return read("load the active event lock", DefenseRepository::loadActiveEventId);
    }

    public Optional<StoredDefenseEvent> findEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load a defense event", connection -> loadEvent(connection, eventId));
    }

    /** Loads every non-terminal event which requires runtime resume or technical recovery. */
    public List<StoredDefenseEvent> loadUnfinishedEvents() {
        return read("load unfinished defense events", connection -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT event_id FROM defense_events WHERE state NOT IN "
                            + TERMINAL_PHASES_SQL + " ORDER BY started_at, event_id");
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(uuid(resultSet.getString("event_id")));
                }
            }

            List<StoredDefenseEvent> events = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                events.add(loadEvent(connection, id).orElseThrow(
                        () -> new PersistenceException(
                                "An unfinished event disappeared while loading", null)));
            }
            return List.copyOf(events);
        });
    }

    /** Persists an in-phase aggregate snapshot without appending a lifecycle transition. */
    public OperationOutcome saveSnapshot(
            DefenseSessionSnapshot snapshot, Instant updatedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        return saveSnapshot(snapshot, currentRevision(snapshot.eventId()), updatedAt);
    }

    /**
     * Persists an in-phase snapshot only when {@code expectedRevision} still names the durable
     * event revision read by the caller.
     */
    public OperationOutcome saveSnapshot(
            DefenseSessionSnapshot snapshot, long expectedRevision, Instant updatedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requireRevision(expectedRevision);
        if (snapshot.phase().isTerminal()) {
            throw new IllegalArgumentException(
                    "Terminal snapshots must be persisted with finishEvent or recoverUnfinishedEvent");
        }

        try {
            return database.inImmediateTransaction(connection -> {
                StoredDefenseEvent current = requireEvent(connection, snapshot.eventId());
                if (current.session().phase().isTerminal()) {
                    return OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                ensureSameSession(current.session(), snapshot);
                if (current.session().phase() != snapshot.phase()) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                if (!isPermittedInPhaseUpdate(current.session(), snapshot)) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                if (!persistSnapshot(
                        connection,
                        current.coreId(),
                        snapshot,
                        expectedRevision,
                        updatedAt)) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                replaceParticipants(connection, snapshot, updatedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("save a defense snapshot", exception);
        }
    }

    /** Appends and applies one non-terminal lifecycle transition exactly once. */
    public OperationOutcome saveTransition(
            DefenseSessionSnapshot snapshot, UUID operationId, Instant occurredAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(operationId, "operationId");
        long expectedRevision = operationExpectedRevisionOrCurrent(
                snapshot.eventId(), operationId);
        return saveTransition(snapshot, expectedRevision, operationId, occurredAt);
    }

    /** Appends one lifecycle transition using an operation-bound revision CAS. */
    public OperationOutcome saveTransition(
            DefenseSessionSnapshot snapshot,
            long expectedRevision,
            UUID operationId,
            Instant occurredAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireRevision(expectedRevision);
        if (snapshot.phase().isTerminal()) {
            throw new IllegalArgumentException(
                    "Terminal transitions must be persisted with finishEvent or recoverUnfinishedEvent");
        }

        long targetRevision = nextRevision(expectedRevision);
        String payloadFingerprint = payloadFingerprint(snapshot);

        try {
            return database.inImmediateTransaction(connection -> {
                Optional<OperationRow> existingOperation = loadOperation(connection, operationId);
                if (existingOperation.isPresent()) {
                    requireMatchingOperation(
                            existingOperation.orElseThrow(),
                            snapshot.eventId(),
                            OperationKind.TRANSITION,
                            targetRevision,
                            payloadFingerprint);
                    return OperationOutcome.ALREADY_APPLIED;
                }

                StoredDefenseEvent current = requireEvent(connection, snapshot.eventId());
                DefensePhase from = current.session().phase();
                if (from.isTerminal()) {
                    return OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                ensureSameSession(current.session(), snapshot);
                if (!from.canTransitionTo(snapshot.phase())) {
                    return OperationOutcome.STATE_MISMATCH;
                }

                if (!persistSnapshot(
                        connection,
                        current.coreId(),
                        snapshot,
                        expectedRevision,
                        occurredAt)) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                replaceParticipants(connection, snapshot, occurredAt);
                insertOperation(
                        connection,
                        operationId,
                        snapshot.eventId(),
                        OperationKind.TRANSITION,
                        targetRevision,
                        payloadFingerprint,
                        occurredAt);
                insertTransition(connection, operationId, from, snapshot, occurredAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The transition operation UUID conflicts with persisted data", exception);
            }
            throw failure("save a defense transition", exception);
        }
    }

    /**
     * Persists a normal terminal aggregate, final core HP, and global-lock release exactly once.
     */
    public OperationOutcome finishEvent(
            DefenseSessionSnapshot terminalSnapshot,
            UUID operationId,
            Instant occurredAt) {
        Objects.requireNonNull(terminalSnapshot, "terminalSnapshot");
        Objects.requireNonNull(operationId, "operationId");
        long expectedRevision = operationExpectedRevisionOrCurrent(
                terminalSnapshot.eventId(), operationId);
        return finishEvent(terminalSnapshot, expectedRevision, operationId, occurredAt);
    }

    /** Persists a normal terminal aggregate using an operation-bound revision CAS. */
    public OperationOutcome finishEvent(
            DefenseSessionSnapshot terminalSnapshot,
            long expectedRevision,
            UUID operationId,
            Instant occurredAt) {
        Objects.requireNonNull(terminalSnapshot, "terminalSnapshot");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireRevision(expectedRevision);
        if (!terminalSnapshot.phase().isTerminal()
                || terminalSnapshot.phase() == DefensePhase.RECOVERY) {
            throw new IllegalArgumentException(
                    "finishEvent requires VICTORY, DEFEAT, or ABORTED");
        }
        return finish(
                terminalSnapshot,
                expectedRevision,
                operationId,
                occurredAt,
                OperationKind.TERMINATE);
    }

    /**
     * Atomically turns an unfinished event into a completed technical RECOVERY, restores the
     * core's start HP snapshot, records the operation, and releases the global lock. Replaying the
     * same operation UUID is harmless.
     */
    public OperationOutcome recoverUnfinishedEvent(
            UUID eventId, UUID operationId, Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        long expectedRevision = operationExpectedRevisionOrCurrent(eventId, operationId);
        return recoverUnfinishedEvent(eventId, expectedRevision, operationId, occurredAt);
    }

    /** Completes technical recovery using an operation-bound revision CAS. */
    public OperationOutcome recoverUnfinishedEvent(
            UUID eventId,
            long expectedRevision,
            UUID operationId,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireRevision(expectedRevision);
        long targetRevision = nextRevision(expectedRevision);

        try {
            return database.inImmediateTransaction(connection -> {
                StoredDefenseEvent current = requireEvent(connection, eventId);
                DefenseSessionSnapshot recovery = recoverySnapshot(current);
                String payloadFingerprint = payloadFingerprint(recovery);
                Optional<OperationRow> existingOperation = loadOperation(connection, operationId);
                if (existingOperation.isPresent()) {
                    requireMatchingOperation(
                            existingOperation.orElseThrow(),
                            eventId,
                            OperationKind.RECOVER,
                            targetRevision,
                            payloadFingerprint);
                    return OperationOutcome.ALREADY_APPLIED;
                }

                if (current.session().phase().isTerminal()) {
                    return OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                Optional<UUID> lockOwner = loadActiveEventId(connection);
                if (lockOwner.isPresent() && !lockOwner.orElseThrow().equals(eventId)) {
                    throw new PersistenceException(
                            "Cannot recover an event while another event owns the global lock",
                            null);
                }
                if (BlockChangeRepository.hasUnresolved(connection, eventId)) {
                    throw new PersistenceConflictException(
                            "Block changes must be rolled back or marked as conflicts before event recovery");
                }

                if (!persistSnapshot(
                        connection,
                        current.coreId(),
                        recovery,
                        expectedRevision,
                        occurredAt)) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                replaceParticipants(connection, recovery, occurredAt);
                markEnemiesRecoveryRemoved(connection, eventId, occurredAt);
                EscrowRepository.voidForRecovery(
                        connection, eventId, operationId, occurredAt);
                RaidSealRepository.refundIfPresent(
                        connection, eventId, operationId, occurredAt);
                markTerminal(connection, eventId, operationId, occurredAt);
                insertOperation(
                        connection,
                        operationId,
                        eventId,
                        OperationKind.RECOVER,
                        targetRevision,
                        payloadFingerprint,
                        occurredAt);
                insertTransition(
                        connection,
                        operationId,
                        current.session().phase(),
                        recovery,
                        occurredAt);
                releaseEventLock(connection, eventId);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The recovery operation UUID conflicts with persisted data", exception);
            }
            throw failure("recover an unfinished defense event", exception);
        }
    }

    /** Inserts or updates one logical enemy ledger entry. */
    public void upsertEnemy(EnemyLedgerEntry enemy) {
        Objects.requireNonNull(enemy, "enemy");
        try {
            database.inImmediateTransaction(connection -> {
                StoredDefenseEvent event = requireEvent(connection, enemy.eventId());
                if (event.session().phase().isTerminal()) {
                    throw new IllegalStateException("Cannot mutate enemies of a terminal event");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO event_enemies(
                            event_id, enemy_id, entity_id, enemy_type, wave_index, status,
                            snapshot, snapshot_version, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(event_id, enemy_id) DO UPDATE SET
                            entity_id = excluded.entity_id,
                            enemy_type = excluded.enemy_type,
                            wave_index = excluded.wave_index,
                            status = excluded.status,
                            snapshot = excluded.snapshot,
                            snapshot_version = excluded.snapshot_version,
                            updated_at = excluded.updated_at
                        """)) {
                    statement.setString(1, enemy.eventId().toString());
                    statement.setString(2, enemy.enemyId().toString());
                    statement.setString(3, enemy.entityId().toString());
                    statement.setString(4, enemy.enemyType());
                    statement.setInt(5, enemy.waveIndex());
                    statement.setString(6, enemy.status().name());
                    statement.setString(7, enemy.snapshot());
                    statement.setInt(8, enemy.snapshotVersion());
                    statement.setString(9, enemy.updatedAt().toString());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The enemy ledger entry conflicts with persisted identity data", exception);
            }
            throw failure("save an enemy ledger entry", exception);
        }
    }

    /**
     * Updates only the lifecycle status of an existing logical enemy. The physical entity UUID is
     * part of the compare key so a stale entity callback cannot mutate a respawned ledger row.
     */
    public void updateEnemyStatus(
            UUID eventId,
            UUID enemyId,
            UUID entityId,
            EnemyStatus status,
            Instant updatedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(enemyId, "enemyId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        try {
            database.inImmediateTransaction(connection -> {
                StoredDefenseEvent event = requireEvent(connection, eventId);
                if (event.session().phase().isTerminal()) {
                    throw new IllegalStateException("Cannot mutate enemies of a terminal event");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_enemies
                        SET status = ?, updated_at = ?
                        WHERE event_id = ? AND enemy_id = ? AND entity_id = ?
                        """)) {
                    statement.setString(1, status.name());
                    statement.setString(2, updatedAt.toString());
                    statement.setString(3, eventId.toString());
                    statement.setString(4, enemyId.toString());
                    statement.setString(5, entityId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "Enemy identity does not match an existing ledger row");
                    }
                }
                return null;
            });
        } catch (SQLException exception) {
            throw failure("update an enemy ledger status", exception);
        }
    }

    public List<EnemyLedgerEntry> loadEnemyLedger(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load an enemy ledger", connection -> {
            List<EnemyLedgerEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT event_id, enemy_id, entity_id, enemy_type, wave_index, status,
                           snapshot, snapshot_version, updated_at
                    FROM event_enemies
                    WHERE event_id = ?
                    ORDER BY wave_index, enemy_id
                    """)) {
                statement.setString(1, eventId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(new EnemyLedgerEntry(
                                uuid(resultSet.getString("event_id")),
                                uuid(resultSet.getString("enemy_id")),
                                uuid(resultSet.getString("entity_id")),
                                resultSet.getString("enemy_type"),
                                resultSet.getInt("wave_index"),
                                EnemyStatus.valueOf(resultSet.getString("status")),
                                resultSet.getString("snapshot"),
                                resultSet.getInt("snapshot_version"),
                                instant(resultSet.getString("updated_at"))));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }

    public List<EventTransitionRecord> loadTransitions(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load event transitions", connection -> {
            List<EventTransitionRecord> transitions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT sequence, event_id, operation_id, from_state, to_state,
                           wave_index, pending_enemies, alive_enemies, occurred_at
                    FROM event_transitions
                    WHERE event_id = ?
                    ORDER BY sequence
                    """)) {
                statement.setString(1, eventId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        transitions.add(new EventTransitionRecord(
                                resultSet.getLong("sequence"),
                                uuid(resultSet.getString("event_id")),
                                uuid(resultSet.getString("operation_id")),
                                DefensePhase.valueOf(resultSet.getString("from_state")),
                                DefensePhase.valueOf(resultSet.getString("to_state")),
                                resultSet.getInt("wave_index"),
                                resultSet.getLong("pending_enemies"),
                                resultSet.getLong("alive_enemies"),
                                instant(resultSet.getString("occurred_at"))));
                    }
                }
            }
            return List.copyOf(transitions);
        });
    }

    private OperationOutcome finish(
            DefenseSessionSnapshot terminalSnapshot,
            long expectedRevision,
            UUID operationId,
            Instant occurredAt,
            OperationKind operationKind) {
        long targetRevision = nextRevision(expectedRevision);
        String payloadFingerprint = payloadFingerprint(terminalSnapshot);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<OperationRow> existingOperation = loadOperation(connection, operationId);
                if (existingOperation.isPresent()) {
                    requireMatchingOperation(
                            existingOperation.orElseThrow(),
                            terminalSnapshot.eventId(),
                            operationKind,
                            targetRevision,
                            payloadFingerprint);
                    return OperationOutcome.ALREADY_APPLIED;
                }

                StoredDefenseEvent current = requireEvent(
                        connection, terminalSnapshot.eventId());
                DefensePhase from = current.session().phase();
                if (from.isTerminal()) {
                    return OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                ensureSameSession(current.session(), terminalSnapshot);
                if (!from.canTransitionTo(terminalSnapshot.phase())) {
                    return OperationOutcome.STATE_MISMATCH;
                }

                BlockChangeRepository.settleAppliedEventBlocks(
                        connection,
                        terminalSnapshot.eventId(),
                        operationId,
                        occurredAt);
                if (!persistSnapshot(
                        connection,
                        current.coreId(),
                        terminalSnapshot,
                        expectedRevision,
                        occurredAt)) {
                    return OperationOutcome.STATE_MISMATCH;
                }
                replaceParticipants(connection, terminalSnapshot, occurredAt);
                EscrowRepository.settleForTerminal(
                        connection,
                        terminalSnapshot.eventId(),
                        operationId,
                        terminalSnapshot.phase(),
                        occurredAt);
                markEnemiesDespawned(
                        connection, terminalSnapshot.eventId(), occurredAt);
                markTerminal(
                        connection, terminalSnapshot.eventId(), operationId, occurredAt);
                insertOperation(
                        connection,
                        operationId,
                        terminalSnapshot.eventId(),
                        operationKind,
                        targetRevision,
                        payloadFingerprint,
                        occurredAt);
                insertTransition(
                        connection, operationId, from, terminalSnapshot, occurredAt);
                releaseEventLock(connection, terminalSnapshot.eventId());
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The terminal operation UUID conflicts with persisted data", exception);
            }
            throw failure("finish a defense event", exception);
        }
    }

    private static TeamRecord teamFromRow(Connection connection, ResultSet resultSet)
            throws SQLException {
        UUID teamId = uuid(resultSet.getString("team_id"));
        UUID ownerId = uuid(resultSet.getString("owner_player_id"));
        Instant createdAt = instant(resultSet.getString("created_at"));
        Set<UUID> members = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id FROM team_members WHERE team_id = ? ORDER BY player_id
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet membersResult = statement.executeQuery()) {
                while (membersResult.next()) {
                    members.add(uuid(membersResult.getString("player_id")));
                }
            }
        }
        return new TeamRecord(teamId, ownerId, members, createdAt);
    }

    private static Optional<TeamRecord> loadTeam(Connection connection, UUID teamId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, owner_player_id, created_at FROM teams WHERE team_id = ?
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(teamFromRow(connection, resultSet));
            }
        }
    }

    private static TeamRecord requireTeam(Connection connection, UUID teamId)
            throws SQLException {
        return loadTeam(connection, teamId).orElseThrow(
                () -> new PersistenceConflictException("Team " + teamId + " does not exist"));
    }

    private static CoreRecord requireCore(Connection connection, UUID coreId)
            throws SQLException {
        return loadCore(connection, coreId).orElseThrow(
                () -> new PersistenceConflictException("Core " + coreId + " does not exist"));
    }

    private static void requireTeamOwner(TeamRecord team, UUID actorId) {
        if (!team.ownerId().equals(actorId)) {
            throw new PersistenceConflictException(
                    "Only the team owner may perform this operation");
        }
    }

    private static void requireTeamMember(
            Connection connection, UUID teamId, UUID playerId) throws SQLException {
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

    private static void requireNoActiveEvent(Connection connection, String operation)
            throws SQLException {
        if (loadActiveEventId(connection).isPresent()) {
            throw new PersistenceConflictException(
                    "Cannot " + operation + " while a defense event owns the global lock");
        }
    }

    private static void requireTeamCanBeDeleted(Connection connection, UUID teamId)
            throws SQLException {
        if (loadCoreByTeam(connection, teamId).isPresent()) {
            throw new PersistenceConflictException(
                    "A team with a core cannot be disbanded or left by its sole owner");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM defense_events WHERE team_id = ? LIMIT 1
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new PersistenceConflictException(
                            "A team with defense history cannot be deleted");
                }
            }
        }
    }

    private static void deleteTeam(Connection connection, UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM teams WHERE team_id = ?")) {
            statement.setString(1, teamId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The team delete affected no rows");
            }
        }
    }

    private static void insertManagementOperation(
            Connection connection,
            UUID operationId,
            String resourceType,
            UUID resourceId,
            String operationKind,
            String payloadFingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO management_operations(
                    operation_id, resource_type, resource_id, operation_kind,
                    payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, resourceType);
            statement.setString(3, resourceId.toString());
            statement.setString(4, operationKind);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<ManagementOperation> loadManagementOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource_type, resource_id, operation_kind, payload_fingerprint
                FROM management_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ManagementOperation(
                        resultSet.getString("resource_type"),
                        uuid(resultSet.getString("resource_id")),
                        resultSet.getString("operation_kind"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingManagementOperation(
            ManagementOperation operation,
            String resourceType,
            UUID resourceId,
            String operationKind,
            String payloadFingerprint) {
        if (!operation.resourceType().equals(resourceType)
                || !operation.resourceId().equals(resourceId)
                || !operation.operationKind().equals(operationKind)
                || !operation.payloadFingerprint().equals(payloadFingerprint)) {
            throw new PersistenceConflictException(
                    "The management operation UUID is already assigned to a different payload");
        }
    }

    private static String managementFingerprint(String operationKind, Object... values) {
        StringBuilder canonical = new StringBuilder(operationKind);
        canonical.append('|');
        for (Object value : values) {
            canonical.append(Objects.requireNonNull(value, "management fingerprint value"));
            canonical.append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime must provide SHA-256", exception);
        }
    }

    private static CoreRecord placeCore(
            Connection connection, CoreRecord core, double minimumCoreDistance)
            throws SQLException {
        requireNoActiveEvent(connection, "place a core");
        if (loadCoreByTeam(connection, core.teamId()).isPresent()) {
            throw new PersistenceConflictException(
                    "Team " + core.teamId() + " already owns a core");
        }
        Optional<CoreRecord> nearby = findDistanceConflict(
                connection,
                core.worldId(),
                core.blockX(),
                core.blockZ(),
                minimumCoreDistance,
                null);
        if (nearby.isPresent()) {
            throw new PersistenceConflictException(
                    "Core position is too close to core " + nearby.orElseThrow().id());
        }
        insertCore(connection, core);
        return core;
    }

    private static void updateCoreHealth(Connection connection, CoreRecord core)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cores SET current_hp = ?, updated_at = ? WHERE core_id = ?
                """)) {
            statement.setLong(1, core.currentHitPoints());
            statement.setString(2, core.updatedAt().toString());
            statement.setString(3, core.id().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The core health update affected no rows");
            }
        }
    }

    private static void updateCorePosition(Connection connection, CoreRecord core)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cores
                SET world_id = ?, block_x = ?, block_y = ?, block_z = ?, updated_at = ?
                WHERE core_id = ?
                """)) {
            statement.setString(1, core.worldId().toString());
            statement.setInt(2, core.blockX());
            statement.setInt(3, core.blockY());
            statement.setInt(4, core.blockZ());
            statement.setString(5, core.updatedAt().toString());
            statement.setString(6, core.id().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The core position update affected no rows");
            }
        }
    }

    private static void updateCore(Connection connection, CoreRecord core) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cores
                SET team_id = ?, world_id = ?, block_x = ?, block_y = ?, block_z = ?,
                    current_hp = ?, max_hp = ?, created_at = ?, updated_at = ?
                WHERE core_id = ?
                """)) {
            statement.setString(1, core.teamId().toString());
            statement.setString(2, core.worldId().toString());
            statement.setInt(3, core.blockX());
            statement.setInt(4, core.blockY());
            statement.setInt(5, core.blockZ());
            statement.setLong(6, core.currentHitPoints());
            statement.setLong(7, core.maximumHitPoints());
            statement.setString(8, core.createdAt().toString());
            statement.setString(9, core.updatedAt().toString());
            statement.setString(10, core.id().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The core rebuild update affected no rows");
            }
        }
    }

    private static void insertCore(Connection connection, CoreRecord core) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cores(
                    core_id, team_id, world_id, block_x, block_y, block_z,
                    current_hp, max_hp, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, core.id().toString());
            statement.setString(2, core.teamId().toString());
            statement.setString(3, core.worldId().toString());
            statement.setInt(4, core.blockX());
            statement.setInt(5, core.blockY());
            statement.setInt(6, core.blockZ());
            statement.setLong(7, core.currentHitPoints());
            statement.setLong(8, core.maximumHitPoints());
            statement.setString(9, core.createdAt().toString());
            statement.setString(10, core.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    private static Optional<CoreRecord> loadCore(Connection connection, UUID coreId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT core_id, team_id, world_id, block_x, block_y, block_z,
                       current_hp, max_hp, created_at, updated_at
                FROM cores WHERE core_id = ?
                """)) {
            statement.setString(1, coreId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(coreFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<CoreRecord> loadCoreByTeam(Connection connection, UUID teamId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT core_id, team_id, world_id, block_x, block_y, block_z,
                       current_hp, max_hp, created_at, updated_at
                FROM cores WHERE team_id = ?
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(coreFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<CoreRecord> findDistanceConflict(
            Connection connection,
            UUID worldId,
            int blockX,
            int blockZ,
            double minimumCoreDistance) throws SQLException {
        return findDistanceConflict(
                connection, worldId, blockX, blockZ, minimumCoreDistance, null);
    }

    private static Optional<CoreRecord> findDistanceConflict(
            Connection connection,
            UUID worldId,
            int blockX,
            int blockZ,
            double minimumCoreDistance,
            UUID excludedCoreId) throws SQLException {
        if (minimumCoreDistance == 0.0D) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT core_id, team_id, world_id, block_x, block_y, block_z,
                       current_hp, max_hp, created_at, updated_at
                FROM cores
                WHERE world_id = ?
                  AND (? IS NULL OR core_id <> ?)
                  AND (
                      CAST(block_x - ? AS REAL) * CAST(block_x - ? AS REAL)
                      + CAST(block_z - ? AS REAL) * CAST(block_z - ? AS REAL)
                  ) < ?
                ORDER BY core_id
                LIMIT 1
                """)) {
            statement.setString(1, worldId.toString());
            if (excludedCoreId == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, excludedCoreId.toString());
                statement.setString(3, excludedCoreId.toString());
            }
            statement.setInt(4, blockX);
            statement.setInt(5, blockX);
            statement.setInt(6, blockZ);
            statement.setInt(7, blockZ);
            statement.setDouble(8, minimumCoreDistance * minimumCoreDistance);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(coreFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static CoreRecord coreFromRow(ResultSet resultSet) throws SQLException {
        return new CoreRecord(
                uuid(resultSet.getString("core_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("world_id")),
                resultSet.getInt("block_x"),
                resultSet.getInt("block_y"),
                resultSet.getInt("block_z"),
                resultSet.getLong("current_hp"),
                resultSet.getLong("max_hp"),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")));
    }

    private static void insertEvent(
            Connection connection, StartRequest request, CoreRecord core) throws SQLException {
        DefenseSessionSnapshot snapshot = request.session();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO defense_events(
                    event_id, team_id, core_id, state, stage_level, total_waves,
                    participant_limit, participants_frozen, wave_index, pending_enemies,
                    alive_enemies, start_core_hp, start_core_max_hp, core_hp, core_max_hp,
                    core_present, core_world_id, core_block_x, core_block_y, core_block_z,
                    config_snapshot, config_version, started_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshot.eventId().toString());
            statement.setString(2, snapshot.teamId().toString());
            statement.setString(3, core.id().toString());
            statement.setString(4, snapshot.phase().name());
            statement.setLong(5, snapshot.stageLevel());
            statement.setInt(6, snapshot.totalWaves());
            statement.setInt(7, snapshot.participantLimit());
            statement.setInt(8, snapshot.participantsFrozen() ? 1 : 0);
            statement.setInt(9, snapshot.currentWave());
            statement.setLong(10, snapshot.pendingEnemies());
            statement.setLong(11, snapshot.aliveEnemies());
            statement.setLong(12, core.currentHitPoints());
            statement.setLong(13, core.maximumHitPoints());
            statement.setLong(14, snapshot.coreState().currentHitPoints());
            statement.setLong(15, snapshot.coreState().maximumHitPoints());
            statement.setInt(16, snapshot.coreState().present() ? 1 : 0);
            statement.setString(17, core.worldId().toString());
            statement.setInt(18, core.blockX());
            statement.setInt(19, core.blockY());
            statement.setInt(20, core.blockZ());
            statement.setString(21, request.configSnapshot());
            statement.setInt(22, request.configVersion());
            statement.setString(23, request.startedAt().toString());
            statement.setString(24, request.startedAt().toString());
            statement.executeUpdate();
        }
    }

    private static Optional<StoredDefenseEvent> loadEvent(Connection connection, UUID eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM defense_events WHERE event_id = ?
                """)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                ParticipantSets participants = loadParticipants(connection, eventId);
                DefenseSessionSnapshot snapshot = new DefenseSessionSnapshot(
                        eventId,
                        uuid(resultSet.getString("team_id")),
                        resultSet.getLong("stage_level"),
                        resultSet.getInt("total_waves"),
                        resultSet.getInt("participant_limit"),
                        DefensePhase.valueOf(resultSet.getString("state")),
                        resultSet.getInt("wave_index"),
                        participants.registered(),
                        participants.effective(),
                        resultSet.getInt("participants_frozen") != 0,
                        resultSet.getLong("pending_enemies"),
                        resultSet.getLong("alive_enemies"),
                        new CoreState(
                                resultSet.getLong("core_max_hp"),
                                resultSet.getLong("core_hp"),
                                resultSet.getInt("core_present") != 0));
                String terminalOperation = resultSet.getString("terminal_operation_id");
                String terminalAt = resultSet.getString("terminal_at");
                return Optional.of(new StoredDefenseEvent(
                        snapshot,
                        uuid(resultSet.getString("core_id")),
                        uuid(resultSet.getString("core_world_id")),
                        resultSet.getInt("core_block_x"),
                        resultSet.getInt("core_block_y"),
                        resultSet.getInt("core_block_z"),
                        resultSet.getLong("start_core_hp"),
                        resultSet.getLong("start_core_max_hp"),
                        resultSet.getString("config_snapshot"),
                        resultSet.getInt("config_version"),
                        instant(resultSet.getString("started_at")),
                        instant(resultSet.getString("updated_at")),
                        resultSet.getLong("revision"),
                        terminalOperation == null
                                ? Optional.empty()
                                : Optional.of(uuid(terminalOperation)),
                        terminalAt == null
                                ? Optional.empty()
                                : Optional.of(instant(terminalAt))));
            }
        }
    }

    private static StoredDefenseEvent requireEvent(Connection connection, UUID eventId)
            throws SQLException {
        return loadEvent(connection, eventId).orElseThrow(
                () -> new IllegalArgumentException("Unknown defense event " + eventId));
    }

    private static ParticipantSets loadParticipants(Connection connection, UUID eventId)
            throws SQLException {
        Set<UUID> registered = new LinkedHashSet<>();
        Set<UUID> effective = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id, registered, effective
                FROM event_participants
                WHERE event_id = ?
                ORDER BY player_id
                """)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID playerId = uuid(resultSet.getString("player_id"));
                    if (resultSet.getInt("registered") != 0) {
                        registered.add(playerId);
                    }
                    if (resultSet.getInt("effective") != 0) {
                        effective.add(playerId);
                    }
                }
            }
        }
        return new ParticipantSets(Set.copyOf(registered), Set.copyOf(effective));
    }

    private static void replaceParticipants(
            Connection connection,
            DefenseSessionSnapshot snapshot,
            Instant changedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_participants(
                    event_id, player_id, registered, effective, joined_at
                ) VALUES (?, ?, ?, 1, ?)
                ON CONFLICT(event_id, player_id) DO UPDATE SET
                    registered = excluded.registered,
                    effective = 1,
                    joined_at = excluded.joined_at
                """)) {
            for (UUID playerId : snapshot.effectiveParticipants()) {
                statement.setString(1, snapshot.eventId().toString());
                statement.setString(2, playerId.toString());
                statement.setInt(
                        3, snapshot.registeredParticipants().contains(playerId) ? 1 : 0);
                statement.setString(4, changedAt.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static boolean persistSnapshot(
            Connection connection,
            UUID coreId,
            DefenseSessionSnapshot snapshot,
            long expectedRevision,
            Instant updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE defense_events
                SET state = ?, stage_level = ?, total_waves = ?, participant_limit = ?,
                    participants_frozen = ?, wave_index = ?, pending_enemies = ?,
                    alive_enemies = ?, core_hp = ?, core_max_hp = ?, core_present = ?,
                    updated_at = ?, revision = revision + 1
                WHERE event_id = ? AND revision = ?
                """)) {
            statement.setString(1, snapshot.phase().name());
            statement.setLong(2, snapshot.stageLevel());
            statement.setInt(3, snapshot.totalWaves());
            statement.setInt(4, snapshot.participantLimit());
            statement.setInt(5, snapshot.participantsFrozen() ? 1 : 0);
            statement.setInt(6, snapshot.currentWave());
            statement.setLong(7, snapshot.pendingEnemies());
            statement.setLong(8, snapshot.aliveEnemies());
            statement.setLong(9, snapshot.coreState().currentHitPoints());
            statement.setLong(10, snapshot.coreState().maximumHitPoints());
            statement.setInt(11, snapshot.coreState().present() ? 1 : 0);
            statement.setString(12, updatedAt.toString());
            statement.setString(13, snapshot.eventId().toString());
            statement.setLong(14, expectedRevision);
            if (statement.executeUpdate() != 1) {
                return false;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cores
                SET current_hp = ?, max_hp = ?, updated_at = ?
                WHERE core_id = ?
                """)) {
            statement.setLong(1, snapshot.coreState().currentHitPoints());
            statement.setLong(2, snapshot.coreState().maximumHitPoints());
            statement.setString(3, updatedAt.toString());
            statement.setString(4, coreId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The event core update affected no rows");
            }
        }
        return true;
    }

    private static void insertTransition(
            Connection connection,
            UUID operationId,
            DefensePhase from,
            DefenseSessionSnapshot snapshot,
            Instant occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_transitions(
                    event_id, operation_id, from_state, to_state, wave_index,
                    pending_enemies, alive_enemies, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshot.eventId().toString());
            statement.setString(2, operationId.toString());
            statement.setString(3, from.name());
            statement.setString(4, snapshot.phase().name());
            statement.setInt(5, snapshot.currentWave());
            statement.setLong(6, snapshot.pendingEnemies());
            statement.setLong(7, snapshot.aliveEnemies());
            statement.setString(8, occurredAt.toString());
            statement.executeUpdate();
        }
    }

    private static void insertOperation(
            Connection connection,
            UUID operationId,
            UUID eventId,
            OperationKind kind,
            long targetRevision,
            String payloadFingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_operations(
                    operation_id, event_id, operation_kind, target_revision,
                    payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, kind.name());
            statement.setLong(4, targetRevision);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<OperationRow> loadOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, operation_kind, target_revision, payload_fingerprint
                FROM event_operations
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OperationRow(
                        uuid(resultSet.getString("event_id")),
                        OperationKind.valueOf(resultSet.getString("operation_kind")),
                        resultSet.getLong("target_revision"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingOperation(
            OperationRow operation,
            UUID eventId,
            OperationKind kind,
            long targetRevision,
            String payloadFingerprint) {
        if (!operation.eventId().equals(eventId)
                || operation.kind() != kind
                || operation.targetRevision() != targetRevision
                || !operation.payloadFingerprint().equals(payloadFingerprint)) {
            throw new PersistenceConflictException(
                    "The operation UUID is already assigned to a different payload or revision");
        }
    }

    private static void markTerminal(
            Connection connection,
            UUID eventId,
            UUID operationId,
            Instant terminalAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE defense_events
                SET terminal_operation_id = ?, terminal_at = ?
                WHERE event_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, terminalAt.toString());
            statement.setString(3, eventId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The terminal event update affected no rows");
            }
        }
    }

    private static void markEnemiesRecoveryRemoved(
            Connection connection, UUID eventId, Instant occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_enemies
                SET status = 'RECOVERY_REMOVED', updated_at = ?
                WHERE event_id = ? AND status IN ('ALLOCATED', 'SPAWNED')
                """)) {
            statement.setString(1, occurredAt.toString());
            statement.setString(2, eventId.toString());
            statement.executeUpdate();
        }
    }

    private static void markEnemiesDespawned(
            Connection connection, UUID eventId, Instant occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_enemies
                SET status = 'DESPAWNED', updated_at = ?
                WHERE event_id = ?
                  AND status NOT IN ('DEAD', 'DESPAWNED', 'RECOVERY_REMOVED')
                """)) {
            statement.setString(1, occurredAt.toString());
            statement.setString(2, eventId.toString());
            statement.executeUpdate();
        }
    }

    private static void releaseEventLock(Connection connection, UUID eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM event_lock WHERE singleton = 1 AND event_id = ?")) {
            statement.setString(1, eventId.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<UUID> loadActiveEventId(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_id FROM event_lock WHERE singleton = 1");
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next()
                    ? Optional.of(uuid(resultSet.getString("event_id")))
                    : Optional.empty();
        }
    }

    private static boolean eventExists(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM defense_events WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static DefenseSessionSnapshot recoverySnapshot(StoredDefenseEvent event) {
        DefenseSessionSnapshot current = event.session();
        return new DefenseSessionSnapshot(
                current.eventId(),
                current.teamId(),
                current.stageLevel(),
                current.totalWaves(),
                current.participantLimit(),
                DefensePhase.RECOVERY,
                current.currentWave(),
                current.registeredParticipants(),
                current.effectiveParticipants(),
                current.participantsFrozen(),
                current.pendingEnemies(),
                current.aliveEnemies(),
                new CoreState(
                        event.startCoreMaximumHitPoints(),
                        event.startCoreHitPoints(),
                        true));
    }

    private static void validateStartCore(
            DefenseSessionSnapshot snapshot, CoreRecord core) {
        if (!snapshot.teamId().equals(core.teamId())) {
            throw new PersistenceConflictException("The selected core belongs to another team");
        }
        if (snapshot.coreState().maximumHitPoints() != core.maximumHitPoints()
                || snapshot.coreState().currentHitPoints() != core.currentHitPoints()) {
            throw new PersistenceConflictException(
                    "The session core snapshot is stale compared with the database");
        }
    }

    private static void ensureSameSession(
            DefenseSessionSnapshot current, DefenseSessionSnapshot next) {
        if (!current.eventId().equals(next.eventId())
                || !current.teamId().equals(next.teamId())
                || current.stageLevel() != next.stageLevel()
                || current.totalWaves() != next.totalWaves()
                || current.participantLimit() != next.participantLimit()
                || current.coreState().maximumHitPoints()
                        != next.coreState().maximumHitPoints()) {
            throw new IllegalArgumentException(
                    "A persisted session's immutable identity and configuration cannot change");
        }
    }

    private static boolean isPermittedInPhaseUpdate(
            DefenseSessionSnapshot current, DefenseSessionSnapshot next) {
        if (current.currentWave() != next.currentWave()
                || !current.registeredParticipants().equals(next.registeredParticipants())
                || !next.effectiveParticipants().containsAll(current.effectiveParticipants())) {
            return false;
        }
        return next.coreState().currentHitPoints()
                <= current.coreState().currentHitPoints();
    }

    private static void requireDistance(double minimumCoreDistance) {
        if (!Double.isFinite(minimumCoreDistance) || minimumCoreDistance < 0.0D) {
            throw new IllegalArgumentException(
                    "minimumCoreDistance must be a finite, non-negative number");
        }
    }

    private long currentRevision(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read(
                "load the current event revision",
                connection -> requireEvent(connection, eventId).revision());
    }

    private long operationExpectedRevisionOrCurrent(UUID eventId, UUID operationId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        return read("load an operation revision", connection -> {
            Optional<OperationRow> operation = loadOperation(connection, operationId);
            if (operation.isEmpty()) {
                return requireEvent(connection, eventId).revision();
            }
            long targetRevision = operation.orElseThrow().targetRevision();
            if (targetRevision <= 0L) {
                throw new PersistenceConflictException(
                        "A legacy operation has no revision binding and cannot be replayed safely");
            }
            return targetRevision - 1L;
        });
    }

    private static void requireRevision(long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    private static long nextRevision(long revision) {
        try {
            return Math.addExact(revision, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("event revision overflow", exception);
        }
    }

    private static String payloadFingerprint(DefenseSessionSnapshot snapshot) {
        StringBuilder canonical = new StringBuilder(512);
        canonical.append(snapshot.eventId()).append('|')
                .append(snapshot.teamId()).append('|')
                .append(snapshot.stageLevel()).append('|')
                .append(snapshot.totalWaves()).append('|')
                .append(snapshot.participantLimit()).append('|')
                .append(snapshot.phase().name()).append('|')
                .append(snapshot.currentWave()).append('|')
                .append(snapshot.participantsFrozen()).append('|')
                .append(snapshot.pendingEnemies()).append('|')
                .append(snapshot.aliveEnemies()).append('|')
                .append(snapshot.coreState().maximumHitPoints()).append('|')
                .append(snapshot.coreState().currentHitPoints()).append('|')
                .append(snapshot.coreState().present()).append('|');
        appendSortedUuids(canonical, snapshot.registeredParticipants());
        canonical.append('|');
        appendSortedUuids(canonical, snapshot.effectiveParticipants());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime must provide SHA-256", exception);
        }
    }

    private static void appendSortedUuids(StringBuilder target, Set<UUID> values) {
        values.stream()
                .map(UUID::toString)
                .sorted()
                .forEach(value -> target.append(value).append(','));
    }

    private <T> T read(String action, Database.SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw failure(action, exception);
        }
    }

    private static PersistenceException failure(String action, SQLException exception) {
        return new PersistenceException("Could not " + action, exception);
    }

    private static boolean isConstraintViolation(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current.getErrorCode() == 19
                    || (current.getMessage() != null
                            && current.getMessage().toLowerCase(java.util.Locale.ROOT)
                                    .contains("constraint"))) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static Instant instant(String value) {
        return Instant.parse(value);
    }

    private record ParticipantSets(Set<UUID> registered, Set<UUID> effective) {
    }

    private record OperationRow(
            UUID eventId,
            OperationKind kind,
            long targetRevision,
            String payloadFingerprint) {
    }

    private record ManagementOperation(
            String resourceType,
            UUID resourceId,
            String operationKind,
            String payloadFingerprint) {
    }

    private enum OperationKind {
        TRANSITION,
        TERMINATE,
        RECOVER
    }
}
