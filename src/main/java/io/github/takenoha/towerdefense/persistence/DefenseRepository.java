package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.config.RewardSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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
    private static final int RESEARCH_CRYSTAL_SEGMENT_SIZE = 64;
    private static final Duration DEFAULT_TEAM_QUEUE_RETENTION = Duration.ofDays(7L);
    public static final int MAX_TEAM_MEMBERS = 8;
    public static final Duration DEFAULT_INVITATION_RETENTION = Duration.ofDays(7L);

    private final Database database;
    private final Duration teamQueueRetention;
    private final RewardSettings rewardSettings;

    public DefenseRepository(Database database) {
        this(database, DEFAULT_TEAM_QUEUE_RETENTION, RewardSettings.defaults());
    }

    public DefenseRepository(Database database, Duration teamQueueRetention) {
        this(database, teamQueueRetention, RewardSettings.defaults());
    }

    /** Uses the configured queue and stage-reward policy. */
    public DefenseRepository(Database database, RewardSettings rewardSettings) {
        this(
                database,
                Objects.requireNonNull(rewardSettings, "rewardSettings").teamQueueRetention(),
                rewardSettings);
    }

    public DefenseRepository(
            Database database,
            Duration teamQueueRetention,
            RewardSettings rewardSettings) {
        this.database = Objects.requireNonNull(database, "database");
        this.teamQueueRetention = Objects.requireNonNull(teamQueueRetention, "teamQueueRetention");
        this.rewardSettings = Objects.requireNonNull(rewardSettings, "rewardSettings");
        if (teamQueueRetention.isZero() || teamQueueRetention.isNegative()) {
            throw new IllegalArgumentException("teamQueueRetention must be positive");
        }
    }

    /** Creates a one-player team and its mandatory owner membership atomically. */
    public TeamRecord createSoloTeam(UUID teamId, UUID ownerPlayerId, Instant createdAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(createdAt, "createdAt");
        try {
            return database.inImmediateTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO teams(team_id, owner_player_id, display_name, created_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, ownerPlayerId.toString());
                    statement.setString(3, TeamRecord.DEFAULT_DISPLAY_NAME);
                    statement.setString(4, createdAt.toString());
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
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO team_progress(
                            team_id, highest_cleared_level, unlocked_level, research_points, updated_at
                        ) VALUES (?, 0, 1, 0, ?)
                        """)) {
                    statement.setString(1, teamId.toString());
                    statement.setString(2, createdAt.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tower_research(team_id, tower_type, research_level, updated_at)
                        VALUES (?, ?, 1, ?)
                        """)) {
                    for (TowerType towerType : TowerType.values()) {
                        statement.setString(1, teamId.toString());
                        statement.setString(2, towerType.id());
                        statement.setString(3, createdAt.toString());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                insertEmptyResourceBalances(connection, teamId, createdAt);
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
            return findTeamByMember(connection, playerId);
        });
    }

    /** Renames a team through an owner-authorized, UUID-idempotent profile mutation. */
    public TeamMutationResult renameTeam(
            UUID teamId,
            UUID actorId,
            String displayName,
            UUID operationId,
            Instant renamedAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        String normalizedName = TeamRecord.normalizeDisplayName(displayName);
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(renamedAt, "renamedAt");
        String fingerprint = managementFingerprint(
                "TEAM_RENAME", teamId, actorId, normalizedName);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TeamProfileOperation> existing = loadTeamProfileOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingTeamProfileOperation(
                            existing.orElseThrow(), teamId, actorId, fingerprint);
                    return new TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "rename a team");
                TeamRecord team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE teams SET display_name = ? WHERE team_id = ?
                        """)) {
                    statement.setString(1, normalizedName);
                    statement.setString(2, teamId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The team rename affected no rows");
                    }
                }
                insertTeamProfileOperation(
                        connection,
                        operationId,
                        teamId,
                        actorId,
                        "TEAM_RENAME",
                        fingerprint,
                        renamedAt);
                return new TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (SQLException exception) {
            throw failure("rename a team", exception);
        }
    }

    /** Lists unexpired invitations and durably expires stale pending rows for this recipient. */
    public List<TeamInvitation> findPendingTeamInvitations(UUID inviteeId, Instant now) {
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(now, "now");
        try {
            return database.inImmediateTransaction(connection -> {
                List<TeamInvitation> invitations = new ArrayList<>();
                List<TeamInvitation> loaded = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT invite_id, team_id, inviter_id, invitee_id, state,
                               created_at, expires_at, resolved_at
                        FROM team_invites
                        WHERE invitee_id = ? AND state = 'PENDING'
                        ORDER BY created_at, invite_id
                        """)) {
                    statement.setString(1, inviteeId.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            loaded.add(teamInvitationFromRow(resultSet));
                        }
                    }
                }
                for (TeamInvitation invitation : loaded) {
                    if (invitation.isPendingAt(now)) {
                        invitations.add(invitation);
                    } else {
                        expireInvitation(connection, invitation.id(), now);
                    }
                }
                return List.copyOf(invitations);
            });
        } catch (SQLException exception) {
            throw failure("load pending team invitations", exception);
        }
    }

    /** Loads one invitation for reconnect-aware status checks and recovery tooling. */
    public Optional<TeamInvitation> findTeamInvitation(UUID invitationId) {
        Objects.requireNonNull(invitationId, "invitationId");
        return read(
                "load a team invitation",
                connection -> loadTeamInvitation(connection, invitationId));
    }

    /** Creates an owner-authorized invitation that remains valid while both players are offline. */
    public TeamInvitationMutationResult createTeamInvitation(
            UUID teamId,
            UUID actorId,
            UUID inviteeId,
            UUID invitationId,
            UUID operationId,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Invitation expiration must be after creation");
        }
        String fingerprint = managementFingerprint(
                "TEAM_INVITE_CREATE",
                teamId,
                actorId,
                inviteeId,
                invitationId,
                expiresAt);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TeamInviteOperation> existing = loadTeamInviteOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingTeamInviteOperation(
                            existing.orElseThrow(),
                            "TEAM_INVITE_CREATE",
                            actorId,
                            fingerprint);
                    TeamInvitation invitation = requireTeamInvitation(
                            connection, existing.orElseThrow().inviteId());
                    return invitationMutation(
                            ManagementOutcome.ALREADY_APPLIED,
                            connection,
                            invitation);
                }
                requireNoActiveEvent(connection, "create a team invitation");
                TeamRecord team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                if (actorId.equals(inviteeId)) {
                    throw new PersistenceConflictException("A team owner cannot invite themselves");
                }
                if (team.members().size() >= MAX_TEAM_MEMBERS) {
                    throw new PersistenceConflictException(
                            "The team has reached the maximum of " + MAX_TEAM_MEMBERS + " members");
                }
                if (findTeamByMember(connection, inviteeId).isPresent()) {
                    throw new PersistenceConflictException(
                            "The invited player already belongs to a team");
                }
                if (hasPendingInvitation(connection, teamId, inviteeId)) {
                    throw new PersistenceConflictException(
                            "This player already has a pending invitation for the team");
                }
                TeamInvitation invitation = new TeamInvitation(
                        invitationId,
                        teamId,
                        actorId,
                        inviteeId,
                        TeamInvitationState.PENDING,
                        createdAt,
                        expiresAt,
                        null);
                insertTeamInvitation(connection, invitation, fingerprint);
                insertTeamInviteOperation(
                        connection,
                        operationId,
                        invitationId,
                        actorId,
                        "TEAM_INVITE_CREATE",
                        fingerprint,
                        createdAt);
                return invitationMutation(ManagementOutcome.APPLIED, connection, invitation);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The invitation conflicts with an existing team or invitation", exception);
            }
            throw failure("create a team invitation", exception);
        }
    }

    /** Accepts a pending invitation for the invited player and adds them atomically. */
    public TeamInvitationMutationResult acceptTeamInvitation(
            UUID invitationId,
            UUID inviteeId,
            UUID operationId,
            Instant acceptedAt) {
        TeamInvitationMutationResult result = resolveTeamInvitation(
                invitationId,
                inviteeId,
                operationId,
                acceptedAt,
                "TEAM_INVITE_ACCEPT",
                true);
        if (result.invitation().state() == TeamInvitationState.EXPIRED) {
            throw new PersistenceConflictException("This invitation has expired");
        }
        return result;
    }

    /** Declines a pending invitation without changing team membership. */
    public TeamInvitationMutationResult declineTeamInvitation(
            UUID invitationId,
            UUID inviteeId,
            UUID operationId,
            Instant declinedAt) {
        TeamInvitationMutationResult result = resolveTeamInvitation(
                invitationId,
                inviteeId,
                operationId,
                declinedAt,
                "TEAM_INVITE_DECLINE",
                false);
        if (result.invitation().state() == TeamInvitationState.EXPIRED) {
            throw new PersistenceConflictException("This invitation has expired");
        }
        return result;
    }

    /** Loads the durable team progression snapshot used by repair quotes and future research. */
    public TeamProgress loadTeamProgress(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return read(
                "load team progression",
                connection -> loadTeamProgress(connection, teamId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "Team " + teamId + " has no progression row")));
    }

    /** Loads one immutable research-crystal issuance batch. */
    public Optional<ResearchCrystalBatch> findResearchCrystalBatch(UUID batchId) {
        Objects.requireNonNull(batchId, "batchId");
        return read(
                "load a research crystal batch",
                connection -> loadResearchCrystalBatch(connection, batchId));
    }

    /** Loads one redemption receipt so the Paper inventory handoff can recover after a restart. */
    public Optional<ResearchCrystalRedemption> findResearchCrystalRedemption(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return read(
                "load a research crystal redemption",
                connection -> loadResearchCrystalRedemption(connection, operationId));
    }

    /**
     * Reserves a team-bound crystal redemption before the Paper inventory item is removed.
     *
     * <p>The returned operation is the durable receipt for the physical handoff. Calling this
     * method again with the same UUID and payload returns the original reservation.</p>
     */
    public ResearchCrystalRedemption prepareResearchCrystalRedemption(
            UUID batchId,
            UUID coreId,
            UUID actorId,
            int quantity,
            UUID operationId,
            Instant preparedAt) {
        return prepareResearchCrystalRedemption(
                batchId,
                coreId,
                actorId,
                null,
                -1,
                null,
                null,
                quantity,
                operationId,
                preparedAt);
    }

    /**
     * Reserves a redemption while validating the PDC team and issuance quantity carried by the
     * physical item.  The compatibility overload above remains for durable records created by
     * earlier plugin versions.
     */
    public ResearchCrystalRedemption prepareResearchCrystalRedemption(
            UUID batchId,
            UUID coreId,
            UUID actorId,
            UUID itemTeamId,
            int itemIssuedQuantity,
            int quantity,
            UUID operationId,
            Instant preparedAt) {
        return prepareResearchCrystalRedemption(
                batchId,
                coreId,
                actorId,
                itemTeamId,
                itemIssuedQuantity,
                null,
                null,
                quantity,
                operationId,
                preparedAt);
    }

    /** Reserves a redemption and, for v2 items, binds it to one issued stack segment. */
    public ResearchCrystalRedemption prepareResearchCrystalRedemption(
            UUID batchId,
            UUID coreId,
            UUID actorId,
            UUID itemTeamId,
            int itemIssuedQuantity,
            Integer itemSegmentOffset,
            Integer itemSegmentQuantity,
            int quantity,
            UUID operationId,
            Instant preparedAt) {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (itemTeamId != null && itemIssuedQuantity <= 0) {
            throw new IllegalArgumentException("itemIssuedQuantity must be positive");
        }
        if ((itemSegmentOffset == null) != (itemSegmentQuantity == null)) {
            throw new IllegalArgumentException(
                    "itemSegmentOffset and itemSegmentQuantity must be supplied together");
        }
        if (itemSegmentOffset != null && itemTeamId == null) {
            throw new IllegalArgumentException(
                    "an issued research crystal segment requires team metadata");
        }
        if (itemSegmentOffset != null
                && (itemSegmentOffset < 0
                        || itemSegmentQuantity <= 0
                        || itemSegmentQuantity > RESEARCH_CRYSTAL_SEGMENT_SIZE)) {
            throw new IllegalArgumentException("research crystal item segment is invalid");
        }
        String fingerprint = itemTeamId == null
                ? crystalRedemptionFingerprint(batchId, coreId, actorId, quantity)
                : crystalRedemptionFingerprint(
                        batchId,
                        coreId,
                        actorId,
                        itemTeamId,
                        itemIssuedQuantity,
                        itemSegmentOffset,
                        itemSegmentQuantity,
                        quantity);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ResearchCrystalRedemption> existing =
                        loadResearchCrystalRedemption(connection, operationId);
                if (existing.isPresent()) {
                    ResearchCrystalRedemption redemption = existing.orElseThrow();
                    requireMatchingCrystalRedemption(
                            redemption,
                            operationId,
                            batchId,
                            coreId,
                            actorId,
                            quantity,
                            fingerprint);
                    return redemption;
                }
                requireNoActiveEvent(connection, "redeem research crystals");
                CoreRecord core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                ResearchCrystalBatch batch = loadResearchCrystalBatch(connection, batchId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "Unknown research crystal batch " + batchId));
                if (!batch.teamId().equals(core.teamId())) {
                    throw new PersistenceConflictException(
                            "Research crystals can only be redeemed at their source team's core");
                }
                if (itemTeamId != null && !batch.teamId().equals(itemTeamId)) {
                    throw new PersistenceConflictException(
                            "The research crystal PDC team does not match its issuance batch");
                }
                if (itemIssuedQuantity > 0 && batch.issuedQuantity() != itemIssuedQuantity) {
                    throw new PersistenceConflictException(
                            "The research crystal PDC issuance quantity is invalid");
                }
                if (itemSegmentOffset != null) {
                    if ((long) itemSegmentOffset + itemSegmentQuantity > batch.issuedQuantity()) {
                        throw new PersistenceConflictException(
                                "The research crystal PDC segment is outside its batch");
                    }
                    ResearchCrystalSegment segment = loadResearchCrystalSegment(
                                    connection, batchId, itemSegmentOffset)
                            .orElseThrow(() -> new PersistenceConflictException(
                                    "The research crystal PDC segment is not issued"));
                    if (segment.segmentQuantity() != itemSegmentQuantity
                            || quantity > segment.remainingQuantity()) {
                        throw new PersistenceConflictException(
                                "The research crystal PDC segment has no remaining quantity");
                    }
                }
                if (batch.status() == ResearchCrystalBatchStatus.VOIDED
                        || quantity > batch.remainingQuantity()) {
                    throw new PersistenceConflictException(
                            "The research crystal batch has no remaining redeemable quantity");
                }
                ResearchCrystalRedemption redemption = new ResearchCrystalRedemption(
                        operationId,
                        batchId,
                        coreId,
                        core.teamId(),
                        actorId,
                        quantity,
                        fingerprint,
                        itemSegmentOffset,
                        itemSegmentQuantity,
                        ResearchCrystalRedemptionState.PREPARED,
                        preparedAt,
                        null,
                        null);
                insertResearchCrystalRedemption(connection, redemption);
                return redemption;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The research crystal redemption conflicts with persisted data", exception);
            }
            throw failure("prepare a research crystal redemption", exception);
        }
    }

    /** Applies a prepared crystal redemption and credits the team's research points atomically. */
    public ResearchCrystalRedemptionResult applyResearchCrystalRedemption(
            UUID operationId,
            Instant appliedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                ResearchCrystalRedemption redemption = loadResearchCrystalRedemption(
                                connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "Unknown research crystal redemption " + operationId));
                if (redemption.state() == ResearchCrystalRedemptionState.APPLIED) {
                    return crystalRedemptionResult(
                            connection, OperationOutcome.ALREADY_APPLIED, redemption.batchId());
                }
                if (redemption.state() == ResearchCrystalRedemptionState.ROLLED_BACK) {
                    throw new PersistenceConflictException(
                            "The research crystal redemption was already rolled back");
                }
                requireNoActiveEvent(connection, "apply a research crystal redemption");
                CoreRecord core = requireCore(connection, redemption.coreId());
                requireTeamMember(connection, core.teamId(), redemption.actorId());
                if (!core.teamId().equals(redemption.teamId())) {
                    throw new PersistenceConflictException(
                            "The redemption team no longer matches the core");
                }
                ResearchCrystalBatch batch = loadResearchCrystalBatch(
                                connection, redemption.batchId())
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The research crystal batch disappeared"));
                if (batch.status() == ResearchCrystalBatchStatus.VOIDED
                        || redemption.quantity() > batch.remainingQuantity()) {
                    throw new PersistenceConflictException(
                            "The research crystal batch was already exhausted or voided");
                }
                if (redemption.segmentOffset() != null) {
                    ResearchCrystalSegment segment = loadResearchCrystalSegment(
                                    connection,
                                    redemption.batchId(),
                                    redemption.segmentOffset())
                            .orElseThrow(() -> new PersistenceConflictException(
                                    "The research crystal redemption segment disappeared"));
                    if (!Objects.equals(
                                segment.segmentQuantity(), redemption.segmentQuantity())
                            || redemption.quantity() > segment.remainingQuantity()) {
                        throw new PersistenceConflictException(
                                "The research crystal redemption segment was already consumed");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE research_crystal_segments
                            SET redeemed_quantity = redeemed_quantity + ?
                            WHERE batch_id = ? AND segment_offset = ?
                              AND redeemed_quantity + ? <= segment_quantity
                            """)) {
                        statement.setInt(1, redemption.quantity());
                        statement.setString(2, redemption.batchId().toString());
                        statement.setInt(3, redemption.segmentOffset());
                        statement.setInt(4, redemption.quantity());
                        if (statement.executeUpdate() != 1) {
                            throw new PersistenceConflictException(
                                    "The research crystal redemption segment was concurrently resolved");
                        }
                    }
                }
                TeamProgress progress = loadTeamProgress(connection, redemption.teamId())
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The redemption team has no progression row"));
                long creditedPoints;
                try {
                    creditedPoints = Math.addExact(
                            progress.researchPoints(), redemption.quantity());
                } catch (ArithmeticException overflow) {
                    throw new PersistenceConflictException(
                            "The team's research point balance cannot increase further", overflow);
                }
                TeamProgress updatedProgress = new TeamProgress(
                        progress.teamId(),
                        progress.highestClearedLevel(),
                        progress.unlockedLevel(),
                        creditedPoints);
                int redeemedQuantity = batch.redeemedQuantity() + redemption.quantity();
                ResearchCrystalBatchStatus nextStatus = redeemedQuantity == batch.issuedQuantity()
                        ? ResearchCrystalBatchStatus.EXHAUSTED
                        : ResearchCrystalBatchStatus.ISSUED;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE research_crystal_batches
                        SET redeemed_quantity = ?, state = ?, updated_at = ?
                        WHERE batch_id = ? AND state = 'ISSUED'
                        """)) {
                    statement.setInt(1, redeemedQuantity);
                    statement.setString(2, nextStatus.name());
                    statement.setString(3, appliedAt.toString());
                    statement.setString(4, batch.batchId().toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The research crystal batch was concurrently resolved");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE team_progress
                        SET research_points = ?, updated_at = ?
                        WHERE team_id = ?
                        """)) {
                    statement.setLong(1, updatedProgress.researchPoints());
                    statement.setString(2, appliedAt.toString());
                    statement.setString(3, updatedProgress.teamId().toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The research point update affected no rows");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE research_crystal_redemptions
                        SET state = 'APPLIED', applied_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, appliedAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The crystal redemption apply affected no rows");
                    }
                }
                ResearchCrystalBatch updatedBatch = loadResearchCrystalBatch(
                                connection, batch.batchId())
                        .orElseThrow(() -> new SQLException(
                                "The crystal batch disappeared after apply"));
                return new ResearchCrystalRedemptionResult(
                        OperationOutcome.APPLIED, updatedProgress, updatedBatch);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The research crystal redemption conflicts with persisted data", exception);
            }
            throw failure("apply a research crystal redemption", exception);
        }
    }

    /** Rolls back a reservation when the Paper-side physical handoff did not complete. */
    public Optional<ResearchCrystalRedemption> rollbackResearchCrystalRedemption(
            UUID operationId,
            Instant rolledBackAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ResearchCrystalRedemption> loaded =
                        loadResearchCrystalRedemption(connection, operationId);
                if (loaded.isEmpty()
                        || loaded.orElseThrow().state() != ResearchCrystalRedemptionState.PREPARED) {
                    return loaded;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE research_crystal_redemptions
                        SET state = 'ROLLED_BACK', rolled_back_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, rolledBackAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("The crystal redemption rollback affected no rows");
                    }
                }
                ResearchCrystalRedemption redemption = loaded.orElseThrow();
                return Optional.of(new ResearchCrystalRedemption(
                        redemption.operationId(),
                        redemption.batchId(),
                        redemption.coreId(),
                        redemption.teamId(),
                        redemption.actorId(),
                        redemption.quantity(),
                        redemption.payloadFingerprint(),
                        redemption.segmentOffset(),
                        redemption.segmentQuantity(),
                        ResearchCrystalRedemptionState.ROLLED_BACK,
                        redemption.preparedAt(),
                        null,
                        rolledBackAt));
            });
        } catch (SQLException exception) {
            throw failure("roll back a research crystal redemption", exception);
        }
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
                if (team.members().size() >= MAX_TEAM_MEMBERS) {
                    throw new PersistenceConflictException(
                            "The team has reached the maximum of " + MAX_TEAM_MEMBERS + " members");
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

    /**
     * Persists the prepared side of the public core physical-placement stop window.
     *
     * <p>No core row is created here. The caller must restore the captured block if this
     * operation remains prepared during startup recovery.</p>
     */
    public CorePlacement prepareCorePlacement(CorePlacement placement) {
        Objects.requireNonNull(placement, "placement");
        if (placement.state() != CorePlacementState.PREPARED) {
            throw new IllegalArgumentException("A placement request must be PREPARED");
        }
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<CorePlacement> existing = loadCorePlacement(
                        connection, placement.operationId());
                if (existing.isPresent()) {
                    requireMatchingCorePlacement(existing.orElseThrow(), placement);
                    return existing.orElseThrow();
                }
                requireNoActiveEvent(connection, "prepare a core placement");
                TeamRecord team = requireTeam(connection, placement.teamId());
                if (placement.relocatingExistingCore()) {
                    requireTeamMember(connection, placement.teamId(), placement.actorId());
                } else {
                    requireTeamOwner(team, placement.actorId());
                }
                Optional<CoreRecord> current = loadCoreByTeam(connection, placement.teamId());
                if (placement.relocatingExistingCore()) {
                    CoreRecord existingCore = current.orElseThrow(
                            () -> new PersistenceConflictException(
                                    "The core to relocate does not exist"));
                    if (!existingCore.id().equals(placement.coreId())
                            || existingCore.currentHitPoints() != existingCore.maximumHitPoints()) {
                        throw new PersistenceConflictException(
                                "Only the team's full-health core can be relocated");
                    }
                    if (loadCore(connection, placement.coreId()).isEmpty()) {
                        throw new PersistenceConflictException(
                                "The core to relocate has no durable row");
                    }
                } else if (placement.rebuildingDestroyedCore()) {
                    CoreRecord existingCore = current.orElseThrow(
                            () -> new PersistenceConflictException(
                                    "The destroyed core to rebuild does not exist"));
                    if (!existingCore.id().equals(placement.coreId())
                            || existingCore.currentHitPoints() != 0L) {
                        throw new PersistenceConflictException(
                                "Only the team's destroyed core can be rebuilt");
                    }
                } else {
                    if (current.isPresent() && current.orElseThrow().currentHitPoints() > 0L) {
                        throw new PersistenceConflictException(
                                "The team already owns a live core");
                    }
                    if (loadCore(connection, placement.coreId()).isPresent()) {
                        throw new PersistenceConflictException(
                                "The core item identity has already been used");
                    }
                }
                insertCorePlacement(connection, placement);
                return placement;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The core placement conflicts with another pending operation", exception);
            }
            throw failure("prepare a core placement", exception);
        }
    }

    /** Applies the database side after the Paper block has been replaced and tagged. */
    public CorePlacementResult applyCorePlacement(UUID operationId, Instant appliedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                CorePlacement placement = loadCorePlacement(connection, operationId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "The prepared core placement does not exist"));
                if (placement.state() == CorePlacementState.APPLIED) {
                    return new CorePlacementResult(
                            placement,
                            loadCore(connection, placement.coreId()).orElseThrow(
                                    () -> new PersistenceConflictException(
                                            "An applied core placement has no core row")));
                }
                if (placement.state() == CorePlacementState.ROLLED_BACK) {
                    throw new PersistenceConflictException(
                            "The prepared core placement was already rolled back");
                }
                requireNoActiveEvent(connection, "apply a core placement");
                TeamRecord team = requireTeam(connection, placement.teamId());
                if (placement.relocatingExistingCore()) {
                    requireTeamMember(connection, placement.teamId(), placement.actorId());
                } else {
                    requireTeamOwner(team, placement.actorId());
                }
                CoreRecord core;
                if (placement.relocatingExistingCore()) {
                    CoreRecord existing = requireCore(connection, placement.coreId());
                    if (existing.currentHitPoints() != existing.maximumHitPoints()
                            || !existing.teamId().equals(placement.teamId())) {
                        throw new PersistenceConflictException(
                                "The team's core is no longer relocatable");
                    }
                    Optional<CoreRecord> nearby = findDistanceConflict(
                            connection,
                            placement.worldId(),
                            placement.blockX(),
                            placement.blockZ(),
                            placement.minimumCoreDistance(),
                            existing.id());
                    if (nearby.isPresent()) {
                        throw new PersistenceConflictException(
                                "Core position is too close to core " + nearby.orElseThrow().id());
                    }
                    core = new CoreRecord(
                            existing.id(),
                            existing.teamId(),
                            placement.worldId(),
                            placement.blockX(),
                            placement.blockY(),
                            placement.blockZ(),
                            existing.currentHitPoints(),
                            existing.maximumHitPoints(),
                            existing.createdAt(),
                            appliedAt);
                    updateCorePosition(connection, core);
                } else if (placement.rebuildingDestroyedCore()) {
                    CoreRecord existing = requireCore(connection, placement.coreId());
                    if (existing.currentHitPoints() != 0L
                            || !existing.teamId().equals(placement.teamId())) {
                        throw new PersistenceConflictException(
                                "The team's core is no longer rebuildable");
                    }
                    core = new CoreRecord(
                            existing.id(),
                            existing.teamId(),
                            placement.worldId(),
                            placement.blockX(),
                            placement.blockY(),
                            placement.blockZ(),
                            placement.maximumHitPoints(),
                            placement.maximumHitPoints(),
                            existing.createdAt(),
                            appliedAt);
                    Optional<CoreRecord> nearby = findDistanceConflict(
                            connection,
                            placement.worldId(),
                            placement.blockX(),
                            placement.blockZ(),
                            placement.minimumCoreDistance(),
                            existing.id());
                    if (nearby.isPresent()) {
                        throw new PersistenceConflictException(
                                "Core position is too close to core " + nearby.orElseThrow().id());
                    }
                    updateCore(connection, core);
                } else {
                    core = new CoreRecord(
                            placement.coreId(),
                            placement.teamId(),
                            placement.worldId(),
                            placement.blockX(),
                            placement.blockY(),
                            placement.blockZ(),
                            placement.maximumHitPoints(),
                            placement.maximumHitPoints(),
                            appliedAt,
                            appliedAt);
                    placeCore(connection, core, placement.minimumCoreDistance());
                }
                CorePlacement applied = new CorePlacement(
                        placement.operationId(),
                        placement.itemId(),
                        placement.coreId(),
                        placement.actorId(),
                        placement.teamId(),
                        placement.worldId(),
                        placement.blockX(),
                        placement.blockY(),
                        placement.blockZ(),
                        placement.maximumHitPoints(),
                        placement.minimumCoreDistance(),
                        placement.rebuildingDestroyedCore(),
                        placement.relocatingExistingCore(),
                        placement.previousBlockData(),
                        CorePlacementState.APPLIED,
                        placement.preparedAt(),
                        appliedAt,
                        null);
                updateCorePlacementState(connection, applied);
                return new CorePlacementResult(applied, core);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The core placement conflicts with persisted core data", exception);
            }
            throw failure("apply a core placement", exception);
        }
    }

    /** Marks a still-prepared placement as rolled back after its physical block is restored. */
    public Optional<CorePlacement> rollbackCorePlacement(
            UUID operationId, Instant rolledBackAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<CorePlacement> loaded = loadCorePlacement(connection, operationId);
                if (loaded.isEmpty() || loaded.orElseThrow().state() != CorePlacementState.PREPARED) {
                    return loaded;
                }
                CorePlacement rolledBack = new CorePlacement(
                        loaded.orElseThrow().operationId(),
                        loaded.orElseThrow().itemId(),
                        loaded.orElseThrow().coreId(),
                        loaded.orElseThrow().actorId(),
                        loaded.orElseThrow().teamId(),
                        loaded.orElseThrow().worldId(),
                        loaded.orElseThrow().blockX(),
                        loaded.orElseThrow().blockY(),
                        loaded.orElseThrow().blockZ(),
                        loaded.orElseThrow().maximumHitPoints(),
                        loaded.orElseThrow().minimumCoreDistance(),
                        loaded.orElseThrow().rebuildingDestroyedCore(),
                        loaded.orElseThrow().relocatingExistingCore(),
                        loaded.orElseThrow().previousBlockData(),
                        CorePlacementState.ROLLED_BACK,
                        loaded.orElseThrow().preparedAt(),
                        null,
                        rolledBackAt);
                updateCorePlacementState(connection, rolledBack);
                return Optional.of(rolledBack);
            });
        } catch (SQLException exception) {
            throw failure("roll back a prepared core placement", exception);
        }
    }

    /** Loads prepared operations for startup physical recovery. */
    public List<CorePlacement> loadPendingCorePlacements() {
        return loadCorePlacementsByState(CorePlacementState.PREPARED);
    }

    /** Loads item identities which were applied before their inventory handoff completed. */
    public List<UUID> loadAppliedCorePlacementItemIds() {
        return read("load applied core placement item identities", connection -> {
            List<UUID> itemIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT item_id FROM core_placement_operations
                    WHERE state = 'APPLIED'
                    ORDER BY applied_at, operation_id
                    """);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    itemIds.add(uuid(resultSet.getString("item_id")));
                }
            }
            return List.copyOf(itemIds);
        });
    }

    /** Loads the last applied placement ledger for a core's physical source block. */
    public Optional<CorePlacement> findAppliedCorePlacementByCore(UUID coreId) {
        Objects.requireNonNull(coreId, "coreId");
        return read("load a core's applied placement", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT operation_id, item_id, core_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, max_hp, minimum_core_distance,
                           rebuilding_destroyed_core, relocating_existing_core,
                           previous_block_data, state, prepared_at, applied_at, rolled_back_at
                    FROM core_placement_operations
                    WHERE core_id = ? AND state = 'APPLIED'
                    ORDER BY applied_at DESC, operation_id DESC
                    LIMIT 1
                    """)) {
                statement.setString(1, coreId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(corePlacementFromRow(resultSet))
                            : Optional.empty();
                }
            }
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
        return repairCore(
                coreId,
                actorId,
                amount,
                0L,
                PaymentMode.LEGACY_ITEMS,
                operationId,
                repairedAt);
    }

    /** Repairs a core and debits the team point wallet in the same SQLite transaction. */
    public CoreMutationResult repairCore(
            UUID coreId,
            UUID actorId,
            long amount,
            long defensePointCost,
            UUID operationId,
            Instant repairedAt) {
        return repairCore(
                coreId,
                actorId,
                amount,
                defensePointCost,
                defensePointCost > 0L ? PaymentMode.POINT_WALLET : PaymentMode.LEGACY_ITEMS,
                operationId,
                repairedAt);
    }

    /** Repairs a core using an explicitly persisted payment mode. */
    public CoreMutationResult repairCore(
            UUID coreId,
            UUID actorId,
            long amount,
            long defensePointCost,
            PaymentMode paymentMode,
            UUID operationId,
            Instant repairedAt) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(paymentMode, "paymentMode");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(repairedAt, "repairedAt");
        if (amount <= 0L) {
            throw new IllegalArgumentException("repair amount must be positive");
        }
        if (defensePointCost < 0L) {
            throw new IllegalArgumentException("defensePointCost must not be negative");
        }
        if (paymentMode == PaymentMode.LEGACY_ITEMS && defensePointCost != 0L) {
            throw new IllegalArgumentException(
                    "legacy item repairs cannot include a wallet payment");
        }
        String fingerprint = managementFingerprint(
                "CORE_REPAIR", coreId, actorId, amount, defensePointCost, paymentMode);
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
                            fingerprint,
                            paymentMode);
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
                if (paymentMode == PaymentMode.POINT_WALLET && defensePointCost > 0L) {
                    ResourceRepository.debitInTransaction(
                            connection,
                            core.teamId(),
                            actorId,
                            ResourceType.DEFENSE_POINTS,
                            defensePointCost,
                            UUID.nameUUIDFromBytes((operationId + "|DEFENSE_POINTS")
                                    .getBytes(StandardCharsets.UTF_8)),
                            operationId.toString(),
                            fingerprint,
                            repairedAt);
                }
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
                        paymentMode,
                        repairedAt);
                return new CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(updated));
            });
        } catch (SQLException exception) {
            throw failure("repair a core", exception);
        }
    }

    /**
     * Reserves a core repair before any vanilla inventory material is moved into a receipt stack.
     * The prepared row fixes the core HP, wallet cost, legacy material and operation fingerprint
     * so a restart cannot silently apply a different repair payload.
     */
    public CoreRepairOperation prepareCoreRepair(
            UUID coreId,
            UUID actorId,
            long amount,
            long defensePointCost,
            PaymentMode paymentMode,
            String vanillaMaterial,
            long vanillaMaterialAmount,
            UUID operationId,
            Instant preparedAt) {
        return prepareCoreRepair(
                coreId,
                actorId,
                amount,
                defensePointCost,
                paymentMode,
                vanillaMaterial,
                vanillaMaterialAmount,
                0L,
                operationId,
                preparedAt);
    }

    /**
     * Prepares a repair with an explicit legacy defense-shard quantity. Wallet repairs keep this
     * quantity at zero; legacy repairs persist it so a restart can distinguish a physical shard
     * payment from a wallet debit without reconstructing the quote from current settings.
     */
    public CoreRepairOperation prepareCoreRepair(
            UUID coreId,
            UUID actorId,
            long amount,
            long defensePointCost,
            PaymentMode paymentMode,
            String vanillaMaterial,
            long vanillaMaterialAmount,
            long legacyDefenseShardAmount,
            UUID operationId,
            Instant preparedAt) {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(paymentMode, "paymentMode");
        Objects.requireNonNull(vanillaMaterial, "vanillaMaterial");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (amount <= 0L || defensePointCost < 0L || vanillaMaterialAmount < 0L
                || legacyDefenseShardAmount < 0L) {
            throw new IllegalArgumentException("core repair quantities are invalid");
        }
        if (paymentMode == PaymentMode.LEGACY_ITEMS && defensePointCost != 0L) {
            throw new IllegalArgumentException(
                    "legacy item repairs cannot include a wallet payment");
        }
        String fingerprint = managementFingerprint(
                "CORE_REPAIR_RECEIPT",
                coreId,
                actorId,
                amount,
                defensePointCost,
                paymentMode,
                vanillaMaterial,
                vanillaMaterialAmount,
                legacyDefenseShardAmount);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<CoreRepairOperation> existing = loadCoreRepairOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingCoreRepairOperation(
                            existing.orElseThrow(),
                            coreId,
                            actorId,
                            amount,
                            defensePointCost,
                            paymentMode,
                            vanillaMaterial,
                            vanillaMaterialAmount,
                            legacyDefenseShardAmount,
                            fingerprint);
                    return existing.orElseThrow();
                }
                requireNoActiveEvent(connection, "prepare a core repair");
                CoreRecord core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() == 0L) {
                    throw new PersistenceConflictException(
                            "A destroyed core must be rebuilt before it can be repaired");
                }
                if (core.currentHitPoints() >= core.maximumHitPoints()) {
                    throw new PersistenceConflictException("The core is already at full health");
                }
                CoreRepairOperation prepared = new CoreRepairOperation(
                        operationId,
                        core.id(),
                        core.teamId(),
                        actorId,
                        core.currentHitPoints(),
                        amount,
                        defensePointCost,
                        paymentMode,
                        vanillaMaterial,
                        vanillaMaterialAmount,
                        legacyDefenseShardAmount,
                        fingerprint,
                        CoreRepairOperationState.PREPARED,
                        preparedAt,
                        null,
                        null);
                insertCoreRepairOperation(connection, prepared);
                return prepared;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The prepared core repair conflicts with persisted data", exception);
            }
            throw failure("prepare a core repair", exception);
        }
    }

    /** Persists the inventory receipt after the prepared operation has been created. */
    public CoreRepairReceipt reserveCoreRepairReceipt(
            UUID operationId,
            UUID playerId,
            String material,
            long quantity,
            Instant reservedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(reservedAt, "reservedAt");
        if (quantity <= 0L) {
            throw new IllegalArgumentException("receipt quantity must be positive");
        }
        try {
            return database.inImmediateTransaction(connection -> {
                CoreRepairOperation operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The prepared core repair does not exist"));
                if (operation.state() != CoreRepairOperationState.PREPARED) {
                    throw new PersistenceConflictException(
                            "The core repair is no longer reservable");
                }
                if (!operation.actorId().equals(playerId)
                        || !expectedReceiptMaterial(operation).equals(material)
                        || expectedReceiptQuantity(operation) != quantity) {
                    throw new PersistenceConflictException(
                            "The core repair receipt does not match its prepared payload");
                }
                Optional<CoreRepairReceipt> existing = loadCoreRepairReceipt(
                        connection, operationId);
                if (existing.isPresent()) {
                    CoreRepairReceipt receipt = existing.orElseThrow();
                    if (!receipt.playerId().equals(playerId)
                            || !receipt.material().equals(material)
                            || receipt.quantity() != quantity) {
                        throw new PersistenceConflictException(
                                "The core repair receipt UUID is already assigned to another payload");
                    }
                    return receipt;
                }
                CoreRepairReceipt receipt = new CoreRepairReceipt(
                        operationId,
                        playerId,
                        material,
                        quantity,
                        CoreRepairReceiptState.RESERVED,
                        reservedAt,
                        null);
                insertCoreRepairReceipt(connection, receipt);
                return receipt;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The core repair receipt conflicts with persisted data", exception);
            }
            throw failure("reserve a core repair receipt", exception);
        }
    }

    /** Applies a prepared repair and wallet debit atomically after its receipt is secured. */
    public CoreMutationResult applyPreparedCoreRepair(
            UUID operationId,
            Instant appliedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                CoreRepairOperation operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The prepared core repair does not exist"));
                if (operation.state() == CoreRepairOperationState.APPLIED) {
                    return new CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, operation.coreId()));
                }
                if (operation.state() == CoreRepairOperationState.ROLLED_BACK) {
                    throw new PersistenceConflictException(
                            "The prepared core repair was already rolled back");
                }
                if (expectedReceiptQuantity(operation) > 0L) {
                    CoreRepairReceipt receipt = loadCoreRepairReceipt(connection, operationId)
                            .orElseThrow(() -> new PersistenceConflictException(
                                    "The core repair receipt was not secured"));
                    if (receipt.state() != CoreRepairReceiptState.SECURED) {
                        throw new PersistenceConflictException(
                                "The core repair receipt has not reached the secured handoff");
                    }
                }
                requireNoActiveEvent(connection, "apply a core repair");
                CoreRecord core = requireCore(connection, operation.coreId());
                requireTeamMember(connection, core.teamId(), operation.actorId());
                if (core.currentHitPoints() != operation.expectedCurrentHitPoints()) {
                    throw new PersistenceConflictException(
                            "The core HP changed before the prepared repair was applied");
                }
                long missingHitPoints = core.maximumHitPoints() - core.currentHitPoints();
                long repairedHitPoints = core.currentHitPoints()
                        + Math.min(operation.repairAmount(), missingHitPoints);
                if (operation.paymentMode() == PaymentMode.POINT_WALLET
                        && operation.defensePointCost() > 0L) {
                    ResourceRepository.debitInTransaction(
                            connection,
                            core.teamId(),
                            operation.actorId(),
                            ResourceType.DEFENSE_POINTS,
                            operation.defensePointCost(),
                            UUID.nameUUIDFromBytes((operationId + "|DEFENSE_POINTS")
                                    .getBytes(StandardCharsets.UTF_8)),
                            operationId.toString(),
                            operation.payloadFingerprint(),
                            appliedAt);
                }
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
                        appliedAt);
                updateCoreHealth(connection, updated);
                insertManagementOperation(
                        connection,
                        operation.operationId(),
                        "CORE",
                        operation.coreId(),
                        "CORE_REPAIR",
                        managementFingerprint(
                                "CORE_REPAIR",
                                operation.coreId(),
                                operation.actorId(),
                                operation.repairAmount(),
                                operation.defensePointCost(),
                                operation.paymentMode()),
                        operation.paymentMode(),
                        appliedAt);
                updateCoreRepairOperationState(
                        connection,
                        operationId,
                        CoreRepairOperationState.APPLIED,
                        appliedAt);
                return new CoreMutationResult(ManagementOutcome.APPLIED, Optional.of(updated));
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The prepared core repair conflicts with persisted data", exception);
            }
            throw failure("apply a prepared core repair", exception);
        }
    }

    /** Durably records that the exact material stacks were replaced with tagged receipt stacks. */
    public OperationOutcome secureCoreRepairReceipt(UUID operationId, Instant securedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(securedAt, "securedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                CoreRepairReceipt receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The core repair receipt does not exist"));
                if (receipt.state() == CoreRepairReceiptState.SECURED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.RESERVED) {
                    throw new PersistenceConflictException(
                            "The core repair receipt is no longer reservable");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE core_repair_receipts
                        SET state = 'SECURED'
                        WHERE operation_id = ? AND state = 'RESERVED'
                        """)) {
                    statement.setString(1, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The core repair receipt changed concurrently");
                    }
                }
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("secure a core repair receipt", exception);
        }
    }

    /** Marks the physical receipt clear as the next durable step after the applied mutation. */
    public OperationOutcome markCoreRepairReceiptClearPending(
            UUID operationId,
            Instant pendingAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(pendingAt, "pendingAt");
        try {
            return database.inImmediateTransaction(connection -> {
                CoreRepairOperation operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The core repair operation does not exist"));
                if (operation.state() != CoreRepairOperationState.APPLIED) {
                    throw new PersistenceConflictException(
                            "Only an applied core repair can clear its receipt");
                }
                CoreRepairReceipt receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElse(null);
                if (receipt == null
                        || receipt.state() == CoreRepairReceiptState.CLEARED
                        || receipt.state() == CoreRepairReceiptState.CLEAR_PENDING) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.SECURED) {
                    throw new PersistenceConflictException(
                            "Only a secured core repair receipt can enter physical clear");
                }
                updateCoreRepairReceiptState(
                        connection,
                        operationId,
                        CoreRepairReceiptState.CLEAR_PENDING,
                        pendingAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("mark a core repair receipt clear-pending", exception);
        }
    }

    /** Marks the physical receipt clear after the applied core mutation is confirmed. */
    public OperationOutcome clearCoreRepairReceipt(UUID operationId, Instant clearedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clearedAt, "clearedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                CoreRepairOperation operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The core repair operation does not exist"));
                if (operation.state() != CoreRepairOperationState.APPLIED) {
                    throw new PersistenceConflictException(
                            "Only an applied core repair can clear its receipt");
                }
                CoreRepairReceipt receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElse(null);
                if (receipt == null || receipt.state() == CoreRepairReceiptState.CLEARED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.SECURED
                        && receipt.state() != CoreRepairReceiptState.CLEAR_PENDING) {
                    throw new PersistenceConflictException(
                            "Only a secured core repair receipt can be cleared");
                }
                updateCoreRepairReceiptState(
                        connection,
                        operationId,
                        CoreRepairReceiptState.CLEARED,
                        clearedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("clear a core repair receipt", exception);
        }
    }

    /** Rolls back a prepared operation and releases its receipt after a failed physical step. */
    public OperationOutcome rollbackPreparedCoreRepair(
            UUID operationId,
            Instant rolledBackAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                CoreRepairOperation operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The core repair operation does not exist"));
                if (operation.state() == CoreRepairOperationState.ROLLED_BACK) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (operation.state() == CoreRepairOperationState.APPLIED) {
                    throw new PersistenceConflictException(
                            "An applied core repair cannot be rolled back");
                }
                updateCoreRepairOperationState(
                        connection,
                        operationId,
                        CoreRepairOperationState.ROLLED_BACK,
                        rolledBackAt);
                updateCoreRepairReceiptState(
                        connection,
                        operationId,
                        CoreRepairReceiptState.RETURN_PENDING,
                        rolledBackAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("roll back a prepared core repair", exception);
        }
    }

    /** Completes a physical refund after the player inventory has been durably saved. */
    public OperationOutcome restoreCoreRepairReceipt(UUID operationId, Instant restoredAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(restoredAt, "restoredAt");
        try {
            return database.inImmediateTransaction(connection -> {
                CoreRepairReceipt receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElse(null);
                if (receipt == null || receipt.state() == CoreRepairReceiptState.RESTORED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.RETURN_PENDING) {
                    throw new PersistenceConflictException(
                            "Only a return-pending core receipt can be restored");
                }
                updateCoreRepairReceiptState(
                        connection,
                        operationId,
                        CoreRepairReceiptState.RESTORED,
                        restoredAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("restore a core repair receipt", exception);
        }
    }

    public Optional<CoreRepairOperation> findCoreRepairOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return read(
                "load a core repair operation",
                connection -> loadCoreRepairOperation(connection, operationId));
    }

    public Optional<CoreRepairReceipt> findCoreRepairReceipt(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return read(
                "load a core repair receipt",
                connection -> loadCoreRepairReceipt(connection, operationId));
    }

    public List<CoreRepairOperation> loadPreparedCoreRepairs(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return read("load prepared core repairs", connection -> {
            List<CoreRepairOperation> operations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT operation_id, core_id, team_id, actor_id, expected_current_hp,
                           repair_amount, defense_point_cost, payment_mode, vanilla_material,
                           vanilla_material_amount, legacy_defense_shard_amount,
                           payload_fingerprint, state, prepared_at,
                           applied_at, rolled_back_at
                    FROM core_repair_operations
                    WHERE actor_id = ?
                      AND (state = 'PREPARED'
                           OR (state = 'APPLIED' AND EXISTS (
                               SELECT 1
                               FROM core_repair_receipts r
                               WHERE r.operation_id = core_repair_operations.operation_id
                                 AND r.state IN ('RESERVED', 'SECURED', 'CLEAR_PENDING')
                           ))
                           OR (state = 'ROLLED_BACK' AND EXISTS (
                               SELECT 1
                               FROM core_repair_receipts r
                               WHERE r.operation_id = core_repair_operations.operation_id
                                 AND r.state = 'RETURN_PENDING'
                           )))
                    ORDER BY prepared_at, operation_id
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        operations.add(coreRepairOperationFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(operations);
        });
    }

    /**
     * Loads terminal receipt rows as a bounded idempotent inventory tombstone.
     *
     * <p>A player save can race the database terminal transition during shutdown.  Keeping the
     * terminal operation visible to the next join lets the Paper bridge strip a resurrected
     * tagged stack without minting or charging anything again.</p>
     */
    public List<CoreRepairOperation> loadTerminalCoreRepairReceipts(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return read("load terminal core repair receipt tombstones", connection -> {
            List<CoreRepairOperation> operations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT operation_id, core_id, team_id, actor_id, expected_current_hp,
                           repair_amount, defense_point_cost, payment_mode, vanilla_material,
                           vanilla_material_amount, legacy_defense_shard_amount,
                           payload_fingerprint, state, prepared_at,
                           applied_at, rolled_back_at
                    FROM core_repair_operations
                    WHERE actor_id = ?
                      AND ((state = 'APPLIED' AND EXISTS (
                               SELECT 1 FROM core_repair_receipts r
                               WHERE r.operation_id = core_repair_operations.operation_id
                                 AND r.state = 'CLEARED'
                           ))
                           OR (state = 'ROLLED_BACK' AND EXISTS (
                               SELECT 1 FROM core_repair_receipts r
                               WHERE r.operation_id = core_repair_operations.operation_id
                                 AND r.state = 'RESTORED'
                           )))
                    ORDER BY prepared_at, operation_id
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        operations.add(coreRepairOperationFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(operations);
        });
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
        return tryStart(request, true);
    }

    /**
     * Creates an event while leaving a supplied raid seal RESERVED. The Paper caller removes the
     * matching physical item on the main thread and must then call
     * {@link #consumeReservedStartSeal(UUID, UUID, Instant)}. A failed or interrupted event is
     * eligible for the normal technical-refund transaction.
     */
    public StartOutcome tryStartReserved(StartRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.raidSealId().isEmpty()) {
            throw new IllegalArgumentException("A reserved start requires a raid seal");
        }
        return tryStart(request, false);
    }

    /** Consumes the reservation after the corresponding physical token has been removed. */
    public OperationOutcome consumeReservedStartSeal(
            UUID eventId,
            UUID sealId,
            Instant consumedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sealId, "sealId");
        Objects.requireNonNull(consumedAt, "consumedAt");
        try {
            return database.inImmediateTransaction(connection ->
                    RaidSealRepository.consumeReservedForStart(
                            connection, eventId, sealId, consumedAt));
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The reserved raid seal conflicts with persisted data", exception);
            }
            throw failure("consume the reserved raid seal", exception);
        }
    }

    private StartOutcome tryStart(StartRequest request, boolean consumeSeal) {
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
                insertBattleFunds(connection, snapshot.eventId(), snapshot.teamId(), request.startedAt());
                replaceParticipants(connection, snapshot, request.startedAt());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO event_lock(singleton, event_id, acquired_at)
                        VALUES (1, ?, ?)
                        """)) {
                    statement.setString(1, snapshot.eventId().toString());
                    statement.setString(2, request.startedAt().toString());
                    statement.executeUpdate();
                }
                if (consumeSeal) {
                    RaidSealRepository.consumeForStart(connection, request);
                } else {
                    RaidSealRepository.reserveForStart(connection, request);
                }
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

    /** Loads the event-scoped battle-funds account. */
    public BattleFunds loadBattleFunds(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read(
                "load event battle funds",
                connection -> loadBattleFunds(connection, eventId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "Defense event " + eventId + " has no battle-funds account")));
    }

    /** Credits event funds for a deterministic enemy or wave reward operation. */
    public BattleFundsMutationResult creditBattleFunds(
            UUID eventId,
            UUID teamId,
            UUID operationId,
            String operationKind,
            long amount,
            Instant appliedAt) {
        return mutateBattleFunds(
                eventId,
                teamId,
                null,
                operationId,
                operationKind,
                amount,
                appliedAt,
                false);
    }

    /** Spends event funds for a team-member operation during preparation or intermission. */
    public BattleFundsMutationResult spendBattleFunds(
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID operationId,
            String operationKind,
            long amount,
            Instant appliedAt) {
        return mutateBattleFunds(
                eventId,
                teamId,
                actorId,
                operationId,
                operationKind,
                amount,
                appliedAt,
                true);
    }

    /** Loads the temporary boosts that survived a restart while an event was still active. */
    public List<BattleBoost> loadBattleBoosts(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load event tower boosts", connection -> {
            List<BattleBoost> boosts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT event_id, team_id, tower_id, boost_kind, level, multiplier, updated_at
                    FROM event_tower_boosts
                    WHERE event_id = ?
                    ORDER BY tower_id, boost_kind
                    """)) {
                statement.setString(1, eventId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        boosts.add(battleBoostFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(boosts);
        });
    }

    /** Purchases one cumulative temporary boost and spends its funds in the same transaction. */
    public BattleBoostMutationResult purchaseBattleBoost(
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            BattleBoostKind kind,
            long cost,
            double boostMultiplier,
            UUID operationId,
            Instant appliedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (cost <= 0L) {
            throw new IllegalArgumentException("cost must be positive");
        }
        if (!Double.isFinite(boostMultiplier) || boostMultiplier <= 0.0d) {
            throw new IllegalArgumentException("boostMultiplier must be finite and positive");
        }
        String operationKind = "BOOST_" + kind.id();
        String fingerprint = managementFingerprint(
                "BATTLE_BOOST",
                eventId,
                teamId,
                actorId,
                towerId,
                kind.id(),
                cost,
                boostMultiplier);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<BattleBoostOperation> existing = loadBattleBoostOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingBattleBoostOperation(
                            existing.orElseThrow(),
                            eventId,
                            teamId,
                            actorId,
                            towerId,
                            kind,
                            cost,
                            boostMultiplier,
                            fingerprint);
                    return new BattleBoostMutationResult(
                            OperationOutcome.ALREADY_APPLIED,
                            requireBattleBoost(connection, eventId, towerId, kind),
                            requireBattleFunds(connection, eventId));
                }

                requireActiveBattleFundsEvent(connection, eventId, true);
                requireTeamMember(connection, teamId, actorId);
                requireTowerBelongsToTeam(connection, towerId, teamId);
                BattleFunds currentFunds = requireBattleFunds(connection, eventId);
                if (!currentFunds.teamId().equals(teamId)) {
                    throw new PersistenceConflictException(
                            "The battle-boost operation belongs to another team");
                }
                if (currentFunds.balance() < cost) {
                    throw new PersistenceConflictException(
                            "The team does not have enough battle funds for this boost");
                }
                Optional<BattleBoost> current = loadBattleBoost(
                        connection, eventId, towerId, kind);
                int nextLevel = current.isPresent()
                        ? Math.addExact(current.orElseThrow().level(), 1)
                        : 1;
                double previousMultiplier = current.map(BattleBoost::multiplier).orElse(1.0d);
                double nextMultiplier = previousMultiplier * boostMultiplier;
                if (!Double.isFinite(nextMultiplier)) {
                    throw new PersistenceConflictException(
                            "The battle boost multiplier is outside the supported range");
                }
                BattleFunds updatedFunds = new BattleFunds(
                        currentFunds.eventId(),
                        currentFunds.teamId(),
                        currentFunds.balance() - cost,
                        currentFunds.totalEarned(),
                        Math.addExact(currentFunds.totalSpent(), cost),
                        BattleFundsState.ACTIVE,
                        appliedAt);
                BattleBoost updatedBoost = new BattleBoost(
                        eventId,
                        teamId,
                        towerId,
                        kind,
                        nextLevel,
                        nextMultiplier,
                        appliedAt);
                updateBattleFunds(connection, updatedFunds);
                upsertBattleBoost(connection, updatedBoost);
                insertBattleFundsOperation(
                        connection,
                        operationId,
                        eventId,
                        teamId,
                        actorId,
                        operationKind,
                        cost,
                        fingerprint,
                        appliedAt);
                insertBattleBoostOperation(
                        connection,
                        operationId,
                        eventId,
                        teamId,
                        actorId,
                        towerId,
                        kind,
                        cost,
                        boostMultiplier,
                        fingerprint,
                        appliedAt);
                return new BattleBoostMutationResult(
                        OperationOutcome.APPLIED, updatedBoost, updatedFunds);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The battle-boost operation conflicts with persisted data", exception);
            }
            throw failure("purchase a battle boost", exception);
        } catch (ArithmeticException overflow) {
            throw new PersistenceConflictException(
                    "The battle-boost account cannot represent this purchase", overflow);
        }
    }

    /** Repairs a tower's durable HP and spends battle funds atomically. */
    public TowerRepairMutationResult repairTowerWithBattleFunds(
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            long repairedHitPoints,
            long cost,
            UUID operationId,
            Instant appliedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (repairedHitPoints <= 0L || cost <= 0L) {
            throw new IllegalArgumentException("tower repair amount and cost must be positive");
        }
        String operationKind = "REPAIR_TOWER";
        String fingerprint = managementFingerprint(
                "TOWER_REPAIR",
                eventId,
                teamId,
                actorId,
                towerId,
                repairedHitPoints,
                cost);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerRepairOperation> existing = loadTowerRepairOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingTowerRepairOperation(
                            existing.orElseThrow(),
                            eventId,
                            teamId,
                            actorId,
                            towerId,
                            repairedHitPoints,
                            cost,
                            fingerprint);
                    return new TowerRepairMutationResult(
                            OperationOutcome.ALREADY_APPLIED,
                            requireTowerDurability(connection, towerId),
                            requireBattleFunds(connection, eventId));
                }

                requireActiveBattleFundsEvent(connection, eventId, true);
                requireTeamMember(connection, teamId, actorId);
                TowerDurability current = requireTowerDurability(connection, towerId);
                if (!current.teamId().equals(teamId)) {
                    throw new PersistenceConflictException(
                            "The tower repair belongs to another team");
                }
                if (repairedHitPoints > current.maximumHitPoints() - current.currentHitPoints()) {
                    throw new PersistenceConflictException(
                            "The tower repair exceeds the missing HP");
                }
                BattleFunds currentFunds = requireBattleFunds(connection, eventId);
                if (!currentFunds.teamId().equals(teamId)) {
                    throw new PersistenceConflictException(
                            "The tower repair belongs to another event team");
                }
                if (currentFunds.balance() < cost) {
                    throw new PersistenceConflictException(
                            "The team does not have enough battle funds for tower repair");
                }
                TowerDurability updatedDurability = new TowerDurability(
                        towerId,
                        teamId,
                        current.currentHitPoints() + repairedHitPoints,
                        current.maximumHitPoints());
                BattleFunds updatedFunds = new BattleFunds(
                        currentFunds.eventId(),
                        currentFunds.teamId(),
                        currentFunds.balance() - cost,
                        currentFunds.totalEarned(),
                        Math.addExact(currentFunds.totalSpent(), cost),
                        BattleFundsState.ACTIVE,
                        appliedAt);
                updateTowerDurability(connection, updatedDurability, appliedAt);
                updateBattleFunds(connection, updatedFunds);
                insertBattleFundsOperation(
                        connection,
                        operationId,
                        eventId,
                        teamId,
                        actorId,
                        operationKind,
                        cost,
                        fingerprint,
                        appliedAt);
                insertTowerRepairOperation(
                        connection,
                        operationId,
                        eventId,
                        teamId,
                        actorId,
                        towerId,
                        repairedHitPoints,
                        cost,
                        fingerprint,
                        appliedAt);
                return new TowerRepairMutationResult(
                        OperationOutcome.APPLIED, updatedDurability, updatedFunds);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower repair operation conflicts with persisted data", exception);
            }
            throw failure("repair a tower with battle funds", exception);
        } catch (ArithmeticException overflow) {
            throw new PersistenceConflictException(
                    "The tower repair account cannot represent this purchase", overflow);
        }
    }

    /** Applies one destroyer attack to a tower and deletes the tower atomically at zero HP. */
    public TowerDamageMutationResult damageTowerByEnemy(
            UUID eventId,
            UUID teamId,
            UUID attackerLogicalEnemyId,
            UUID towerId,
            long damage,
            UUID operationId,
            Instant appliedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(attackerLogicalEnemyId, "attackerLogicalEnemyId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (damage <= 0L) {
            throw new IllegalArgumentException("tower damage must be positive");
        }
        String fingerprint = managementFingerprint(
                "TOWER_DAMAGE",
                eventId,
                teamId,
                attackerLogicalEnemyId,
                towerId,
                damage);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TowerDamageOperation> existing = loadTowerDamageOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    TowerDamageOperation operation = existing.orElseThrow();
                    requireMatchingTowerDamageOperation(
                            operation,
                            eventId,
                            teamId,
                            attackerLogicalEnemyId,
                            towerId,
                            damage,
                            fingerprint);
                    return damageResult(operation, OperationOutcome.ALREADY_APPLIED);
                }

                requireActiveTowerDamageEvent(connection, eventId, teamId);
                TowerDurability current = requireTowerDurability(connection, towerId);
                if (!current.teamId().equals(teamId)) {
                    throw new PersistenceConflictException(
                            "The tower damage belongs to another team");
                }
                boolean destroyed = current.currentHitPoints() <= damage;
                long remainingHitPoints = destroyed
                        ? 0L
                        : current.currentHitPoints() - damage;
                if (destroyed) {
                    deleteTower(connection, towerId, teamId);
                } else {
                    updateTowerDurability(
                            connection,
                            new TowerDurability(
                                    towerId,
                                    teamId,
                                    remainingHitPoints,
                                    current.maximumHitPoints()),
                            appliedAt);
                }
                TowerDamageOperation operation = new TowerDamageOperation(
                        eventId,
                        teamId,
                        attackerLogicalEnemyId,
                        towerId,
                        damage,
                        remainingHitPoints,
                        destroyed,
                        fingerprint);
                insertTowerDamageOperation(connection, operation, operationId, appliedAt);
                return damageResult(operation, OperationOutcome.APPLIED);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The tower damage operation conflicts with persisted data", exception);
            }
            throw failure("damage a tower by an event enemy", exception);
        }
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
                clearBattleBoosts(connection, eventId);
                settleBattleFunds(connection, eventId, occurredAt);
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

                if (terminalSnapshot.phase() == DefensePhase.VICTORY) {
                    issueVictoryResearchCrystals(
                            connection,
                            terminalSnapshot,
                            operationId,
                            occurredAt);
                    advanceTeamProgressAfterVictory(
                            connection, terminalSnapshot.teamId(), terminalSnapshot.stageLevel(), occurredAt);
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
                        occurredAt,
                        teamQueueRetention);
                clearBattleBoosts(connection, terminalSnapshot.eventId());
                settleBattleFunds(connection, terminalSnapshot.eventId(), occurredAt);
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

    /**
     * Creates the one team-scoped crystal drop for a successful terminal before queue settlement.
     *
     * <p>The existing escrow queue is deliberately reused as the physical delivery boundary:
     * defeats and recovery settle the synthetic drop without issuing a queue row, while victory
     * turns it into exactly one TEAM row. The separate batch ledger supplies the source-team and
     * redeemed-quantity authority used by the core deposit path.</p>
     */
    private BattleFundsMutationResult mutateBattleFunds(
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID operationId,
            String operationKind,
            long amount,
            Instant appliedAt,
            boolean spend) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationKind, "operationKind");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (operationKind.isBlank()) {
            throw new IllegalArgumentException("operationKind must not be blank");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be positive");
        }
        String fingerprint = managementFingerprint(
                spend ? "BATTLE_FUNDS_SPEND" : "BATTLE_FUNDS_CREDIT",
                eventId,
                teamId,
                actorId == null ? "SYSTEM" : actorId,
                operationKind,
                amount);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<BattleFundsOperation> existing = loadBattleFundsOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingBattleFundsOperation(
                            existing.orElseThrow(),
                            eventId,
                            teamId,
                            actorId,
                            operationKind,
                            amount,
                            fingerprint);
                    return new BattleFundsMutationResult(
                            OperationOutcome.ALREADY_APPLIED,
                            requireBattleFunds(connection, eventId));
                }

                requireActiveBattleFundsEvent(connection, eventId, spend);
                BattleFunds current = requireBattleFunds(connection, eventId);
                if (!current.teamId().equals(teamId)) {
                    throw new PersistenceConflictException(
                            "The battle-funds operation belongs to another team");
                }
                if (spend) {
                    requireTeamMember(connection, teamId, Objects.requireNonNull(actorId, "actorId"));
                    if (current.balance() < amount) {
                        throw new PersistenceConflictException(
                                "The team does not have enough battle funds");
                    }
                }
                long nextBalance;
                long nextEarned = current.totalEarned();
                long nextSpent = current.totalSpent();
                try {
                    if (spend) {
                        nextBalance = Math.subtractExact(current.balance(), amount);
                        nextSpent = Math.addExact(current.totalSpent(), amount);
                    } else {
                        nextBalance = Math.addExact(current.balance(), amount);
                        nextEarned = Math.addExact(current.totalEarned(), amount);
                    }
                } catch (ArithmeticException overflow) {
                    throw new PersistenceConflictException(
                            "The battle-funds account cannot represent this mutation", overflow);
                }
                BattleFunds updated = new BattleFunds(
                        current.eventId(),
                        current.teamId(),
                        nextBalance,
                        nextEarned,
                        nextSpent,
                        BattleFundsState.ACTIVE,
                        appliedAt);
                updateBattleFunds(connection, updated);
                insertBattleFundsOperation(
                        connection,
                        operationId,
                        eventId,
                        teamId,
                        actorId,
                        operationKind,
                        amount,
                        fingerprint,
                        appliedAt);
                return new BattleFundsMutationResult(OperationOutcome.APPLIED, updated);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The battle-funds operation conflicts with persisted data", exception);
            }
            throw failure("mutate event battle funds", exception);
        }
    }

    private static void requireActiveBattleFundsEvent(
            Connection connection,
            UUID eventId,
            boolean spend) throws SQLException {
        Optional<UUID> activeEvent = loadActiveEventId(connection);
        if (activeEvent.isEmpty() || !activeEvent.orElseThrow().equals(eventId)) {
            throw new PersistenceConflictException(
                    "Battle funds are available only for the active defense event");
        }
        StoredDefenseEvent event = requireEvent(connection, eventId);
        if (event.session().phase().isTerminal()
                || (spend && (event.session().phase() == DefensePhase.WAVE_ACTIVE
                        || event.session().phase() == DefensePhase.COUNTDOWN))) {
            throw new PersistenceConflictException(
                    spend
                            ? "Battle funds may only be spent during preparation or intermission"
                            : "The defense event is already terminal");
        }
    }

    private static void requireActiveTowerDamageEvent(
            Connection connection,
            UUID eventId,
            UUID teamId) throws SQLException {
        Optional<UUID> activeEvent = loadActiveEventId(connection);
        if (activeEvent.isEmpty() || !activeEvent.orElseThrow().equals(eventId)) {
            throw new PersistenceConflictException(
                    "Tower damage is available only for the active defense event");
        }
        StoredDefenseEvent event = requireEvent(connection, eventId);
        if (!event.session().teamId().equals(teamId)
                || event.session().phase() != DefensePhase.WAVE_ACTIVE) {
            throw new PersistenceConflictException(
                    "Tower damage is available only during the owning team's active wave");
        }
    }

    private static void insertBattleFunds(
            Connection connection,
            UUID eventId,
            UUID teamId,
            Instant createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_battle_funds(
                    event_id, team_id, balance, total_earned, total_spent, state, updated_at
                ) VALUES (?, ?, 0, 0, 0, 'ACTIVE', ?)
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, createdAt.toString());
            statement.executeUpdate();
        }
    }

    private static void settleBattleFunds(
            Connection connection,
            UUID eventId,
            Instant settledAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_battle_funds
                SET balance = 0, state = 'SETTLED', updated_at = ?
                WHERE event_id = ? AND state = 'ACTIVE'
                """)) {
            statement.setString(1, settledAt.toString());
            statement.setString(2, eventId.toString());
            statement.executeUpdate();
        }
    }

    private static void clearBattleBoosts(Connection connection, UUID eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM event_tower_boosts WHERE event_id = ?
                """)) {
            statement.setString(1, eventId.toString());
            statement.executeUpdate();
        }
    }

    private static void updateBattleFunds(Connection connection, BattleFunds funds)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_battle_funds
                SET balance = ?, total_earned = ?, total_spent = ?, state = ?, updated_at = ?
                WHERE event_id = ? AND state = 'ACTIVE'
                """)) {
            statement.setLong(1, funds.balance());
            statement.setLong(2, funds.totalEarned());
            statement.setLong(3, funds.totalSpent());
            statement.setString(4, funds.state().name());
            statement.setString(5, funds.updatedAt().toString());
            statement.setString(6, funds.eventId().toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException(
                        "The battle-funds account was concurrently settled");
            }
        }
    }

    private static Optional<BattleFunds> loadBattleFunds(
            Connection connection,
            UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, team_id, balance, total_earned, total_spent, state, updated_at
                FROM event_battle_funds WHERE event_id = ?
                """)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(battleFundsFromRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static BattleFunds requireBattleFunds(Connection connection, UUID eventId)
            throws SQLException {
        return loadBattleFunds(connection, eventId).orElseThrow(
                () -> new PersistenceConflictException(
                        "Defense event " + eventId + " has no battle-funds account"));
    }

    private static BattleFunds battleFundsFromRow(ResultSet resultSet) throws SQLException {
        return new BattleFunds(
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("team_id")),
                resultSet.getLong("balance"),
                resultSet.getLong("total_earned"),
                resultSet.getLong("total_spent"),
                BattleFundsState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("updated_at")));
    }

    private static BattleBoost battleBoostFromRow(ResultSet resultSet) throws SQLException {
        return new BattleBoost(
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("tower_id")),
                battleBoostKind(resultSet.getString("boost_kind")),
                resultSet.getInt("level"),
                resultSet.getDouble("multiplier"),
                instant(resultSet.getString("updated_at")));
    }

    private static BattleBoostKind battleBoostKind(String id) {
        for (BattleBoostKind kind : BattleBoostKind.values()) {
            if (kind.id().equals(id)) {
                return kind;
            }
        }
        throw new PersistenceException("Unknown persisted battle boost kind: " + id, null);
    }

    private static Optional<BattleBoost> loadBattleBoost(
            Connection connection,
            UUID eventId,
            UUID towerId,
            BattleBoostKind kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, team_id, tower_id, boost_kind, level, multiplier, updated_at
                FROM event_tower_boosts
                WHERE event_id = ? AND tower_id = ? AND boost_kind = ?
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, towerId.toString());
            statement.setString(3, kind.id());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(battleBoostFromRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static BattleBoost requireBattleBoost(
            Connection connection,
            UUID eventId,
            UUID towerId,
            BattleBoostKind kind) throws SQLException {
        return loadBattleBoost(connection, eventId, towerId, kind).orElseThrow(
                () -> new PersistenceConflictException(
                        "The battle boost was not stored for tower " + towerId));
    }

    private static void upsertBattleBoost(Connection connection, BattleBoost boost)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_tower_boosts(
                    event_id, team_id, tower_id, boost_kind, level, multiplier, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(event_id, tower_id, boost_kind) DO UPDATE SET
                    team_id = excluded.team_id,
                    level = excluded.level,
                    multiplier = excluded.multiplier,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, boost.eventId().toString());
            statement.setString(2, boost.teamId().toString());
            statement.setString(3, boost.towerId().toString());
            statement.setString(4, boost.kind().id());
            statement.setInt(5, boost.level());
            statement.setDouble(6, boost.multiplier());
            statement.setString(7, boost.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void insertBattleBoostOperation(
            Connection connection,
            UUID operationId,
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            BattleBoostKind kind,
            long cost,
            double boostMultiplier,
            String fingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_tower_boost_operations(
                    operation_id, event_id, team_id, actor_id, tower_id, boost_kind,
                    cost, boost_multiplier, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, teamId.toString());
            statement.setString(4, actorId.toString());
            statement.setString(5, towerId.toString());
            statement.setString(6, kind.id());
            statement.setLong(7, cost);
            statement.setDouble(8, boostMultiplier);
            statement.setString(9, fingerprint);
            statement.setString(10, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<BattleBoostOperation> loadBattleBoostOperation(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, team_id, actor_id, tower_id, boost_kind,
                       cost, boost_multiplier, payload_fingerprint
                FROM event_tower_boost_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BattleBoostOperation(
                        uuid(resultSet.getString("event_id")),
                        uuid(resultSet.getString("team_id")),
                        uuid(resultSet.getString("actor_id")),
                        uuid(resultSet.getString("tower_id")),
                        battleBoostKind(resultSet.getString("boost_kind")),
                        resultSet.getLong("cost"),
                        resultSet.getDouble("boost_multiplier"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingBattleBoostOperation(
            BattleBoostOperation existing,
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            BattleBoostKind kind,
            long cost,
            double boostMultiplier,
            String fingerprint) {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !existing.actorId().equals(actorId)
                || !existing.towerId().equals(towerId)
                || existing.kind() != kind
                || existing.cost() != cost
                || Double.compare(existing.boostMultiplier(), boostMultiplier) != 0
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The battle-boost operation UUID is already assigned to another payload");
        }
    }

    private static Optional<TowerDurability> loadTowerDurability(
            Connection connection,
            UUID towerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tower_id, team_id, current_hp, max_hp
                FROM towers WHERE tower_id = ?
                """)) {
            statement.setString(1, towerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new TowerDurability(
                                uuid(resultSet.getString("tower_id")),
                                uuid(resultSet.getString("team_id")),
                                resultSet.getLong("current_hp"),
                                resultSet.getLong("max_hp")))
                        : Optional.empty();
            }
        }
    }

    private static TowerDurability requireTowerDurability(
            Connection connection,
            UUID towerId) throws SQLException {
        return loadTowerDurability(connection, towerId).orElseThrow(
                () -> new PersistenceConflictException(
                        "The tower to repair does not exist"));
    }

    private static void updateTowerDurability(
            Connection connection,
            TowerDurability durability,
            Instant updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE towers
                SET current_hp = ?, updated_at = ?
                WHERE tower_id = ?
                """)) {
            statement.setLong(1, durability.currentHitPoints());
            statement.setString(2, updatedAt.toString());
            statement.setString(3, durability.towerId().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The tower durability update affected no rows");
            }
        }
    }

    private static void deleteTower(
            Connection connection,
            UUID towerId,
            UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM towers WHERE tower_id = ? AND team_id = ?")) {
            statement.setString(1, towerId.toString());
            statement.setString(2, teamId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The destroyed tower delete affected no rows");
            }
        }
    }

    private static void insertTowerDamageOperation(
            Connection connection,
            TowerDamageOperation operation,
            UUID operationId,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_tower_damage_operations(
                    operation_id, event_id, team_id, attacker_enemy_id, tower_id,
                    damage, remaining_hp, destroyed, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, operation.eventId().toString());
            statement.setString(3, operation.teamId().toString());
            statement.setString(4, operation.attackerLogicalEnemyId().toString());
            statement.setString(5, operation.towerId().toString());
            statement.setLong(6, operation.damage());
            statement.setLong(7, operation.remainingHitPoints());
            statement.setInt(8, operation.destroyed() ? 1 : 0);
            statement.setString(9, operation.payloadFingerprint());
            statement.setString(10, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<TowerDamageOperation> loadTowerDamageOperation(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, team_id, attacker_enemy_id, tower_id,
                       damage, remaining_hp, destroyed, payload_fingerprint
                FROM event_tower_damage_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TowerDamageOperation(
                        uuid(resultSet.getString("event_id")),
                        uuid(resultSet.getString("team_id")),
                        uuid(resultSet.getString("attacker_enemy_id")),
                        uuid(resultSet.getString("tower_id")),
                        resultSet.getLong("damage"),
                        resultSet.getLong("remaining_hp"),
                        resultSet.getInt("destroyed") == 1,
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingTowerDamageOperation(
            TowerDamageOperation existing,
            UUID eventId,
            UUID teamId,
            UUID attackerLogicalEnemyId,
            UUID towerId,
            long damage,
            String fingerprint) {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !existing.attackerLogicalEnemyId().equals(attackerLogicalEnemyId)
                || !existing.towerId().equals(towerId)
                || existing.damage() != damage
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The tower-damage operation UUID is already assigned to another payload");
        }
    }

    private static TowerDamageMutationResult damageResult(
            TowerDamageOperation operation,
            OperationOutcome outcome) {
        return new TowerDamageMutationResult(
                outcome,
                operation.eventId(),
                operation.teamId(),
                operation.towerId(),
                operation.attackerLogicalEnemyId(),
                operation.damage(),
                operation.remainingHitPoints(),
                operation.destroyed());
    }

    private static void insertTowerRepairOperation(
            Connection connection,
            UUID operationId,
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            long repairedHitPoints,
            long cost,
            String fingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_tower_repair_operations(
                    operation_id, event_id, team_id, actor_id, tower_id,
                    repaired_hit_points, cost, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, teamId.toString());
            statement.setString(4, actorId.toString());
            statement.setString(5, towerId.toString());
            statement.setLong(6, repairedHitPoints);
            statement.setLong(7, cost);
            statement.setString(8, fingerprint);
            statement.setString(9, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<TowerRepairOperation> loadTowerRepairOperation(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, team_id, actor_id, tower_id,
                       repaired_hit_points, cost, payload_fingerprint
                FROM event_tower_repair_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TowerRepairOperation(
                        uuid(resultSet.getString("event_id")),
                        uuid(resultSet.getString("team_id")),
                        uuid(resultSet.getString("actor_id")),
                        uuid(resultSet.getString("tower_id")),
                        resultSet.getLong("repaired_hit_points"),
                        resultSet.getLong("cost"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingTowerRepairOperation(
            TowerRepairOperation existing,
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            long repairedHitPoints,
            long cost,
            String fingerprint) {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !existing.actorId().equals(actorId)
                || !existing.towerId().equals(towerId)
                || existing.repairedHitPoints() != repairedHitPoints
                || existing.cost() != cost
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The tower-repair operation UUID is already assigned to another payload");
        }
    }

    private static void requireTowerBelongsToTeam(
            Connection connection,
            UUID towerId,
            UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id FROM towers WHERE tower_id = ?
                """)) {
            statement.setString(1, towerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !teamId.toString().equals(resultSet.getString("team_id"))) {
                    throw new PersistenceConflictException(
                            "The tower is missing or belongs to another team");
                }
            }
        }
    }

    private static void insertBattleFundsOperation(
            Connection connection,
            UUID operationId,
            UUID eventId,
            UUID teamId,
            UUID actorId,
            String operationKind,
            long amount,
            String fingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_battle_fund_operations(
                    operation_id, event_id, team_id, actor_id, operation_kind,
                    amount, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, teamId.toString());
            statement.setString(4, actorId == null ? null : actorId.toString());
            statement.setString(5, operationKind);
            statement.setLong(6, amount);
            statement.setString(7, fingerprint);
            statement.setString(8, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<BattleFundsOperation> loadBattleFundsOperation(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, team_id, actor_id, operation_kind, amount, payload_fingerprint
                FROM event_battle_fund_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                String actor = resultSet.getString("actor_id");
                return Optional.of(new BattleFundsOperation(
                        uuid(resultSet.getString("event_id")),
                        uuid(resultSet.getString("team_id")),
                        actor == null ? null : uuid(actor),
                        resultSet.getString("operation_kind"),
                        resultSet.getLong("amount"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingBattleFundsOperation(
            BattleFundsOperation existing,
            UUID eventId,
            UUID teamId,
            UUID actorId,
            String operationKind,
            long amount,
            String fingerprint) {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !Objects.equals(existing.actorId(), actorId)
                || !existing.operationKind().equals(operationKind)
                || existing.amount() != amount
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The battle-funds operation UUID is already assigned to another payload");
        }
    }

    private void issueVictoryResearchCrystals(
            Connection connection,
            DefenseSessionSnapshot terminalSnapshot,
            UUID terminalOperationId,
            Instant issuedAt) throws SQLException {
        TeamProgress beforeVictory = loadTeamProgress(connection, terminalSnapshot.teamId())
                .orElseThrow(() -> new PersistenceConflictException(
                        "Team " + terminalSnapshot.teamId() + " has no progression row"));
        int quantity = rewardSettings.researchCrystalQuantity(
                terminalSnapshot.stageLevel(), beforeVictory.highestClearedLevel());
        if (quantity <= 0) {
            return;
        }
        UUID batchId = deterministicUuid(
                terminalOperationId,
                "RESEARCH_CRYSTAL_BATCH",
                terminalSnapshot.eventId().toString());
        UUID createOperationId = deterministicUuid(
                terminalOperationId,
                "RESEARCH_CRYSTAL_DROP",
                batchId.toString());
        String payload = researchCrystalPayload(
                batchId,
                terminalSnapshot.teamId(),
                quantity);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO research_crystal_batches(
                    batch_id, event_id, team_id, stage_level, issued_quantity,
                    redeemed_quantity, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'ISSUED', ?, ?)
                ON CONFLICT(batch_id) DO NOTHING
                """)) {
            statement.setString(1, batchId.toString());
            statement.setString(2, terminalSnapshot.eventId().toString());
            statement.setString(3, terminalSnapshot.teamId().toString());
            statement.setLong(4, terminalSnapshot.stageLevel());
            statement.setInt(5, quantity);
            statement.setString(6, issuedAt.toString());
            statement.setString(7, issuedAt.toString());
            statement.executeUpdate();
        }
        ResearchCrystalBatch existing = loadResearchCrystalBatch(connection, batchId)
                .orElseThrow(() -> new SQLException(
                        "The research crystal batch was not persisted"));
        if (!existing.eventId().equals(terminalSnapshot.eventId())
                || !existing.teamId().equals(terminalSnapshot.teamId())
                || existing.stageLevel() != terminalSnapshot.stageLevel()
                || existing.issuedQuantity() != quantity) {
            throw new PersistenceConflictException(
                    "The research crystal batch UUID is already assigned to another payload");
        }
        ensureResearchCrystalSegments(connection, batchId, quantity);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_drop_escrow(
                    drop_id, event_id, source_kind, source_id, item_id, item_payload,
                    quantity, claimed_quantity, status, display_entity_id,
                    create_operation_id, created_at, updated_at
                ) VALUES (?, ?, 'ENEMY', ?, 'research_crystal', ?, ?, 0, 'HELD', NULL, ?, ?, ?)
                ON CONFLICT(drop_id) DO NOTHING
                """)) {
            statement.setString(1, batchId.toString());
            statement.setString(2, terminalSnapshot.eventId().toString());
            statement.setString(3, terminalSnapshot.eventId().toString());
            statement.setString(4, payload);
            statement.setInt(5, quantity);
            statement.setString(6, createOperationId.toString());
            statement.setString(7, issuedAt.toString());
            statement.setString(8, issuedAt.toString());
            statement.executeUpdate();
        }
    }

    private static void advanceTeamProgressAfterVictory(
            Connection connection,
            UUID teamId,
            long stageLevel,
            Instant updatedAt) throws SQLException {
        TeamProgress current = loadTeamProgress(connection, teamId).orElseThrow(
                () -> new PersistenceConflictException(
                        "Team " + teamId + " has no progression row"));
        TeamProgress advanced = current.afterVictory(stageLevel);
        if (advanced.equals(current)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE team_progress
                SET highest_cleared_level = ?, unlocked_level = ?, updated_at = ?
                WHERE team_id = ?
                """)) {
            statement.setLong(1, advanced.highestClearedLevel());
            statement.setLong(2, advanced.unlockedLevel());
            statement.setString(3, updatedAt.toString());
            statement.setString(4, teamId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The victory progression update affected no rows");
            }
        }
    }

    private TeamInvitationMutationResult resolveTeamInvitation(
            UUID invitationId,
            UUID inviteeId,
            UUID operationId,
            Instant resolvedAt,
            String operationKind,
            boolean accept) {
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        String fingerprint = managementFingerprint(operationKind, invitationId, inviteeId);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TeamInviteOperation> existing = loadTeamInviteOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingTeamInviteOperation(
                            existing.orElseThrow(),
                            operationKind,
                            inviteeId,
                            fingerprint);
                    TeamInvitation invitation = requireTeamInvitation(
                            connection, existing.orElseThrow().inviteId());
                    return invitationMutation(
                            ManagementOutcome.ALREADY_APPLIED,
                            connection,
                            invitation);
                }
                requireNoActiveEvent(connection, accept
                        ? "accept a team invitation"
                        : "decline a team invitation");
                TeamInvitation invitation = requireTeamInvitation(connection, invitationId);
                if (!invitation.inviteeId().equals(inviteeId)) {
                    throw new PersistenceConflictException(
                            "This invitation is addressed to another player");
                }
                if (invitation.state() != TeamInvitationState.PENDING) {
                    throw new PersistenceConflictException(
                            "This invitation is no longer pending");
                }
                if (!resolvedAt.isBefore(invitation.expiresAt())) {
                    expireInvitation(connection, invitationId, resolvedAt);
                    return invitationMutation(
                            ManagementOutcome.APPLIED,
                            connection,
                            requireTeamInvitation(connection, invitationId));
                }
                if (accept) {
                    TeamRecord team = requireTeam(connection, invitation.teamId());
                    if (findTeamByMember(connection, inviteeId).isPresent()) {
                        throw new PersistenceConflictException(
                                "The invited player already belongs to a team");
                    }
                    if (team.members().size() >= MAX_TEAM_MEMBERS) {
                        throw new PersistenceConflictException(
                                "The team has reached the maximum of " + MAX_TEAM_MEMBERS
                                        + " members");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO team_members(team_id, player_id, role, joined_at)
                            VALUES (?, ?, 'MEMBER', ?)
                            """)) {
                        statement.setString(1, team.id().toString());
                        statement.setString(2, inviteeId.toString());
                        statement.setString(3, resolvedAt.toString());
                        statement.executeUpdate();
                    }
                }
                updateInvitationState(
                        connection,
                        invitationId,
                        accept ? TeamInvitationState.ACCEPTED : TeamInvitationState.DECLINED,
                        resolvedAt);
                insertTeamInviteOperation(
                        connection,
                        operationId,
                        invitationId,
                        inviteeId,
                        operationKind,
                        fingerprint,
                        resolvedAt);
                TeamInvitation updated = requireTeamInvitation(connection, invitationId);
                return invitationMutation(ManagementOutcome.APPLIED, connection, updated);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The invitation cannot change team membership", exception);
            }
            throw failure(
                    accept ? "accept a team invitation" : "decline a team invitation",
                    exception);
        }
    }

    private static TeamInvitationMutationResult invitationMutation(
            ManagementOutcome outcome,
            Connection connection,
            TeamInvitation invitation) throws SQLException {
        return new TeamInvitationMutationResult(
                outcome,
                invitation,
                loadTeam(connection, invitation.teamId()));
    }

    private static void insertTeamInvitation(
            Connection connection,
            TeamInvitation invitation,
            String payloadFingerprint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO team_invites(
                    invite_id, team_id, inviter_id, invitee_id, state,
                    created_at, expires_at, resolved_at, create_payload_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, invitation.id().toString());
            statement.setString(2, invitation.teamId().toString());
            statement.setString(3, invitation.inviterId().toString());
            statement.setString(4, invitation.inviteeId().toString());
            statement.setString(5, invitation.state().name());
            statement.setString(6, invitation.createdAt().toString());
            statement.setString(7, invitation.expiresAt().toString());
            statement.setString(8, nullableInstantString(invitation.resolvedAt()));
            statement.setString(9, payloadFingerprint);
            statement.executeUpdate();
        }
    }

    private static void updateInvitationState(
            Connection connection,
            UUID invitationId,
            TeamInvitationState state,
            Instant resolvedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE team_invites
                SET state = ?, resolved_at = ?
                WHERE invite_id = ? AND state = 'PENDING'
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, resolvedAt.toString());
            statement.setString(3, invitationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The invitation state update affected no rows");
            }
        }
    }

    private static void expireInvitation(
            Connection connection, UUID invitationId, Instant resolvedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE team_invites
                SET state = 'EXPIRED', resolved_at = ?
                WHERE invite_id = ? AND state = 'PENDING'
                """)) {
            statement.setString(1, resolvedAt.toString());
            statement.setString(2, invitationId.toString());
            statement.executeUpdate();
        }
    }

    private static boolean hasPendingInvitation(
            Connection connection, UUID teamId, UUID inviteeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM team_invites
                WHERE team_id = ? AND invitee_id = ? AND state = 'PENDING'
                LIMIT 1
                """)) {
            statement.setString(1, teamId.toString());
            statement.setString(2, inviteeId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static Optional<TeamInvitation> loadTeamInvitation(
            Connection connection, UUID invitationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT invite_id, team_id, inviter_id, invitee_id, state,
                       created_at, expires_at, resolved_at
                FROM team_invites WHERE invite_id = ?
                """)) {
            statement.setString(1, invitationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(teamInvitationFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static TeamInvitation requireTeamInvitation(
            Connection connection, UUID invitationId) throws SQLException {
        return loadTeamInvitation(connection, invitationId).orElseThrow(
                () -> new PersistenceConflictException(
                        "Team invitation " + invitationId + " does not exist"));
    }

    private static TeamInvitation teamInvitationFromRow(ResultSet resultSet) throws SQLException {
        return new TeamInvitation(
                uuid(resultSet.getString("invite_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("inviter_id")),
                uuid(resultSet.getString("invitee_id")),
                TeamInvitationState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("expires_at")),
                nullableInstant(resultSet.getString("resolved_at")));
    }

    private static void insertTeamInviteOperation(
            Connection connection,
            UUID operationId,
            UUID invitationId,
            UUID actorId,
            String operationKind,
            String payloadFingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO team_invite_operations(
                    operation_id, invite_id, actor_id, operation_kind,
                    payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, invitationId.toString());
            statement.setString(3, actorId.toString());
            statement.setString(4, operationKind);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<TeamInviteOperation> loadTeamInviteOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT invite_id, actor_id, operation_kind, payload_fingerprint
                FROM team_invite_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TeamInviteOperation(
                        uuid(resultSet.getString("invite_id")),
                        uuid(resultSet.getString("actor_id")),
                        resultSet.getString("operation_kind"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingTeamInviteOperation(
            TeamInviteOperation operation,
            String operationKind,
            UUID actorId,
            String payloadFingerprint) {
        if (!operation.actorId().equals(actorId)
                || !operation.operationKind().equals(operationKind)
                || !operation.payloadFingerprint().equals(payloadFingerprint)) {
            throw new PersistenceConflictException(
                    "The invitation operation UUID is already assigned to a different payload");
        }
    }

    private static Optional<TeamRecord> findTeamByMember(
            Connection connection, UUID playerId) throws SQLException {
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
        return new TeamRecord(
                teamId,
                ownerId,
                members,
                resultSet.getString("display_name"),
                createdAt);
    }

    private static Optional<TeamRecord> loadTeam(Connection connection, UUID teamId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, owner_player_id, display_name, created_at
                FROM teams WHERE team_id = ?
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

    private static Optional<TeamProgress> loadTeamProgress(
            Connection connection, UUID teamId) throws SQLException {
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

    private static Optional<ResearchCrystalBatch> loadResearchCrystalBatch(
            Connection connection, UUID batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT batch_id, event_id, team_id, stage_level, issued_quantity,
                       redeemed_quantity, state, created_at, updated_at
                FROM research_crystal_batches WHERE batch_id = ?
                """)) {
            statement.setString(1, batchId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(researchCrystalBatchFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<ResearchCrystalSegment> loadResearchCrystalSegment(
            Connection connection,
            UUID batchId,
            int segmentOffset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT segment_quantity, redeemed_quantity
                FROM research_crystal_segments
                WHERE batch_id = ? AND segment_offset = ?
                """)) {
            statement.setString(1, batchId.toString());
            statement.setInt(2, segmentOffset);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new ResearchCrystalSegment(
                                resultSet.getInt("segment_quantity"),
                                resultSet.getInt("redeemed_quantity")))
                        : Optional.empty();
            }
        }
    }

    private static void ensureResearchCrystalSegments(
            Connection connection,
            UUID batchId,
            int issuedQuantity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO research_crystal_segments(
                    batch_id, segment_offset, segment_quantity)
                VALUES (?, ?, ?)
                ON CONFLICT(batch_id, segment_offset) DO NOTHING
                """)) {
            for (int offset = 0; offset < issuedQuantity; offset += RESEARCH_CRYSTAL_SEGMENT_SIZE) {
                statement.setString(1, batchId.toString());
                statement.setInt(2, offset);
                statement.setInt(
                        3,
                        Math.min(
                                RESEARCH_CRYSTAL_SEGMENT_SIZE,
                                issuedQuantity - offset));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static ResearchCrystalBatch researchCrystalBatchFromRow(ResultSet resultSet)
            throws SQLException {
        return new ResearchCrystalBatch(
                uuid(resultSet.getString("batch_id")),
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("team_id")),
                resultSet.getLong("stage_level"),
                resultSet.getInt("issued_quantity"),
                resultSet.getInt("redeemed_quantity"),
                ResearchCrystalBatchStatus.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")));
    }

    private static Optional<ResearchCrystalRedemption> loadResearchCrystalRedemption(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, batch_id, core_id, team_id, actor_id, quantity,
                       payload_fingerprint, segment_offset, segment_quantity, state,
                       prepared_at, applied_at, rolled_back_at
                FROM research_crystal_redemptions WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(researchCrystalRedemptionFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static ResearchCrystalRedemption researchCrystalRedemptionFromRow(
            ResultSet resultSet) throws SQLException {
        return new ResearchCrystalRedemption(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("batch_id")),
                uuid(resultSet.getString("core_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("actor_id")),
                resultSet.getInt("quantity"),
                resultSet.getString("payload_fingerprint"),
                nullableInteger(resultSet, "segment_offset"),
                nullableInteger(resultSet, "segment_quantity"),
                ResearchCrystalRedemptionState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static void insertResearchCrystalRedemption(
            Connection connection,
            ResearchCrystalRedemption redemption) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO research_crystal_redemptions(
                    operation_id, batch_id, core_id, team_id, actor_id, quantity,
                    payload_fingerprint, segment_offset, segment_quantity,
                    state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, redemption.operationId().toString());
            statement.setString(2, redemption.batchId().toString());
            statement.setString(3, redemption.coreId().toString());
            statement.setString(4, redemption.teamId().toString());
            statement.setString(5, redemption.actorId().toString());
            statement.setInt(6, redemption.quantity());
            statement.setString(7, redemption.payloadFingerprint());
            if (redemption.segmentOffset() == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
                statement.setNull(9, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, redemption.segmentOffset());
                statement.setInt(9, redemption.segmentQuantity());
            }
            statement.setString(10, redemption.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static ResearchCrystalRedemptionResult crystalRedemptionResult(
            Connection connection,
            OperationOutcome outcome,
            UUID batchId) throws SQLException {
        ResearchCrystalBatch batch = loadResearchCrystalBatch(connection, batchId)
                .orElseThrow(() -> new SQLException("The crystal batch disappeared"));
        TeamProgress progress = loadTeamProgress(connection, batch.teamId())
                .orElseThrow(() -> new SQLException("The crystal team progression disappeared"));
        return new ResearchCrystalRedemptionResult(outcome, progress, batch);
    }

    private static void requireMatchingCrystalRedemption(
            ResearchCrystalRedemption redemption,
            UUID operationId,
            UUID batchId,
            UUID coreId,
            UUID actorId,
            int quantity,
            String fingerprint) {
        if (!redemption.operationId().equals(operationId)
                || !redemption.batchId().equals(batchId)
                || !redemption.coreId().equals(coreId)
                || !redemption.actorId().equals(actorId)
                || redemption.quantity() != quantity
                || !redemption.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The research crystal redemption UUID is already assigned to another payload");
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
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM team_resource_balances
                WHERE team_id = ? AND balance > 0 LIMIT 1
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new PersistenceConflictException(
                            "A team with resource wallet points cannot be disbanded");
                }
            }
        }
        if (ResourceVoucherRepository.hasLiveVouchers(connection, teamId)) {
            throw new PersistenceConflictException(
                    "A team with an unredeemed resource voucher cannot be disbanded");
        }
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
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM team_resource_balances
                WHERE team_id = ? AND balance > 0
                LIMIT 1
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new PersistenceConflictException(
                            "A team with a non-zero resource wallet cannot be deleted");
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

    private static void insertTeamProfileOperation(
            Connection connection,
            UUID operationId,
            UUID teamId,
            UUID actorId,
            String operationKind,
            String payloadFingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO team_profile_operations(
                    operation_id, team_id, actor_id, operation_kind,
                    payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, actorId.toString());
            statement.setString(4, operationKind);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<TeamProfileOperation> loadTeamProfileOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, actor_id, operation_kind, payload_fingerprint
                FROM team_profile_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TeamProfileOperation(
                        uuid(resultSet.getString("team_id")),
                        uuid(resultSet.getString("actor_id")),
                        resultSet.getString("operation_kind"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireMatchingTeamProfileOperation(
            TeamProfileOperation operation,
            UUID teamId,
            UUID actorId,
            String payloadFingerprint) {
        if (!operation.teamId().equals(teamId)
                || !operation.actorId().equals(actorId)
                || !operation.operationKind().equals("TEAM_RENAME")
                || !operation.payloadFingerprint().equals(payloadFingerprint)) {
            throw new PersistenceConflictException(
                    "The team profile operation UUID is already assigned to a different payload");
        }
    }

    private static void insertCoreRepairOperation(
            Connection connection,
            CoreRepairOperation operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO core_repair_operations(
                    operation_id, core_id, team_id, actor_id, expected_current_hp,
                    repair_amount, defense_point_cost, payment_mode, vanilla_material,
                    vanilla_material_amount, legacy_defense_shard_amount,
                    payload_fingerprint, state, prepared_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, operation.coreId().toString());
            statement.setString(3, operation.teamId().toString());
            statement.setString(4, operation.actorId().toString());
            statement.setLong(5, operation.expectedCurrentHitPoints());
            statement.setLong(6, operation.repairAmount());
            statement.setLong(7, operation.defensePointCost());
            statement.setString(8, operation.paymentMode().name());
            statement.setString(9, operation.vanillaMaterial());
            statement.setLong(10, operation.vanillaMaterialAmount());
            statement.setLong(11, operation.legacyDefenseShardAmount());
            statement.setString(12, operation.payloadFingerprint());
            statement.setString(13, operation.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static Optional<CoreRepairOperation> loadCoreRepairOperation(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, core_id, team_id, actor_id, expected_current_hp,
                       repair_amount, defense_point_cost, payment_mode, vanilla_material,
                       vanilla_material_amount, legacy_defense_shard_amount,
                       payload_fingerprint, state, prepared_at,
                       applied_at, rolled_back_at
                FROM core_repair_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(coreRepairOperationFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static CoreRepairOperation coreRepairOperationFromRow(ResultSet resultSet)
            throws SQLException {
        return new CoreRepairOperation(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("core_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("actor_id")),
                resultSet.getLong("expected_current_hp"),
                resultSet.getLong("repair_amount"),
                resultSet.getLong("defense_point_cost"),
                PaymentMode.valueOf(resultSet.getString("payment_mode")),
                resultSet.getString("vanilla_material"),
                resultSet.getLong("vanilla_material_amount"),
                resultSet.getLong("legacy_defense_shard_amount"),
                resultSet.getString("payload_fingerprint"),
                CoreRepairOperationState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static void requireMatchingCoreRepairOperation(
            CoreRepairOperation existing,
            UUID coreId,
            UUID actorId,
            long amount,
            long defensePointCost,
            PaymentMode paymentMode,
            String vanillaMaterial,
            long vanillaMaterialAmount,
            long legacyDefenseShardAmount,
            String payloadFingerprint) {
        if (!existing.coreId().equals(coreId)
                || !existing.actorId().equals(actorId)
                || existing.repairAmount() != amount
                || existing.defensePointCost() != defensePointCost
                || existing.paymentMode() != paymentMode
                || !existing.vanillaMaterial().equals(vanillaMaterial)
                || existing.vanillaMaterialAmount() != vanillaMaterialAmount
                || existing.legacyDefenseShardAmount() != legacyDefenseShardAmount
                || !existing.payloadFingerprint().equals(payloadFingerprint)) {
            throw new PersistenceConflictException(
                    "The core repair operation UUID is already assigned to another payload");
        }
    }

    private static String expectedReceiptMaterial(CoreRepairOperation operation) {
        return operation.paymentMode() == PaymentMode.LEGACY_ITEMS
                && operation.legacyDefenseShardAmount() > 0L
                ? "CORE_REPAIR_BUNDLE"
                : operation.vanillaMaterial();
    }

    private static long expectedReceiptQuantity(CoreRepairOperation operation) {
        return Math.addExact(operation.vanillaMaterialAmount(), operation.legacyDefenseShardAmount());
    }

    private static void updateCoreRepairOperationState(
            Connection connection,
            UUID operationId,
            CoreRepairOperationState state,
            Instant at) throws SQLException {
        String sql = switch (state) {
            case APPLIED -> """
                    UPDATE core_repair_operations
                    SET state = 'APPLIED', applied_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """;
            case ROLLED_BACK -> """
                    UPDATE core_repair_operations
                    SET state = 'ROLLED_BACK', rolled_back_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """;
            case PREPARED -> throw new IllegalArgumentException(
                    "A core repair cannot transition back to PREPARED");
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, at.toString());
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException(
                        "The core repair state changed concurrently");
            }
        }
    }

    private static void insertCoreRepairReceipt(
            Connection connection,
            CoreRepairReceipt receipt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO core_repair_receipts(
                    operation_id, player_id, material, quantity, state, reserved_at)
                VALUES (?, ?, ?, ?, 'RESERVED', ?)
                """)) {
            statement.setString(1, receipt.operationId().toString());
            statement.setString(2, receipt.playerId().toString());
            statement.setString(3, receipt.material());
            statement.setLong(4, receipt.quantity());
            statement.setString(5, receipt.reservedAt().toString());
            statement.executeUpdate();
        }
    }

    private static Optional<CoreRepairReceipt> loadCoreRepairReceipt(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, player_id, material, quantity, state,
                       reserved_at, resolved_at
                FROM core_repair_receipts WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CoreRepairReceipt(
                        uuid(resultSet.getString("operation_id")),
                        uuid(resultSet.getString("player_id")),
                        resultSet.getString("material"),
                        resultSet.getLong("quantity"),
                        CoreRepairReceiptState.valueOf(resultSet.getString("state")),
                        instant(resultSet.getString("reserved_at")),
                        nullableInstant(resultSet.getString("resolved_at"))));
            }
        }
    }

    private static void updateCoreRepairReceiptState(
            Connection connection,
            UUID operationId,
            CoreRepairReceiptState state,
            Instant at) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE core_repair_receipts
                SET state = ?, resolved_at = ?
                WHERE operation_id = ?
                  AND ((? = 'CLEARED' AND state IN ('SECURED', 'CLEAR_PENDING'))
                       OR (? = 'RESTORED' AND state IN ('RESERVED', 'SECURED', 'RETURN_PENDING'))
                       OR (? = 'RETURN_PENDING' AND state IN ('RESERVED', 'SECURED'))
                       OR (? = 'CLEAR_PENDING' AND state = 'SECURED'))
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, at.toString());
            statement.setString(3, operationId.toString());
            statement.setString(4, state.name());
            statement.setString(5, state.name());
            statement.setString(6, state.name());
            statement.setString(7, state.name());
            statement.executeUpdate();
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
        insertManagementOperation(
                connection,
                operationId,
                resourceType,
                resourceId,
                operationKind,
                payloadFingerprint,
                PaymentMode.LEGACY_ITEMS,
                appliedAt);
    }

    private static void insertManagementOperation(
            Connection connection,
            UUID operationId,
            String resourceType,
            UUID resourceId,
            String operationKind,
            String payloadFingerprint,
            PaymentMode paymentMode,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO management_operations(
                    operation_id, resource_type, resource_id, operation_kind,
                    payload_fingerprint, payment_mode, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, resourceType);
            statement.setString(3, resourceId.toString());
            statement.setString(4, operationKind);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, paymentMode.name());
            statement.setString(7, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<ManagementOperation> loadManagementOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource_type, resource_id, operation_kind, payload_fingerprint, payment_mode
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
                        resultSet.getString("payload_fingerprint"),
                        PaymentMode.valueOf(resultSet.getString("payment_mode"))));
            }
        }
    }

    private static void requireMatchingManagementOperation(
            ManagementOperation operation,
            String resourceType,
            UUID resourceId,
            String operationKind,
            String payloadFingerprint) {
        requireMatchingManagementOperation(
                operation,
                resourceType,
                resourceId,
                operationKind,
                payloadFingerprint,
                PaymentMode.LEGACY_ITEMS);
    }

    private static void requireMatchingManagementOperation(
            ManagementOperation operation,
            String resourceType,
            UUID resourceId,
            String operationKind,
            String payloadFingerprint,
            PaymentMode paymentMode) {
        if (!operation.resourceType().equals(resourceType)
                || !operation.resourceId().equals(resourceId)
                || !operation.operationKind().equals(operationKind)
                || !operation.payloadFingerprint().equals(payloadFingerprint)
                || operation.paymentMode() != paymentMode) {
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

    private List<CorePlacement> loadCorePlacementsByState(CorePlacementState state) {
        Objects.requireNonNull(state, "state");
        return read("load core placements", connection -> {
            List<CorePlacement> placements = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT operation_id, item_id, core_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, max_hp, minimum_core_distance,
                           rebuilding_destroyed_core, relocating_existing_core,
                           previous_block_data, state,
                           prepared_at, applied_at, rolled_back_at
                    FROM core_placement_operations
                    WHERE state = ?
                    ORDER BY prepared_at, operation_id
                    """)) {
                statement.setString(1, state.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        placements.add(corePlacementFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(placements);
        });
    }

    private static void insertEmptyResourceBalances(
            Connection connection, UUID teamId, Instant createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO team_resource_balances(team_id, resource_type, balance, updated_at)
                VALUES (?, ?, 0, ?)
                """)) {
            for (ResourceType resourceType : ResourceType.values()) {
                statement.setString(1, teamId.toString());
                statement.setString(2, resourceType.name());
                statement.setString(3, createdAt.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Optional<CorePlacement> loadCorePlacement(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, item_id, core_id, actor_id, team_id, world_id,
                       block_x, block_y, block_z, max_hp, minimum_core_distance,
                       rebuilding_destroyed_core, relocating_existing_core,
                       previous_block_data, state,
                       prepared_at, applied_at, rolled_back_at
                FROM core_placement_operations
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(corePlacementFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static void insertCorePlacement(
            Connection connection, CorePlacement placement) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO core_placement_operations(
                    operation_id, item_id, core_id, actor_id, team_id, world_id,
                    block_x, block_y, block_z, max_hp, minimum_core_distance,
                    rebuilding_destroyed_core, relocating_existing_core,
                    previous_block_data, state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, placement.operationId().toString());
            statement.setString(2, placement.itemId().toString());
            statement.setString(3, placement.coreId().toString());
            statement.setString(4, placement.actorId().toString());
            statement.setString(5, placement.teamId().toString());
            statement.setString(6, placement.worldId().toString());
            statement.setInt(7, placement.blockX());
            statement.setInt(8, placement.blockY());
            statement.setInt(9, placement.blockZ());
            statement.setLong(10, placement.maximumHitPoints());
            statement.setDouble(11, placement.minimumCoreDistance());
            statement.setInt(12, placement.rebuildingDestroyedCore() ? 1 : 0);
            statement.setInt(13, placement.relocatingExistingCore() ? 1 : 0);
            statement.setString(14, placement.previousBlockData());
            statement.setString(15, placement.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void updateCorePlacementState(
            Connection connection, CorePlacement placement) throws SQLException {
        String sql;
        if (placement.state() == CorePlacementState.APPLIED) {
            sql = """
                    UPDATE core_placement_operations
                    SET state = 'APPLIED', applied_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """;
        } else if (placement.state() == CorePlacementState.ROLLED_BACK) {
            sql = """
                    UPDATE core_placement_operations
                    SET state = 'ROLLED_BACK', rolled_back_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """;
        } else {
            throw new IllegalArgumentException("Only terminal placement states can be persisted");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(
                    1,
                    placement.state() == CorePlacementState.APPLIED
                            ? placement.appliedAt().toString()
                            : placement.rolledBackAt().toString());
            statement.setString(2, placement.operationId().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The core placement state update affected no rows");
            }
        }
    }

    private static CorePlacement corePlacementFromRow(ResultSet resultSet) throws SQLException {
        return new CorePlacement(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("item_id")),
                uuid(resultSet.getString("core_id")),
                uuid(resultSet.getString("actor_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("world_id")),
                resultSet.getInt("block_x"),
                resultSet.getInt("block_y"),
                resultSet.getInt("block_z"),
                resultSet.getLong("max_hp"),
                resultSet.getDouble("minimum_core_distance"),
                resultSet.getInt("rebuilding_destroyed_core") != 0,
                resultSet.getInt("relocating_existing_core") != 0,
                resultSet.getString("previous_block_data"),
                CorePlacementState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static void requireMatchingCorePlacement(
            CorePlacement existing, CorePlacement requested) {
        if (!existing.itemId().equals(requested.itemId())
                || !existing.coreId().equals(requested.coreId())
                || !existing.actorId().equals(requested.actorId())
                || !existing.teamId().equals(requested.teamId())
                || !existing.worldId().equals(requested.worldId())
                || existing.blockX() != requested.blockX()
                || existing.blockY() != requested.blockY()
                || existing.blockZ() != requested.blockZ()
                || existing.maximumHitPoints() != requested.maximumHitPoints()
                || Double.compare(
                                existing.minimumCoreDistance(), requested.minimumCoreDistance())
                        != 0
                || existing.rebuildingDestroyedCore() != requested.rebuildingDestroyedCore()
                || existing.relocatingExistingCore() != requested.relocatingExistingCore()
                || !existing.previousBlockData().equals(requested.previousBlockData())) {
            throw new PersistenceConflictException(
                    "The operation UUID was reused with a different core placement payload");
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

    private static String researchCrystalPayload(
            UUID batchId,
            UUID teamId,
            int issuedQuantity) {
        return "research_crystal:v2:" + batchId + ":" + teamId + ":" + issuedQuantity;
    }

    private static String crystalRedemptionFingerprint(
            UUID batchId,
            UUID coreId,
            UUID actorId,
            int quantity) {
        return "CRYSTAL|" + batchId + "|" + coreId + "|" + actorId + "|" + quantity;
    }

    private static String crystalRedemptionFingerprint(
            UUID batchId,
            UUID coreId,
            UUID actorId,
            UUID itemTeamId,
            int itemIssuedQuantity,
            Integer itemSegmentOffset,
            Integer itemSegmentQuantity,
            int quantity) {
        return "CRYSTAL|" + batchId + "|" + coreId + "|" + actorId + "|"
                + itemTeamId + "|" + itemIssuedQuantity + "|"
                + (itemSegmentOffset == null ? "legacy" : itemSegmentOffset)
                + "|" + (itemSegmentQuantity == null ? "legacy" : itemSegmentQuantity)
                + "|" + quantity;
    }

    private static UUID deterministicUuid(UUID base, String namespace, String value) {
        return UUID.nameUUIDFromBytes(
                (base + "|" + namespace + "|" + value).getBytes(StandardCharsets.UTF_8));
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

    private static Instant nullableInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String nullableInstantString(Instant value) {
        return value == null ? null : value.toString();
    }

    private record ResearchCrystalSegment(int segmentQuantity, int redeemedQuantity) {
        private int remainingQuantity() {
            return segmentQuantity - redeemedQuantity;
        }
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
            String payloadFingerprint,
            PaymentMode paymentMode) {
    }

    private record TeamProfileOperation(
            UUID teamId,
            UUID actorId,
            String operationKind,
            String payloadFingerprint) {
    }

    private record TeamInviteOperation(
            UUID inviteId,
            UUID actorId,
            String operationKind,
            String payloadFingerprint) {
    }

    private record BattleFundsOperation(
            UUID eventId,
            UUID teamId,
            UUID actorId,
            String operationKind,
            long amount,
            String payloadFingerprint) {
    }

    private record BattleBoostOperation(
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            BattleBoostKind kind,
            long cost,
            double boostMultiplier,
            String payloadFingerprint) {
    }

    private record TowerRepairOperation(
            UUID eventId,
            UUID teamId,
            UUID actorId,
            UUID towerId,
            long repairedHitPoints,
            long cost,
            String payloadFingerprint) {
    }

    private record TowerDamageOperation(
            UUID eventId,
            UUID teamId,
            UUID attackerLogicalEnemyId,
            UUID towerId,
            long damage,
            long remainingHitPoints,
            boolean destroyed,
            String payloadFingerprint) {
    }

    private enum OperationKind {
        TRANSITION,
        TERMINATE,
        RECOVER
    }
}
