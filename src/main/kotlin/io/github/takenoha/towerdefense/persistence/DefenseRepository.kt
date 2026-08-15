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
private const val TERMINAL_PHASES_SQL = "('VICTORY', 'DEFEAT', 'ABORTED', 'RECOVERY')"
private const val RESEARCH_CRYSTAL_SEGMENT_SIZE = 64
private val DEFAULT_TEAM_QUEUE_RETENTION = Duration.ofDays(7L)

class DefenseRepository(
    private val database: Database,
    private val teamQueueRetention: Duration,
    private val rewardSettings: RewardSettings,
) {
    init {
        Objects.requireNonNull(database, "database")
        Objects.requireNonNull(teamQueueRetention, "teamQueueRetention")
        Objects.requireNonNull(rewardSettings, "rewardSettings")
        if (teamQueueRetention.isZero || teamQueueRetention.isNegative) throw IllegalArgumentException("teamQueueRetention must be positive")
    }
    constructor(database: Database) : this(database, DEFAULT_TEAM_QUEUE_RETENTION, RewardSettings.defaults())
    constructor(database: Database, teamQueueRetention: Duration) : this(database, teamQueueRetention, RewardSettings.defaults())
    constructor(database: Database, rewardSettings: RewardSettings) : this(database, Objects.requireNonNull(rewardSettings, "rewardSettings").teamQueueRetention(), rewardSettings)
    companion object {
        const val MAX_TEAM_MEMBERS: Int = 8
        @JvmField val DEFAULT_INVITATION_RETENTION: Duration = Duration.ofDays(7L)
    }

/** Creates a one-player team and its mandatory owner membership atomically. */
    fun createSoloTeam(teamId: UUID, ownerPlayerId: UUID, createdAt: Instant): TeamRecord {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(createdAt, "createdAt");
        try {
            return database.inImmediateTransaction({ connection ->
                connection.prepareStatement("""
                        INSERT INTO teams(team_id, owner_player_id, display_name, created_at)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent()).use { statement ->
                    statement.setString(1, teamId.toString());
                    statement.setString(2, ownerPlayerId.toString());
                    statement.setString(3, TeamRecord.DEFAULT_DISPLAY_NAME);
                    statement.setString(4, createdAt.toString());
                    statement.executeUpdate();

}
                connection.prepareStatement("""
                        INSERT INTO team_members(team_id, player_id, role, joined_at)
                        VALUES (?, ?, 'OWNER', ?)
                        """.trimIndent()).use { statement ->
                    statement.setString(1, teamId.toString());
                    statement.setString(2, ownerPlayerId.toString());
                    statement.setString(3, createdAt.toString());
                    statement.executeUpdate();

}
                connection.prepareStatement("""
                        INSERT INTO team_progress(
                            team_id, highest_cleared_level, unlocked_level, research_points, updated_at
                        ) VALUES (?, 0, 1, 0, ?)
                        """.trimIndent()).use { statement ->
                    statement.setString(1, teamId.toString());
                    statement.setString(2, createdAt.toString());
                    statement.executeUpdate();

}
                connection.prepareStatement("""
                        INSERT INTO tower_research(team_id, tower_type, research_level, updated_at)
                        VALUES (?, ?, 1, ?)
                        """.trimIndent()).use { statement ->
                    for (towerType in TowerType.values()) {
                        statement.setString(1, teamId.toString());
                        statement.setString(2, towerType.id());
                        statement.setString(3, createdAt.toString());
                        statement.addBatch();
                    }
                    statement.executeBatch();

}
                insertEmptyResourceBalances(connection, teamId, createdAt);
                return@inImmediateTransaction TeamRecord(teamId, ownerPlayerId, setOf(ownerPlayerId), createdAt)
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The team or owner already belongs to a persisted team", exception);
            }
            throw failure("create a solo team", exception);
        }
    }

    fun findTeam(teamId: UUID): Optional<TeamRecord> {
        Objects.requireNonNull(teamId, "teamId");
        return read("load a team", { connection -> loadTeam(connection, teamId) });
    }

    /** Looks up the deterministic solo team owned by a player. */
    fun findTeamByOwner(ownerId: UUID): Optional<TeamRecord> {
        Objects.requireNonNull(ownerId, "ownerId");
        return read("load a team by owner", { connection ->
            connection.prepareStatement("""
                    SELECT team_id FROM teams WHERE owner_player_id = ?
                    """.trimIndent()).use { statement ->
                statement.setString(1, ownerId.toString());
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@read Optional.empty();
                    }
                    return@read loadTeam(connection, uuid(resultSet.getString("team_id")));

}

}
        });
    }

    /** Looks up the team to which a player currently belongs. */
    fun findTeamByMember(playerId: UUID): Optional<TeamRecord> {
        Objects.requireNonNull(playerId, "playerId");
        return read("load a team by member", { connection ->
            return@read findTeamByMember(connection, playerId);
        });
    }

    /** Renames a team through an owner-authorized, UUID-idempotent profile mutation. */
    fun renameTeam(teamId: UUID, actorId: UUID, displayName: String, operationId: UUID, renamedAt: Instant): TeamMutationResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        var normalizedName = TeamRecord.normalizeDisplayName(displayName);
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(renamedAt, "renamedAt");
        var fingerprint = managementFingerprint(
                "TEAM_RENAME", teamId, actorId, normalizedName);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadTeamProfileOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingTeamProfileOperation(
                            existing.orElseThrow(), teamId, actorId, fingerprint);
                    return@inImmediateTransaction TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "rename a team");
                var team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                connection.prepareStatement("""
                        UPDATE teams SET display_name = ? WHERE team_id = ?
                        """.trimIndent()).use { statement ->
                    statement.setString(1, normalizedName);
                    statement.setString(2, teamId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The team rename affected no rows");
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
                return@inImmediateTransaction TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (exception: SQLException) {
            throw failure("rename a team", exception);
        }
    }

    /** Lists unexpired invitations and durably expires stale pending rows for this recipient. */
    fun findPendingTeamInvitations(inviteeId: UUID, now: Instant): List<TeamInvitation> {
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(now, "now");
        try {
            return database.inImmediateTransaction({ connection ->
                var invitations = ArrayList<TeamInvitation>();
                var loaded = ArrayList<TeamInvitation>();
                connection.prepareStatement("""
                        SELECT invite_id, team_id, inviter_id, invitee_id, state,
                               created_at, expires_at, resolved_at
                        FROM team_invites
                        WHERE invitee_id = ? AND state = 'PENDING'
                        ORDER BY created_at, invite_id
                        """.trimIndent()).use { statement ->
                    statement.setString(1, inviteeId.toString());
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            loaded.add(teamInvitationFromRow(resultSet));
                        }

}

}
                for (invitation in loaded) {
                    if (invitation.isPendingAt(now)) {
                        invitations.add(invitation);
                    } else {
                        expireInvitation(connection, invitation.id(), now);
                    }
                }
                return@inImmediateTransaction java.util.List.copyOf(invitations);
            });
        } catch (exception: SQLException) {
            throw failure("load pending team invitations", exception);
        }
    }

    /** Loads one invitation for reconnect-aware status checks and recovery tooling. */
    fun findTeamInvitation(invitationId: UUID): Optional<TeamInvitation> {
        Objects.requireNonNull(invitationId, "invitationId");
        return read(
                "load a team invitation",
                { connection -> loadTeamInvitation(connection, invitationId) });
    }

    /** Creates an owner-authorized invitation that remains valid while both players are offline. */
    fun createTeamInvitation(teamId: UUID, actorId: UUID, inviteeId: UUID, invitationId: UUID, operationId: UUID, createdAt: Instant, expiresAt: Instant): TeamInvitationMutationResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw IllegalArgumentException("Invitation expiration must be after creation");
        }
        var fingerprint = managementFingerprint(
                "TEAM_INVITE_CREATE",
                teamId,
                actorId,
                inviteeId,
                invitationId,
                expiresAt);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadTeamInviteOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingTeamInviteOperation(
                            existing.orElseThrow(),
                            "TEAM_INVITE_CREATE",
                            actorId,
                            fingerprint);
                    var invitation = requireTeamInvitation(
                            connection, existing.orElseThrow().inviteId());
                    return@inImmediateTransaction invitationMutation(
                            ManagementOutcome.ALREADY_APPLIED,
                            connection,
                            invitation);
                }
                requireNoActiveEvent(connection, "create a team invitation");
                var team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                if (actorId.equals(inviteeId)) {
                    throw PersistenceConflictException("A team owner cannot invite themselves");
                }
                if (team.members().size >= MAX_TEAM_MEMBERS) {
                    throw PersistenceConflictException(
                            "The team has reached the maximum of " + MAX_TEAM_MEMBERS + " members");
                }
                if (findTeamByMember(connection, inviteeId).isPresent()) {
                    throw PersistenceConflictException(
                            "The invited player already belongs to a team");
                }
                if (hasPendingInvitation(connection, teamId, inviteeId)) {
                    throw PersistenceConflictException(
                            "This player already has a pending invitation for the team");
                }
                var invitation = TeamInvitation(
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
                return@inImmediateTransaction invitationMutation(ManagementOutcome.APPLIED, connection, invitation);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The invitation conflicts with an existing team or invitation", exception);
            }
            throw failure("create a team invitation", exception);
        }
    }

    /** Accepts a pending invitation for the invited player and adds them atomically. */
    fun acceptTeamInvitation(invitationId: UUID, inviteeId: UUID, operationId: UUID, acceptedAt: Instant): TeamInvitationMutationResult {
        var result = resolveTeamInvitation(
                invitationId,
                inviteeId,
                operationId,
                acceptedAt,
                "TEAM_INVITE_ACCEPT",
                true);
        if (result.invitation.state() == TeamInvitationState.EXPIRED) {
            throw PersistenceConflictException("This invitation has expired");
        }
        return result;
    }

    /** Declines a pending invitation without changing team membership. */
    fun declineTeamInvitation(invitationId: UUID, inviteeId: UUID, operationId: UUID, declinedAt: Instant): TeamInvitationMutationResult {
        var result = resolveTeamInvitation(
                invitationId,
                inviteeId,
                operationId,
                declinedAt,
                "TEAM_INVITE_DECLINE",
                false);
        if (result.invitation.state() == TeamInvitationState.EXPIRED) {
            throw PersistenceConflictException("This invitation has expired");
        }
        return result;
    }

    /** Loads the durable team progression snapshot used by repair quotes and future research. */
    fun loadTeamProgress(teamId: UUID): TeamProgress {
        Objects.requireNonNull(teamId, "teamId");
        return read(
                "load team progression",
                { connection -> loadTeamProgress(connection, teamId).orElseThrow { PersistenceConflictException(
                                "Team " + teamId + " has no progression row") } });
    }

    /** Loads one immutable research-crystal issuance batch. */
    fun findResearchCrystalBatch(batchId: UUID): Optional<ResearchCrystalBatch> {
        Objects.requireNonNull(batchId, "batchId");
        return read(
                "load a research crystal batch",
                { connection -> loadResearchCrystalBatch(connection, batchId) });
    }

    /** Loads one redemption receipt so the Paper inventory handoff can recover after a restart. */
    fun findResearchCrystalRedemption(operationId: UUID): Optional<ResearchCrystalRedemption> {
        Objects.requireNonNull(operationId, "operationId");
        return read(
                "load a research crystal redemption",
                { connection -> loadResearchCrystalRedemption(connection, operationId) });
    }

    /**
     * Reserves a team-bound crystal redemption before the Paper inventory item is removed.
     *
     * <p>The returned operation is the durable receipt for the physical handoff. Calling this
     * method again with the same UUID and payload returns the original reservation.</p>
     */
    fun prepareResearchCrystalRedemption(batchId: UUID, coreId: UUID, actorId: UUID, quantity: Int, operationId: UUID, preparedAt: Instant): ResearchCrystalRedemption {
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
    fun prepareResearchCrystalRedemption(batchId: UUID, coreId: UUID, actorId: UUID, itemTeamId: UUID?, itemIssuedQuantity: Int, quantity: Int, operationId: UUID, preparedAt: Instant): ResearchCrystalRedemption {
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
    fun prepareResearchCrystalRedemption(batchId: UUID, coreId: UUID, actorId: UUID, itemTeamId: UUID?, itemIssuedQuantity: Int, itemSegmentOffset: Int?, itemSegmentQuantity: Int?, quantity: Int, operationId: UUID, preparedAt: Instant): ResearchCrystalRedemption {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (quantity <= 0) {
            throw IllegalArgumentException("quantity must be positive");
        }
        if (itemTeamId != null && itemIssuedQuantity <= 0) {
            throw IllegalArgumentException("itemIssuedQuantity must be positive");
        }
        if ((itemSegmentOffset == null) != (itemSegmentQuantity == null)) {
            throw IllegalArgumentException(
                    "itemSegmentOffset and itemSegmentQuantity must be supplied together");
        }
        if (itemSegmentOffset != null && itemTeamId == null) {
            throw IllegalArgumentException(
                    "an issued research crystal segment requires team metadata");
        }
        if (itemSegmentOffset != null
                && (itemSegmentOffset < 0
                        || itemSegmentQuantity!! <= 0
                        || itemSegmentQuantity > RESEARCH_CRYSTAL_SEGMENT_SIZE)) {
            throw IllegalArgumentException("research crystal item segment is invalid");
        }
        var fingerprint = if (itemTeamId == null) {
            crystalRedemptionFingerprint(batchId, coreId, actorId, quantity)
        } else {
            crystalRedemptionFingerprint(
                batchId,
                coreId,
                actorId,
                itemTeamId,
                itemIssuedQuantity,
                itemSegmentOffset,
                itemSegmentQuantity,
                quantity)
        }
        try {
            return database.inImmediateTransaction({ connection ->
                var existing =
                        loadResearchCrystalRedemption(connection, operationId);
                if (existing.isPresent()) {
                    var redemption = existing.orElseThrow();
                    requireMatchingCrystalRedemption(
                            redemption,
                            operationId,
                            batchId,
                            coreId,
                            actorId,
                            quantity,
                            fingerprint);
                    return@inImmediateTransaction redemption;
                }
                requireNoActiveEvent(connection, "redeem research crystals");
                var core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                var batch = loadResearchCrystalBatch(connection, batchId)
                        .orElseThrow { PersistenceConflictException(
                                "Unknown research crystal batch " + batchId) };
                if (!batch.teamId().equals(core.teamId())) {
                    throw PersistenceConflictException(
                            "Research crystals can only be redeemed at their source team's core");
                }
                if (itemTeamId != null && !batch.teamId().equals(itemTeamId)) {
                    throw PersistenceConflictException(
                            "The research crystal PDC team does not match its issuance batch");
                }
                if (itemIssuedQuantity > 0 && batch.issuedQuantity() != itemIssuedQuantity) {
                    throw PersistenceConflictException(
                            "The research crystal PDC issuance quantity is invalid");
                }
                if (itemSegmentOffset != null) {
                    if (itemSegmentOffset.toLong() + itemSegmentQuantity!! > batch.issuedQuantity()) {
                        throw PersistenceConflictException(
                                "The research crystal PDC segment is outside its batch");
                    }
                    var segment = loadResearchCrystalSegment(
                                    connection, batchId, itemSegmentOffset)
                            .orElseThrow { PersistenceConflictException(
                                    "The research crystal PDC segment is not issued") };
                    if (segment.segmentQuantity() != itemSegmentQuantity
                            || quantity > segment.remainingQuantity()) {
                        throw PersistenceConflictException(
                                "The research crystal PDC segment has no remaining quantity");
                    }
                }
                if (batch.status() == ResearchCrystalBatchStatus.VOIDED
                        || quantity > batch.remainingQuantity()) {
                    throw PersistenceConflictException(
                            "The research crystal batch has no remaining redeemable quantity");
                }
                var redemption = ResearchCrystalRedemption(
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
                return@inImmediateTransaction redemption;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The research crystal redemption conflicts with persisted data", exception);
            }
            throw failure("prepare a research crystal redemption", exception);
        }
    }

    /** Applies a prepared crystal redemption and credits the team's research points atomically. */
    fun applyResearchCrystalRedemption(operationId: UUID, appliedAt: Instant): ResearchCrystalRedemptionResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var redemption = loadResearchCrystalRedemption(
                                connection, operationId)
                        .orElseThrow { PersistenceConflictException(
                                "Unknown research crystal redemption " + operationId) };
                if (redemption.state() == ResearchCrystalRedemptionState.APPLIED) {
                    return@inImmediateTransaction crystalRedemptionResult(
                            connection, OperationOutcome.ALREADY_APPLIED, redemption.batchId());
                }
                if (redemption.state() == ResearchCrystalRedemptionState.ROLLED_BACK) {
                    throw PersistenceConflictException(
                            "The research crystal redemption was already rolled back");
                }
                requireNoActiveEvent(connection, "apply a research crystal redemption");
                var core = requireCore(connection, redemption.coreId());
                requireTeamMember(connection, core.teamId(), redemption.actorId());
                if (!core.teamId().equals(redemption.teamId())) {
                    throw PersistenceConflictException(
                            "The redemption team no longer matches the core");
                }
                var batch = loadResearchCrystalBatch(
                                connection, redemption.batchId())
                        .orElseThrow { PersistenceConflictException(
                                "The research crystal batch disappeared") };
                if (batch.status() == ResearchCrystalBatchStatus.VOIDED
                        || redemption.quantity() > batch.remainingQuantity()) {
                    throw PersistenceConflictException(
                            "The research crystal batch was already exhausted or voided");
                }
                if (redemption.segmentOffset() != null) {
                    var segment = loadResearchCrystalSegment(
                                    connection,
                                    redemption.batchId(),
                                    redemption.segmentOffset())
                            .orElseThrow { PersistenceConflictException(
                                    "The research crystal redemption segment disappeared") };
                    if (!Objects.equals(
                                segment.segmentQuantity(), redemption.segmentQuantity())
                            || redemption.quantity() > segment.remainingQuantity()) {
                        throw PersistenceConflictException(
                                "The research crystal redemption segment was already consumed");
                    }
                    connection.prepareStatement("""
                            UPDATE research_crystal_segments
                            SET redeemed_quantity = redeemed_quantity + ?
                            WHERE batch_id = ? AND segment_offset = ?
                              AND redeemed_quantity + ? <= segment_quantity
                            """.trimIndent()).use { statement ->
                        statement.setInt(1, redemption.quantity());
                        statement.setString(2, redemption.batchId().toString());
                        statement.setInt(3, redemption.segmentOffset());
                        statement.setInt(4, redemption.quantity());
                        if (statement.executeUpdate() != 1) {
                            throw PersistenceConflictException(
                                    "The research crystal redemption segment was concurrently resolved");
                        }

}
                }
                var progress = loadTeamProgress(connection, redemption.teamId())
                        .orElseThrow { PersistenceConflictException(
                                "The redemption team has no progression row") };
                var creditedPoints = 0L
                try {
                    creditedPoints = Math.addExact(
                            progress.researchPoints(), redemption.quantity().toLong());
                } catch (overflow: ArithmeticException) {
                    throw PersistenceConflictException(
                            "The team's research point balance cannot increase further", overflow);
                }
                var updatedProgress = TeamProgress(
                        progress.teamId(),
                        progress.highestClearedLevel(),
                        progress.unlockedLevel(),
                        creditedPoints);
                var redeemedQuantity = batch.redeemedQuantity() + redemption.quantity();
                var nextStatus = if (redeemedQuantity == batch.issuedQuantity()) ResearchCrystalBatchStatus.EXHAUSTED else ResearchCrystalBatchStatus.ISSUED
                connection.prepareStatement("""
                        UPDATE research_crystal_batches
                        SET redeemed_quantity = ?, state = ?, updated_at = ?
                        WHERE batch_id = ? AND state = 'ISSUED'
                        """.trimIndent()).use { statement ->
                    statement.setInt(1, redeemedQuantity);
                    statement.setString(2, nextStatus.name);
                    statement.setString(3, appliedAt.toString());
                    statement.setString(4, batch.batchId().toString());
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                                "The research crystal batch was concurrently resolved");
                    }

}
                connection.prepareStatement("""
                        UPDATE team_progress
                        SET research_points = ?, updated_at = ?
                        WHERE team_id = ?
                        """.trimIndent()).use { statement ->
                    statement.setLong(1, updatedProgress.researchPoints());
                    statement.setString(2, appliedAt.toString());
                    statement.setString(3, updatedProgress.teamId().toString());
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The research point update affected no rows");
                    }

}
                connection.prepareStatement("""
                        UPDATE research_crystal_redemptions
                        SET state = 'APPLIED', applied_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """.trimIndent()).use { statement ->
                    statement.setString(1, appliedAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The crystal redemption apply affected no rows");
                    }

}
                var updatedBatch = loadResearchCrystalBatch(
                                connection, batch.batchId())
                        .orElseThrow { SQLException(
                                "The crystal batch disappeared after apply") };
                return@inImmediateTransaction ResearchCrystalRedemptionResult(
                        OperationOutcome.APPLIED, updatedProgress, updatedBatch);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The research crystal redemption conflicts with persisted data", exception);
            }
            throw failure("apply a research crystal redemption", exception);
        }
    }

    /** Rolls back a reservation when the Paper-side physical handoff did not complete. */
    fun rollbackResearchCrystalRedemption(operationId: UUID, rolledBackAt: Instant): Optional<ResearchCrystalRedemption> {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var loaded =
                        loadResearchCrystalRedemption(connection, operationId);
                if (loaded.isEmpty()
                        || loaded.orElseThrow().state() != ResearchCrystalRedemptionState.PREPARED) {
                    return@inImmediateTransaction loaded;
                }
                connection.prepareStatement("""
                        UPDATE research_crystal_redemptions
                        SET state = 'ROLLED_BACK', rolled_back_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """.trimIndent()).use { statement ->
                    statement.setString(1, rolledBackAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The crystal redemption rollback affected no rows");
                    }

}
                var redemption = loaded.orElseThrow();
                return@inImmediateTransaction Optional.of(ResearchCrystalRedemption(
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
        } catch (exception: SQLException) {
            throw failure("roll back a research crystal redemption", exception);
        }
    }

    /** Adds a member when the actor is the owner and no event is active. */
    fun addTeamMember(teamId: UUID, actorId: UUID, memberId: UUID, operationId: UUID, joinedAt: Instant): TeamMutationResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(joinedAt, "joinedAt");
        var fingerprint = managementFingerprint(
                "TEAM_ADD_MEMBER", teamId, actorId, memberId);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(), "TEAM", teamId, "TEAM_ADD_MEMBER", fingerprint);
                    return@inImmediateTransaction TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "change team membership");
                var team = requireTeam(connection, teamId);
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
                    return@inImmediateTransaction TeamMutationResult(
                            ManagementOutcome.APPLIED,
                            loadTeam(connection, teamId));
                }
                if (team.members().size >= MAX_TEAM_MEMBERS) {
                    throw PersistenceConflictException(
                            "The team has reached the maximum of " + MAX_TEAM_MEMBERS + " members");
                }
                connection.prepareStatement("""
                        INSERT INTO team_members(team_id, player_id, role, joined_at)
                        VALUES (?, ?, 'MEMBER', ?)
                        """.trimIndent()).use { statement ->
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
                return@inImmediateTransaction TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The player already belongs to another team", exception);
            }
            throw failure("add a team member", exception);
        }
    }

    /** Removes a non-owner member when the actor is the owner and no event is active. */
    fun removeTeamMember(teamId: UUID, actorId: UUID, memberId: UUID, operationId: UUID, removedAt: Instant): TeamMutationResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(removedAt, "removedAt");
        var fingerprint = managementFingerprint(
                "TEAM_REMOVE_MEMBER", teamId, actorId, memberId);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "TEAM",
                            teamId,
                            "TEAM_REMOVE_MEMBER",
                            fingerprint);
                    return@inImmediateTransaction TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "change team membership");
                var team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                if (!team.members().contains(memberId)) {
                    throw PersistenceConflictException(
                            "Player " + memberId + " is not a member of team " + teamId);
                }
                if (team.ownerId().equals(memberId)) {
                    throw PersistenceConflictException("The team owner cannot be removed");
                }
                connection.prepareStatement("""
                        DELETE FROM team_members WHERE team_id = ? AND player_id = ?
                        """.trimIndent()).use { statement ->
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
                return@inImmediateTransaction TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (exception: SQLException) {
            throw failure("remove a team member", exception);
        }
    }

    /** Transfers ownership to an existing member while the team is idle. */
    fun transferTeamOwnership(teamId: UUID, actorId: UUID, newOwnerId: UUID, operationId: UUID, transferredAt: Instant): TeamMutationResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(newOwnerId, "newOwnerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(transferredAt, "transferredAt");
        var fingerprint = managementFingerprint(
                "TEAM_TRANSFER_OWNER", teamId, actorId, newOwnerId);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "TEAM",
                            teamId,
                            "TEAM_TRANSFER_OWNER",
                            fingerprint);
                    return@inImmediateTransaction TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "transfer team ownership");
                var team = requireTeam(connection, teamId);
                requireTeamOwner(team, actorId);
                if (!team.members().contains(newOwnerId)) {
                    throw PersistenceConflictException(
                            "Ownership can only be transferred to an existing team member");
                }
                connection.prepareStatement("""
                        UPDATE team_members SET role = 'MEMBER'
                        WHERE team_id = ? AND player_id = ?
                        """.trimIndent()).use { statement ->
                    statement.setString(1, teamId.toString());
                    statement.setString(2, actorId.toString());
                    statement.executeUpdate();

}
                connection.prepareStatement("""
                        UPDATE team_members SET role = 'OWNER'
                        WHERE team_id = ? AND player_id = ?
                        """.trimIndent()).use { statement ->
                    statement.setString(1, teamId.toString());
                    statement.setString(2, newOwnerId.toString());
                    statement.executeUpdate();

}
                connection.prepareStatement("""
                        UPDATE teams SET owner_player_id = ? WHERE team_id = ?
                        """.trimIndent()).use { statement ->
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
                return@inImmediateTransaction TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (exception: SQLException) {
            throw failure("transfer team ownership", exception);
        }
    }

    /** Leaves a team; a sole owner may leave only by removing an empty team. */
    fun leaveTeam(teamId: UUID, playerId: UUID, operationId: UUID, leftAt: Instant): TeamMutationResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(leftAt, "leftAt");
        var fingerprint = managementFingerprint("TEAM_LEAVE", teamId, playerId);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(), "TEAM", teamId, "TEAM_LEAVE", fingerprint);
                    return@inImmediateTransaction TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "leave a team");
                var team = requireTeam(connection, teamId);
                if (!team.members().contains(playerId)) {
                    throw PersistenceConflictException(
                            "Player " + playerId + " is not a member of team " + teamId);
                }
                if (team.ownerId().equals(playerId)) {
                    if (team.members().size > 1) {
                        throw PersistenceConflictException(
                                "The owner must transfer ownership before leaving");
                    }
                    requireTeamCanBeDeleted(connection, teamId);
                    deleteTeam(connection, teamId);
                } else {
                    connection.prepareStatement("""
                            DELETE FROM team_members WHERE team_id = ? AND player_id = ?
                            """.trimIndent()).use { statement ->
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
                return@inImmediateTransaction TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        loadTeam(connection, teamId));
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The team still has persisted references", exception);
            }
            throw failure("leave a team", exception);
        }
    }

    /** Disbands an idle, empty team after verifying owner authority. */
    fun disbandTeam(teamId: UUID, actorId: UUID, operationId: UUID, disbandedAt: Instant): TeamMutationResult {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(disbandedAt, "disbandedAt");
        var fingerprint = managementFingerprint("TEAM_DISBAND", teamId, actorId);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "TEAM",
                            teamId,
                            "TEAM_DISBAND",
                            fingerprint);
                    return@inImmediateTransaction TeamMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadTeam(connection, teamId));
                }
                requireNoActiveEvent(connection, "disband a team");
                var team = requireTeam(connection, teamId);
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
                return@inImmediateTransaction TeamMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.empty());
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The team still has persisted references", exception);
            }
            throw failure("disband a team", exception);
        }
    }

    /**
     * Places a core after checking the one-core-per-team and horizontal-distance invariants under
     * the same write lock as the insert.
     */
    fun placeCore(core: CoreRecord, minimumCoreDistance: Double): CoreRecord {
        Objects.requireNonNull(core, "core");
        requireDistance(minimumCoreDistance);
        try {
            return database.inImmediateTransaction({ connection ->
                return@inImmediateTransaction placeCore(connection, core, minimumCoreDistance);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The core conflicts with persisted ownership or position data", exception);
            }
            throw failure("place a core", exception);
        }
    }

    /** Places a core after verifying that the actor belongs to its team. */
    fun placeCore(actorId: UUID, core: CoreRecord, minimumCoreDistance: Double): CoreRecord {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(core, "core");
        requireDistance(minimumCoreDistance);
        try {
            return database.inImmediateTransaction({ connection ->
                requireTeamMember(connection, core.teamId(), actorId);
                return@inImmediateTransaction placeCore(connection, core, minimumCoreDistance);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The core conflicts with persisted ownership or position data", exception);
            }
            throw failure("place a core for a team member", exception);
        }
    }

    /** Places a core with an operation UUID so a Paper retry cannot create a second core. */
    fun placeCore(actorId: UUID, core: CoreRecord, minimumCoreDistance: Double, operationId: UUID, placedAt: Instant): CoreMutationResult {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(placedAt, "placedAt");
        requireDistance(minimumCoreDistance);
        var fingerprint = managementFingerprint(
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
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            core.id(),
                            "CORE_PLACE",
                            fingerprint);
                    return@inImmediateTransaction CoreMutationResult(
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
                return@inImmediateTransaction CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(core));
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The core conflicts with persisted ownership or position data", exception);
            }
            throw failure("place a core with an operation", exception);
        }
    }

    fun findCore(coreId: UUID): Optional<CoreRecord> {
        Objects.requireNonNull(coreId, "coreId");
        return read("load a core", { connection -> loadCore(connection, coreId) });
    }

    fun findCoreByTeam(teamId: UUID): Optional<CoreRecord> {
        Objects.requireNonNull(teamId, "teamId");
        return read("load a team's core", { connection -> loadCoreByTeam(connection, teamId) });
    }

    /** Loads the complete durable core registry in stable UUID order. */
    fun loadAllCores(): List<CoreRecord> {
        return read("load all cores", { connection ->
            var cores = ArrayList<CoreRecord>();
            connection.prepareStatement("""
                    SELECT core_id, team_id, world_id, block_x, block_y, block_z,
                           current_hp, max_hp, created_at, updated_at
                    FROM cores
                    ORDER BY core_id
                    """.trimIndent()).use { statement ->
statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    cores.add(coreFromRow(resultSet));
                }

}}
            return@read java.util.List.copyOf(cores);
        });
    }

    /**
     * Persists the prepared side of the public core physical-placement stop window.
     *
     * <p>No core row is created here. The caller must restore the captured block if this
     * operation remains prepared during startup recovery.</p>
     */
    fun prepareCorePlacement(placement: CorePlacement): CorePlacement {
        Objects.requireNonNull(placement, "placement");
        if (placement.state() != CorePlacementState.PREPARED) {
            throw IllegalArgumentException("A placement request must be PREPARED");
        }
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadCorePlacement(
                        connection, placement.operationId());
                if (existing.isPresent()) {
                    requireMatchingCorePlacement(existing.orElseThrow(), placement);
                    return@inImmediateTransaction existing.orElseThrow();
                }
                requireNoActiveEvent(connection, "prepare a core placement");
                var team = requireTeam(connection, placement.teamId());
                if (placement.relocatingExistingCore()) {
                    requireTeamMember(connection, placement.teamId(), placement.actorId());
                } else {
                    requireTeamOwner(team, placement.actorId());
                }
                var current = loadCoreByTeam(connection, placement.teamId());
                if (placement.relocatingExistingCore()) {
                    var existingCore = current.orElseThrow { PersistenceConflictException(
                                    "The core to relocate does not exist") };
                    if (!existingCore.id().equals(placement.coreId())
                            || existingCore.currentHitPoints() != existingCore.maximumHitPoints()) {
                        throw PersistenceConflictException(
                                "Only the team's full-health core can be relocated");
                    }
                    if (loadCore(connection, placement.coreId()).isEmpty()) {
                        throw PersistenceConflictException(
                                "The core to relocate has no durable row");
                    }
                } else if (placement.rebuildingDestroyedCore()) {
                    var existingCore = current.orElseThrow { PersistenceConflictException(
                                    "The destroyed core to rebuild does not exist") };
                    if (!existingCore.id().equals(placement.coreId())
                            || existingCore.currentHitPoints() != 0L) {
                        throw PersistenceConflictException(
                                "Only the team's destroyed core can be rebuilt");
                    }
                } else {
                    if (current.isPresent() && current.orElseThrow().currentHitPoints() > 0L) {
                        throw PersistenceConflictException(
                                "The team already owns a live core");
                    }
                    if (loadCore(connection, placement.coreId()).isPresent()) {
                        throw PersistenceConflictException(
                                "The core item identity has already been used");
                    }
                }
                insertCorePlacement(connection, placement);
                return@inImmediateTransaction placement;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The core placement conflicts with another pending operation", exception);
            }
            throw failure("prepare a core placement", exception);
        }
    }

    /** Applies the database side after the Paper block has been replaced and tagged. */
    fun applyCorePlacement(operationId: UUID, appliedAt: Instant): CorePlacementResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var placement = loadCorePlacement(connection, operationId).orElseThrow { PersistenceConflictException(
                                "The prepared core placement does not exist") };
                if (placement.state() == CorePlacementState.APPLIED) {
                    return@inImmediateTransaction CorePlacementResult(
                            placement,
                            loadCore(connection, placement.coreId()).orElseThrow { PersistenceConflictException(
                                            "An applied core placement has no core row") });
                }
                if (placement.state() == CorePlacementState.ROLLED_BACK) {
                    throw PersistenceConflictException(
                            "The prepared core placement was already rolled back");
                }
                requireNoActiveEvent(connection, "apply a core placement");
                var team = requireTeam(connection, placement.teamId());
                if (placement.relocatingExistingCore()) {
                    requireTeamMember(connection, placement.teamId(), placement.actorId());
                } else {
                    requireTeamOwner(team, placement.actorId());
                }
                lateinit var core: CoreRecord
                if (placement.relocatingExistingCore()) {
                    var existing = requireCore(connection, placement.coreId());
                    if (existing.currentHitPoints() != existing.maximumHitPoints()
                            || !existing.teamId().equals(placement.teamId())) {
                        throw PersistenceConflictException(
                                "The team's core is no longer relocatable");
                    }
                    var nearby = findDistanceConflict(
                            connection,
                            placement.worldId(),
                            placement.blockX(),
                            placement.blockZ(),
                            placement.minimumCoreDistance(),
                            existing.id());
                    if (nearby.isPresent()) {
                        throw PersistenceConflictException(
                                "Core position is too close to core " + nearby.orElseThrow().id());
                    }
                    core = CoreRecord(
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
                    var existing = requireCore(connection, placement.coreId());
                    if (existing.currentHitPoints() != 0L
                            || !existing.teamId().equals(placement.teamId())) {
                        throw PersistenceConflictException(
                                "The team's core is no longer rebuildable");
                    }
                    core = CoreRecord(
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
                    var nearby = findDistanceConflict(
                            connection,
                            placement.worldId(),
                            placement.blockX(),
                            placement.blockZ(),
                            placement.minimumCoreDistance(),
                            existing.id());
                    if (nearby.isPresent()) {
                        throw PersistenceConflictException(
                                "Core position is too close to core " + nearby.orElseThrow().id());
                    }
                    updateCore(connection, core);
                } else {
                    core = CoreRecord(
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
                var applied = CorePlacement(
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
                return@inImmediateTransaction CorePlacementResult(applied, core);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The core placement conflicts with persisted core data", exception);
            }
            throw failure("apply a core placement", exception);
        }
    }

    /** Marks a still-prepared placement as rolled back after its physical block is restored. */
    fun rollbackCorePlacement(operationId: UUID, rolledBackAt: Instant): Optional<CorePlacement> {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var loaded = loadCorePlacement(connection, operationId);
                if (loaded.isEmpty() || loaded.orElseThrow().state() != CorePlacementState.PREPARED) {
                    return@inImmediateTransaction loaded;
                }
                var rolledBack = CorePlacement(
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
                return@inImmediateTransaction Optional.of(rolledBack);
            });
        } catch (exception: SQLException) {
            throw failure("roll back a prepared core placement", exception);
        }
    }

    /** Loads prepared operations for startup physical recovery. */
    fun loadPendingCorePlacements(): List<CorePlacement> {
        return loadCorePlacementsByState(CorePlacementState.PREPARED);
    }

    /** Loads item identities which were applied before their inventory handoff completed. */
    fun loadAppliedCorePlacementItemIds(): List<UUID> {
        return read("load applied core placement item identities", { connection ->
            var itemIds = ArrayList<UUID>();
            connection.prepareStatement("""
                    SELECT item_id FROM core_placement_operations
                    WHERE state = 'APPLIED'
                    ORDER BY applied_at, operation_id
                    """.trimIndent()).use { statement ->
statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    itemIds.add(uuid(resultSet.getString("item_id")));
                }

}}
            return@read java.util.List.copyOf(itemIds);
        });
    }

    /** Loads the last applied placement ledger for a core's physical source block. */
    fun findAppliedCorePlacementByCore(coreId: UUID): Optional<CorePlacement> {
        Objects.requireNonNull(coreId, "coreId");
        return read("load a core's applied placement", { connection ->
            connection.prepareStatement("""
                    SELECT operation_id, item_id, core_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, max_hp, minimum_core_distance,
                           rebuilding_destroyed_core, relocating_existing_core,
                           previous_block_data, state, prepared_at, applied_at, rolled_back_at
                    FROM core_placement_operations
                    WHERE core_id = ? AND state = 'APPLIED'
                    ORDER BY applied_at DESC, operation_id DESC
                    LIMIT 1
                    """.trimIndent()).use { statement ->
                statement.setString(1, coreId.toString());
                statement.executeQuery().use { resultSet ->
                    return@read if (resultSet.next()) Optional.of(corePlacementFromRow(resultSet)) else Optional.empty()

}

}
        });
    }

    /** Returns the first same-world core strictly nearer than the configured distance. */
    fun findDistanceConflict(worldId: UUID, blockX: Int, blockZ: Int, minimumCoreDistance: Double): Optional<CoreRecord> {
        Objects.requireNonNull(worldId, "worldId");
        requireDistance(minimumCoreDistance);
        return read(
                "check core distance",
                { connection -> findDistanceConflict(
                        connection, worldId, blockX, blockZ, minimumCoreDistance) });
    }

    /** Repairs a present core outside an active defense event. */
    fun repairCore(coreId: UUID, actorId: UUID, amount: Long, operationId: UUID, repairedAt: Instant): CoreMutationResult {
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
    fun repairCore(coreId: UUID, actorId: UUID, amount: Long, defensePointCost: Long, operationId: UUID, repairedAt: Instant): CoreMutationResult {
        return repairCore(
                coreId,
                actorId,
                amount,
                defensePointCost,
                if (defensePointCost > 0L) PaymentMode.POINT_WALLET else PaymentMode.LEGACY_ITEMS,
                operationId,
                repairedAt);
    }

    /** Repairs a core using an explicitly persisted payment mode. */
    fun repairCore(coreId: UUID, actorId: UUID, amount: Long, defensePointCost: Long, paymentMode: PaymentMode, operationId: UUID, repairedAt: Instant): CoreMutationResult {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(paymentMode, "paymentMode");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(repairedAt, "repairedAt");
        if (amount <= 0L) {
            throw IllegalArgumentException("repair amount must be positive");
        }
        if (defensePointCost < 0L) {
            throw IllegalArgumentException("defensePointCost must not be negative");
        }
        if (paymentMode == PaymentMode.LEGACY_ITEMS && defensePointCost != 0L) {
            throw IllegalArgumentException(
                    "legacy item repairs cannot include a wallet payment");
        }
        var fingerprint = managementFingerprint(
                "CORE_REPAIR", coreId, actorId, amount, defensePointCost, paymentMode);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            coreId,
                            "CORE_REPAIR",
                            fingerprint,
                            paymentMode);
                    return@inImmediateTransaction CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, coreId));
                }
                requireNoActiveEvent(connection, "repair a core");
                var core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() == 0L) {
                    throw PersistenceConflictException(
                            "A destroyed core must be rebuilt before it can be repaired");
                }
                var missingHitPoints = core.maximumHitPoints() - core.currentHitPoints();
                var repairedHitPoints = core.currentHitPoints() + Math.min(amount, missingHitPoints);
                if (paymentMode == PaymentMode.POINT_WALLET && defensePointCost > 0L) {
                    ResourceRepository.debitInTransaction(
                            connection,
                            core.teamId(),
                            actorId,
                            ResourceType.DEFENSE_POINTS,
                            defensePointCost,
                            UUID.nameUUIDFromBytes((operationId.toString() + "|DEFENSE_POINTS")
                                    .toByteArray(StandardCharsets.UTF_8)),
                            operationId.toString(),
                            fingerprint,
                            repairedAt);
                }
                var updated = CoreRecord(
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
                return@inImmediateTransaction CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(updated));
            });
        } catch (exception: SQLException) {
            throw failure("repair a core", exception);
        }
    }

    /**
     * Reserves a core repair before any vanilla inventory material is moved into a receipt stack.
     * The prepared row fixes the core HP, wallet cost, legacy material and operation fingerprint
     * so a restart cannot silently apply a different repair payload.
     */
    fun prepareCoreRepair(coreId: UUID, actorId: UUID, amount: Long, defensePointCost: Long, paymentMode: PaymentMode, vanillaMaterial: String, vanillaMaterialAmount: Long, operationId: UUID, preparedAt: Instant): CoreRepairOperation {
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
    fun prepareCoreRepair(coreId: UUID, actorId: UUID, amount: Long, defensePointCost: Long, paymentMode: PaymentMode, vanillaMaterial: String, vanillaMaterialAmount: Long, legacyDefenseShardAmount: Long, operationId: UUID, preparedAt: Instant): CoreRepairOperation {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(paymentMode, "paymentMode");
        Objects.requireNonNull(vanillaMaterial, "vanillaMaterial");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (amount <= 0L || defensePointCost < 0L || vanillaMaterialAmount < 0L
                || legacyDefenseShardAmount < 0L) {
            throw IllegalArgumentException("core repair quantities are invalid");
        }
        if (paymentMode == PaymentMode.LEGACY_ITEMS && defensePointCost != 0L) {
            throw IllegalArgumentException(
                    "legacy item repairs cannot include a wallet payment");
        }
        var fingerprint = managementFingerprint(
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
            return database.inImmediateTransaction({ connection ->
                var existing = loadCoreRepairOperation(
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
                    return@inImmediateTransaction existing.orElseThrow();
                }
                requireNoActiveEvent(connection, "prepare a core repair");
                var core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() == 0L) {
                    throw PersistenceConflictException(
                            "A destroyed core must be rebuilt before it can be repaired");
                }
                if (core.currentHitPoints() >= core.maximumHitPoints()) {
                    throw PersistenceConflictException("The core is already at full health");
                }
                var prepared = CoreRepairOperation(
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
                return@inImmediateTransaction prepared;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The prepared core repair conflicts with persisted data", exception);
            }
            throw failure("prepare a core repair", exception);
        }
    }

    /** Persists the inventory receipt after the prepared operation has been created. */
    fun reserveCoreRepairReceipt(operationId: UUID, playerId: UUID, material: String, quantity: Long, reservedAt: Instant): CoreRepairReceipt {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(reservedAt, "reservedAt");
        if (quantity <= 0L) {
            throw IllegalArgumentException("receipt quantity must be positive");
        }
        try {
            return database.inImmediateTransaction({ connection ->
                var operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow { PersistenceConflictException(
                                "The prepared core repair does not exist") };
                if (operation.state() != CoreRepairOperationState.PREPARED) {
                    throw PersistenceConflictException(
                            "The core repair is no longer reservable");
                }
                if (!operation.actorId().equals(playerId)
                        || !expectedReceiptMaterial(operation).equals(material)
                        || expectedReceiptQuantity(operation) != quantity) {
                    throw PersistenceConflictException(
                            "The core repair receipt does not match its prepared payload");
                }
                var existing = loadCoreRepairReceipt(
                        connection, operationId);
                if (existing.isPresent()) {
                    var receipt = existing.orElseThrow();
                    if (!receipt.playerId().equals(playerId)
                            || !receipt.material().equals(material)
                            || receipt.quantity() != quantity) {
                        throw PersistenceConflictException(
                                "The core repair receipt UUID is already assigned to another payload");
                    }
                    return@inImmediateTransaction receipt;
                }
                var receipt = CoreRepairReceipt(
                        operationId,
                        playerId,
                        material,
                        quantity,
                        CoreRepairReceiptState.RESERVED,
                        reservedAt,
                        null);
                insertCoreRepairReceipt(connection, receipt);
                return@inImmediateTransaction receipt;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The core repair receipt conflicts with persisted data", exception);
            }
            throw failure("reserve a core repair receipt", exception);
        }
    }

    /** Applies a prepared repair and wallet debit atomically after its receipt is secured. */
    fun applyPreparedCoreRepair(operationId: UUID, appliedAt: Instant): CoreMutationResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow { PersistenceConflictException(
                                "The prepared core repair does not exist") };
                if (operation.state() == CoreRepairOperationState.APPLIED) {
                    return@inImmediateTransaction CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, operation.coreId()));
                }
                if (operation.state() == CoreRepairOperationState.ROLLED_BACK) {
                    throw PersistenceConflictException(
                            "The prepared core repair was already rolled back");
                }
                if (expectedReceiptQuantity(operation) > 0L) {
                    var receipt = loadCoreRepairReceipt(connection, operationId)
                            .orElseThrow { PersistenceConflictException(
                                    "The core repair receipt was not secured") };
                    if (receipt.state() != CoreRepairReceiptState.SECURED) {
                        throw PersistenceConflictException(
                                "The core repair receipt has not reached the secured handoff");
                    }
                }
                requireNoActiveEvent(connection, "apply a core repair");
                var core = requireCore(connection, operation.coreId());
                requireTeamMember(connection, core.teamId(), operation.actorId());
                if (core.currentHitPoints() != operation.expectedCurrentHitPoints()) {
                    throw PersistenceConflictException(
                            "The core HP changed before the prepared repair was applied");
                }
                var missingHitPoints = core.maximumHitPoints() - core.currentHitPoints();
                var repairedHitPoints = core.currentHitPoints() + Math.min(operation.repairAmount(), missingHitPoints);
                if (operation.paymentMode() == PaymentMode.POINT_WALLET
                        && operation.defensePointCost() > 0L) {
                    ResourceRepository.debitInTransaction(
                            connection,
                            core.teamId(),
                            operation.actorId(),
                            ResourceType.DEFENSE_POINTS,
                            operation.defensePointCost(),
                            UUID.nameUUIDFromBytes((operationId.toString() + "|DEFENSE_POINTS")
                                    .toByteArray(StandardCharsets.UTF_8)),
                            operationId.toString(),
                            operation.payloadFingerprint(),
                            appliedAt);
                }
                var updated = CoreRecord(
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
                return@inImmediateTransaction CoreMutationResult(ManagementOutcome.APPLIED, Optional.of(updated));
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The prepared core repair conflicts with persisted data", exception);
            }
            throw failure("apply a prepared core repair", exception);
        }
    }

    /** Durably records that the exact material stacks were replaced with tagged receipt stacks. */
    fun secureCoreRepairReceipt(operationId: UUID, securedAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(securedAt, "securedAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElseThrow { PersistenceConflictException(
                                "The core repair receipt does not exist") };
                if (receipt.state() == CoreRepairReceiptState.SECURED) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.RESERVED) {
                    throw PersistenceConflictException(
                            "The core repair receipt is no longer reservable");
                }
                connection.prepareStatement("""
                        UPDATE core_repair_receipts
                        SET state = 'SECURED'
                        WHERE operation_id = ? AND state = 'RESERVED'
                        """.trimIndent()).use { statement ->
                    statement.setString(1, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                                "The core repair receipt changed concurrently");
                    }

}
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            throw failure("secure a core repair receipt", exception);
        }
    }

    /** Marks the physical receipt clear as the next durable step after the applied mutation. */
    fun markCoreRepairReceiptClearPending(operationId: UUID, pendingAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(pendingAt, "pendingAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow { PersistenceConflictException(
                                "The core repair operation does not exist") };
                if (operation.state() != CoreRepairOperationState.APPLIED) {
                    throw PersistenceConflictException(
                            "Only an applied core repair can clear its receipt");
                }
                var receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElse(null);
                if (receipt == null
                        || receipt.state() == CoreRepairReceiptState.CLEARED
                        || receipt.state() == CoreRepairReceiptState.CLEAR_PENDING) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.SECURED) {
                    throw PersistenceConflictException(
                            "Only a secured core repair receipt can enter physical clear");
                }
                updateCoreRepairReceiptState(
                        connection,
                        operationId,
                        CoreRepairReceiptState.CLEAR_PENDING,
                        pendingAt);
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            throw failure("mark a core repair receipt clear-pending", exception);
        }
    }

    /** Marks the physical receipt clear after the applied core mutation is confirmed. */
    fun clearCoreRepairReceipt(operationId: UUID, clearedAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clearedAt, "clearedAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow { PersistenceConflictException(
                                "The core repair operation does not exist") };
                if (operation.state() != CoreRepairOperationState.APPLIED) {
                    throw PersistenceConflictException(
                            "Only an applied core repair can clear its receipt");
                }
                var receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElse(null);
                if (receipt == null || receipt.state() == CoreRepairReceiptState.CLEARED) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.SECURED
                        && receipt.state() != CoreRepairReceiptState.CLEAR_PENDING) {
                    throw PersistenceConflictException(
                            "Only a secured core repair receipt can be cleared");
                }
                updateCoreRepairReceiptState(
                        connection,
                        operationId,
                        CoreRepairReceiptState.CLEARED,
                        clearedAt);
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            throw failure("clear a core repair receipt", exception);
        }
    }

    /** Rolls back a prepared operation and releases its receipt after a failed physical step. */
    fun rollbackPreparedCoreRepair(operationId: UUID, rolledBackAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var operation = loadCoreRepairOperation(connection, operationId)
                        .orElseThrow { PersistenceConflictException(
                                "The core repair operation does not exist") };
                if (operation.state() == CoreRepairOperationState.ROLLED_BACK) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }
                if (operation.state() == CoreRepairOperationState.APPLIED) {
                    throw PersistenceConflictException(
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
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            throw failure("roll back a prepared core repair", exception);
        }
    }

    /** Completes a physical refund after the player inventory has been durably saved. */
    fun restoreCoreRepairReceipt(operationId: UUID, restoredAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(restoredAt, "restoredAt");
        try {
            return database.inImmediateTransaction({ connection ->
                var receipt = loadCoreRepairReceipt(connection, operationId)
                        .orElse(null);
                if (receipt == null || receipt.state() == CoreRepairReceiptState.RESTORED) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }
                if (receipt.state() != CoreRepairReceiptState.RETURN_PENDING) {
                    throw PersistenceConflictException(
                            "Only a return@inImmediateTransaction-pending core receipt can be restored");
                }
                updateCoreRepairReceiptState(
                        connection,
                        operationId,
                        CoreRepairReceiptState.RESTORED,
                        restoredAt);
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            throw failure("restore a core repair receipt", exception);
        }
    }

    fun findCoreRepairOperation(operationId: UUID): Optional<CoreRepairOperation> {
        Objects.requireNonNull(operationId, "operationId");
        return read(
                "load a core repair operation",
                { connection -> loadCoreRepairOperation(connection, operationId) });
    }

    fun findCoreRepairReceipt(operationId: UUID): Optional<CoreRepairReceipt> {
        Objects.requireNonNull(operationId, "operationId");
        return read(
                "load a core repair receipt",
                { connection -> loadCoreRepairReceipt(connection, operationId) });
    }

    fun loadPreparedCoreRepairs(playerId: UUID): List<CoreRepairOperation> {
        Objects.requireNonNull(playerId, "playerId");
        return read("load prepared core repairs", { connection ->
            var operations = ArrayList<CoreRepairOperation>();
            connection.prepareStatement("""
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
                    """.trimIndent()).use { statement ->
                statement.setString(1, playerId.toString());
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        operations.add(coreRepairOperationFromRow(resultSet));
                    }

}

}
            return@read java.util.List.copyOf(operations);
        });
    }

    /**
     * Loads terminal receipt rows as a bounded idempotent inventory tombstone.
     *
     * <p>A player save can race the database terminal transition during shutdown.  Keeping the
     * terminal operation visible to the next join lets the Paper bridge strip a resurrected
     * tagged stack without minting or charging anything again.</p>
     */
    fun loadTerminalCoreRepairReceipts(playerId: UUID): List<CoreRepairOperation> {
        Objects.requireNonNull(playerId, "playerId");
        return read("load terminal core repair receipt tombstones", { connection ->
            var operations = ArrayList<CoreRepairOperation>();
            connection.prepareStatement("""
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
                    """.trimIndent()).use { statement ->
                statement.setString(1, playerId.toString());
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        operations.add(coreRepairOperationFromRow(resultSet));
                    }

}

}
            return@read java.util.List.copyOf(operations);
        });
    }

    /** Moves an intact core after checking ownership, idle state, and world separation. */
    fun relocateCore(coreId: UUID, actorId: UUID, worldId: UUID, blockX: Int, blockY: Int, blockZ: Int, minimumCoreDistance: Double, operationId: UUID, relocatedAt: Instant): CoreMutationResult {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(relocatedAt, "relocatedAt");
        requireDistance(minimumCoreDistance);
        var fingerprint = managementFingerprint(
                "CORE_RELOCATE",
                coreId,
                actorId,
                worldId,
                blockX,
                blockY,
                blockZ,
                minimumCoreDistance);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            coreId,
                            "CORE_RELOCATE",
                            fingerprint);
                    return@inImmediateTransaction CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, coreId));
                }
                requireNoActiveEvent(connection, "relocate a core");
                var core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() != core.maximumHitPoints()) {
                    throw PersistenceConflictException(
                            "A core can only be relocated at full HP");
                }
                var nearby = findDistanceConflict(
                        connection,
                        worldId,
                        blockX,
                        blockZ,
                        minimumCoreDistance,
                        coreId);
                if (nearby.isPresent()) {
                    throw PersistenceConflictException(
                            "Core position is too close to core " + nearby.orElseThrow().id());
                }
                var updated = CoreRecord(
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
                return@inImmediateTransaction CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(updated));
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The core position conflicts with persisted data", exception);
            }
            throw failure("relocate a core", exception);
        }
    }

    /** Rebuilds a destroyed core in place as a full-health placement. */
    fun rebuildCore(coreId: UUID, actorId: UUID, worldId: UUID, blockX: Int, blockY: Int, blockZ: Int, maximumHitPoints: Long, minimumCoreDistance: Double, operationId: UUID, rebuiltAt: Instant): CoreMutationResult {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rebuiltAt, "rebuiltAt");
        if (maximumHitPoints <= 0L) {
            throw IllegalArgumentException("maximumHitPoints must be positive");
        }
        requireDistance(minimumCoreDistance);
        var fingerprint = managementFingerprint(
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
            return database.inImmediateTransaction({ connection ->
                var existing = loadManagementOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingManagementOperation(
                            existing.orElseThrow(),
                            "CORE",
                            coreId,
                            "CORE_REBUILD",
                            fingerprint);
                    return@inImmediateTransaction CoreMutationResult(
                            ManagementOutcome.ALREADY_APPLIED,
                            loadCore(connection, coreId));
                }
                requireNoActiveEvent(connection, "rebuild a core");
                var core = requireCore(connection, coreId);
                requireTeamMember(connection, core.teamId(), actorId);
                if (core.currentHitPoints() != 0L) {
                    throw PersistenceConflictException(
                            "Only a destroyed core can be rebuilt");
                }
                var nearby = findDistanceConflict(
                        connection,
                        worldId,
                        blockX,
                        blockZ,
                        minimumCoreDistance,
                        coreId);
                if (nearby.isPresent()) {
                    throw PersistenceConflictException(
                            "Core position is too close to core " + nearby.orElseThrow().id());
                }
                var updated = CoreRecord(
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
                return@inImmediateTransaction CoreMutationResult(
                        ManagementOutcome.APPLIED,
                        Optional.of(updated));
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The rebuilt core conflicts with persisted data", exception);
            }
            throw failure("rebuild a core", exception);
        }
    }

    /**
     * Atomically inserts the complete start snapshot and acquires the global DB lock. Nothing is
     * inserted when another event owns the lock.
     */
    fun tryStart(request: StartRequest): StartOutcome {
        return tryStart(request, true);
    }

    /**
     * Creates an event while leaving a supplied raid seal RESERVED. The Paper caller removes the
     * matching physical item on the main thread and must then call
     * {@link #consumeReservedStartSeal(UUID, UUID, Instant)}. A failed or interrupted event is
     * eligible for the normal technical-refund transaction.
     */
    fun tryStartReserved(request: StartRequest): StartOutcome {
        Objects.requireNonNull(request, "request");
        if (request.raidSealId().isEmpty()) {
            throw IllegalArgumentException("A reserved start requires a raid seal");
        }
        return tryStart(request, false);
    }

    /** Consumes the reservation after the corresponding physical token has been removed. */
    fun consumeReservedStartSeal(eventId: UUID, sealId: UUID, consumedAt: Instant): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sealId, "sealId");
        Objects.requireNonNull(consumedAt, "consumedAt");
        try {
            return database.inImmediateTransaction({ connection -> RaidSealRepository.consumeReservedForStart(
                            connection, eventId, sealId, consumedAt) });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The reserved raid seal conflicts with persisted data", exception);
            }
            throw failure("consume the reserved raid seal", exception);
        }
    }

    private fun tryStart(request: StartRequest, consumeSeal: Boolean): StartOutcome {
        Objects.requireNonNull(request, "request");
        try {
            return database.inImmediateTransaction({ connection ->
                if (loadActiveEventId(connection).isPresent()) {
                    return@inImmediateTransaction StartOutcome.LOCKED;
                }

                var snapshot = request.session();
                if (eventExists(connection, snapshot.eventId())) {
                    throw PersistenceConflictException(
                            "Event " + snapshot.eventId() + " already exists");
                }
                var core = loadCore(connection, request.coreId()).orElseThrow { PersistenceConflictException(
                                "Core " + request.coreId() + " does not exist") };
                validateStartCore(snapshot, core);

                insertEvent(connection, request, core);
                insertBattleFunds(connection, snapshot.eventId(), snapshot.teamId(), request.startedAt());
                replaceParticipants(connection, snapshot, request.startedAt());
                connection.prepareStatement("""
                        INSERT INTO event_lock(singleton, event_id, acquired_at)
                        VALUES (1, ?, ?)
                        """.trimIndent()).use { statement ->
                    statement.setString(1, snapshot.eventId().toString());
                    statement.setString(2, request.startedAt().toString());
                    statement.executeUpdate();

}
                if (consumeSeal) {
                    RaidSealRepository.consumeForStart(connection, request);
                } else {
                    RaidSealRepository.reserveForStart(connection, request);
                }
                return@inImmediateTransaction StartOutcome.STARTED;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The event start conflicts with persisted data", exception);
            }
            throw failure("start a defense event", exception);
        }
    }

    fun activeEventId(): Optional<UUID> {
        return read("load the active event lock", { connection -> loadActiveEventId(connection) });
    }

    /** Loads the event-scoped battle-funds account. */
    fun loadBattleFunds(eventId: UUID): BattleFunds {
        Objects.requireNonNull(eventId, "eventId");
        return read(
                "load event battle funds",
                { connection -> loadBattleFunds(connection, eventId).orElseThrow { PersistenceConflictException(
                                "Defense event " + eventId + " has no battle-funds account") } });
    }

    /** Credits event funds for a deterministic enemy or wave reward operation. */
    fun creditBattleFunds(eventId: UUID, teamId: UUID, operationId: UUID, operationKind: String, amount: Long, appliedAt: Instant): BattleFundsMutationResult {
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
    fun spendBattleFunds(eventId: UUID, teamId: UUID, actorId: UUID, operationId: UUID, operationKind: String, amount: Long, appliedAt: Instant): BattleFundsMutationResult {
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
    fun loadBattleBoosts(eventId: UUID): List<BattleBoost> {
        Objects.requireNonNull(eventId, "eventId");
        return read("load event tower boosts", { connection ->
            var boosts = ArrayList<BattleBoost>();
            connection.prepareStatement("""
                    SELECT event_id, team_id, tower_id, boost_kind, level, multiplier, updated_at
                    FROM event_tower_boosts
                    WHERE event_id = ?
                    ORDER BY tower_id, boost_kind
                    """.trimIndent()).use { statement ->
                statement.setString(1, eventId.toString());
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        boosts.add(battleBoostFromRow(resultSet));
                    }

}

}
            return@read java.util.List.copyOf(boosts);
        });
    }

    /** Purchases one cumulative temporary boost and spends its funds in the same transaction. */
    fun purchaseBattleBoost(eventId: UUID, teamId: UUID, actorId: UUID, towerId: UUID, kind: BattleBoostKind, cost: Long, boostMultiplier: Double, operationId: UUID, appliedAt: Instant): BattleBoostMutationResult {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (cost <= 0L) {
            throw IllegalArgumentException("cost must be positive");
        }
        if (!java.lang.Double.isFinite(boostMultiplier) || boostMultiplier <= 0.0) {
            throw IllegalArgumentException("boostMultiplier must be finite and positive");
        }
        var operationKind = "BOOST_" + kind.id();
        var fingerprint = managementFingerprint(
                "BATTLE_BOOST",
                eventId,
                teamId,
                actorId,
                towerId,
                kind.id(),
                cost,
                boostMultiplier);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadBattleBoostOperation(
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
                    return@inImmediateTransaction BattleBoostMutationResult(
                            OperationOutcome.ALREADY_APPLIED,
                            requireBattleBoost(connection, eventId, towerId, kind),
                            requireBattleFunds(connection, eventId));
                }

                requireActiveBattleFundsEvent(connection, eventId, true);
                requireTeamMember(connection, teamId, actorId);
                requireTowerBelongsToTeam(connection, towerId, teamId);
                var currentFunds = requireBattleFunds(connection, eventId);
                if (!currentFunds.teamId().equals(teamId)) {
                    throw PersistenceConflictException(
                            "The battle-boost operation belongs to another team");
                }
                if (currentFunds.balance() < cost) {
                    throw PersistenceConflictException(
                            "The team does not have enough battle funds for this boost");
                }
                var current = loadBattleBoost(
                        connection, eventId, towerId, kind);
                var nextLevel = if (current.isPresent()) Math.addExact(current.orElseThrow().level(), 1) else 1
                var previousMultiplier = current.map(BattleBoost::multiplier).orElse(1.0);
                var nextMultiplier = previousMultiplier * boostMultiplier;
                if (!java.lang.Double.isFinite(nextMultiplier)) {
                    throw PersistenceConflictException(
                            "The battle boost multiplier is outside the supported range");
                }
                var updatedFunds = BattleFunds(
                        currentFunds.eventId(),
                        currentFunds.teamId(),
                        currentFunds.balance() - cost,
                        currentFunds.totalEarned(),
                        Math.addExact(currentFunds.totalSpent(), cost),
                        BattleFundsState.ACTIVE,
                        appliedAt);
                var updatedBoost = BattleBoost(
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
                return@inImmediateTransaction BattleBoostMutationResult(
                        OperationOutcome.APPLIED, updatedBoost, updatedFunds);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The battle-boost operation conflicts with persisted data", exception);
            }
            throw failure("purchase a battle boost", exception);
        } catch (overflow: ArithmeticException) {
            throw PersistenceConflictException(
                    "The battle-boost account cannot represent this purchase", overflow);
        }
    }

    /** Repairs a tower's durable HP and spends battle funds atomically. */
    fun repairTowerWithBattleFunds(eventId: UUID, teamId: UUID, actorId: UUID, towerId: UUID, repairedHitPoints: Long, cost: Long, operationId: UUID, appliedAt: Instant): TowerRepairMutationResult {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (repairedHitPoints <= 0L || cost <= 0L) {
            throw IllegalArgumentException("tower repair amount and cost must be positive");
        }
        var operationKind = "REPAIR_TOWER";
        var fingerprint = managementFingerprint(
                "TOWER_REPAIR",
                eventId,
                teamId,
                actorId,
                towerId,
                repairedHitPoints,
                cost);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadTowerRepairOperation(
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
                    return@inImmediateTransaction TowerRepairMutationResult(
                            OperationOutcome.ALREADY_APPLIED,
                            requireTowerDurability(connection, towerId),
                            requireBattleFunds(connection, eventId));
                }

                requireActiveBattleFundsEvent(connection, eventId, true);
                requireTeamMember(connection, teamId, actorId);
                var current = requireTowerDurability(connection, towerId);
                if (!current.teamId.equals(teamId)) {
                    throw PersistenceConflictException(
                            "The tower repair belongs to another team");
                }
                if (repairedHitPoints > current.maximumHitPoints - current.currentHitPoints) {
                    throw PersistenceConflictException(
                            "The tower repair exceeds the missing HP");
                }
                var currentFunds = requireBattleFunds(connection, eventId);
                if (!currentFunds.teamId().equals(teamId)) {
                    throw PersistenceConflictException(
                            "The tower repair belongs to another event team");
                }
                if (currentFunds.balance() < cost) {
                    throw PersistenceConflictException(
                            "The team does not have enough battle funds for tower repair");
                }
                var updatedDurability = TowerDurability(
                        towerId,
                        teamId,
                        current.currentHitPoints + repairedHitPoints,
                        current.maximumHitPoints);
                var updatedFunds = BattleFunds(
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
                return@inImmediateTransaction TowerRepairMutationResult(
                        OperationOutcome.APPLIED, updatedDurability, updatedFunds);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The tower repair operation conflicts with persisted data", exception);
            }
            throw failure("repair a tower with battle funds", exception);
        } catch (overflow: ArithmeticException) {
            throw PersistenceConflictException(
                    "The tower repair account cannot represent this purchase", overflow);
        }
    }

    /** Applies one destroyer attack to a tower and deletes the tower atomically at zero HP. */
    fun damageTowerByEnemy(eventId: UUID, teamId: UUID, attackerLogicalEnemyId: UUID, towerId: UUID, damage: Long, operationId: UUID, appliedAt: Instant): TowerDamageMutationResult {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(attackerLogicalEnemyId, "attackerLogicalEnemyId");
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (damage <= 0L) {
            throw IllegalArgumentException("tower damage must be positive");
        }
        var fingerprint = managementFingerprint(
                "TOWER_DAMAGE",
                eventId,
                teamId,
                attackerLogicalEnemyId,
                towerId,
                damage);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadTowerDamageOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    var operation = existing.orElseThrow();
                    requireMatchingTowerDamageOperation(
                            operation,
                            eventId,
                            teamId,
                            attackerLogicalEnemyId,
                            towerId,
                            damage,
                            fingerprint);
                    return@inImmediateTransaction damageResult(operation, OperationOutcome.ALREADY_APPLIED);
                }

                requireActiveTowerDamageEvent(connection, eventId, teamId);
                var current = requireTowerDurability(connection, towerId);
                if (!current.teamId.equals(teamId)) {
                    throw PersistenceConflictException(
                            "The tower damage belongs to another team");
                }
                var destroyed = current.currentHitPoints <= damage;
                var remainingHitPoints = if (destroyed) 0L else current.currentHitPoints - damage
                if (destroyed) {
                    deleteTower(connection, towerId, teamId);
                } else {
                    updateTowerDurability(
                            connection,
                            TowerDurability(
                                    towerId,
                                    teamId,
                                    remainingHitPoints,
                                    current.maximumHitPoints),
                            appliedAt);
                }
                var operation = TowerDamageOperation(
                        eventId,
                        teamId,
                        attackerLogicalEnemyId,
                        towerId,
                        damage,
                        remainingHitPoints,
                        destroyed,
                        fingerprint);
                insertTowerDamageOperation(connection, operation, operationId, appliedAt);
                return@inImmediateTransaction damageResult(operation, OperationOutcome.APPLIED);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The tower damage operation conflicts with persisted data", exception);
            }
            throw failure("damage a tower by an event enemy", exception);
        }
    }

    fun findEvent(eventId: UUID): Optional<StoredDefenseEvent> {
        Objects.requireNonNull(eventId, "eventId");
        return read("load a defense event", { connection -> loadEvent(connection, eventId) });
    }

    /** Loads every non-terminal event which requires runtime resume or technical recovery. */
    fun loadUnfinishedEvents(): List<StoredDefenseEvent> {
        return read("load unfinished defense events", { connection ->
            var ids = ArrayList<UUID>();
            connection.prepareStatement(
                    "SELECT event_id FROM defense_events WHERE state NOT IN "
                            + TERMINAL_PHASES_SQL + " ORDER BY started_at, event_id").use { statement ->
statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    ids.add(uuid(resultSet.getString("event_id")));
                }

}}

            var events = ArrayList<StoredDefenseEvent>(ids.size);
            for (id in ids) {
                events.add(loadEvent(connection, id).orElseThrow { PersistenceException(
                                "An unfinished event disappeared while loading", null) });
            }
            return@read java.util.List.copyOf(events);
        });
    }

    /** Persists an in-phase aggregate snapshot without appending a lifecycle transition. */
    fun saveSnapshot(snapshot: DefenseSessionSnapshot, updatedAt: Instant): OperationOutcome {
        Objects.requireNonNull(snapshot, "snapshot");
        return saveSnapshot(snapshot, currentRevision(snapshot.eventId()), updatedAt);
    }

    /**
     * Persists an in-phase snapshot only when {@code expectedRevision} still names the durable
     * event revision read by the caller.
     */
    fun saveSnapshot(snapshot: DefenseSessionSnapshot, expectedRevision: Long, updatedAt: Instant): OperationOutcome {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requireRevision(expectedRevision);
        if (snapshot.phase().isTerminal()) {
            throw IllegalArgumentException(
                    "Terminal snapshots must be persisted with finishEvent or recoverUnfinishedEvent");
        }

        try {
            return database.inImmediateTransaction({ connection ->
                var current = requireEvent(connection, snapshot.eventId());
                if (current.session().phase().isTerminal()) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }
                ensureSameSession(current.session(), snapshot);
                if (current.session().phase() != snapshot.phase()) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }
                if (!isPermittedInPhaseUpdate(current.session(), snapshot)) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }
                if (!persistSnapshot(
                        connection,
                        current.coreId(),
                        snapshot,
                        expectedRevision,
                        updatedAt)) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }
                replaceParticipants(connection, snapshot, updatedAt);
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            throw failure("save a defense snapshot", exception);
        }
    }

    /** Appends and applies one non-terminal lifecycle transition exactly once. */
    fun saveTransition(snapshot: DefenseSessionSnapshot, operationId: UUID, occurredAt: Instant): OperationOutcome {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(operationId, "operationId");
        var expectedRevision = operationExpectedRevisionOrCurrent(
                snapshot.eventId(), operationId);
        return saveTransition(snapshot, expectedRevision, operationId, occurredAt);
    }

    /** Appends one lifecycle transition using an operation-bound revision CAS. */
    fun saveTransition(snapshot: DefenseSessionSnapshot, expectedRevision: Long, operationId: UUID, occurredAt: Instant): OperationOutcome {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireRevision(expectedRevision);
        if (snapshot.phase().isTerminal()) {
            throw IllegalArgumentException(
                    "Terminal transitions must be persisted with finishEvent or recoverUnfinishedEvent");
        }

        var targetRevision = nextRevision(expectedRevision);
        var payloadFingerprint = payloadFingerprint(snapshot);

        try {
            return database.inImmediateTransaction({ connection ->
                var existingOperation = loadOperation(connection, operationId);
                if (existingOperation.isPresent()) {
                    requireMatchingOperation(
                            existingOperation.orElseThrow(),
                            snapshot.eventId(),
                            OperationKind.TRANSITION,
                            targetRevision,
                            payloadFingerprint);
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }

                var current = requireEvent(connection, snapshot.eventId());
                var from = current.session().phase();
                if (from.isTerminal()) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }
                ensureSameSession(current.session(), snapshot);
                if (!from.canTransitionTo(snapshot.phase())) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }

                if (!persistSnapshot(
                        connection,
                        current.coreId(),
                        snapshot,
                        expectedRevision,
                        occurredAt)) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
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
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The transition operation UUID conflicts with persisted data", exception);
            }
            throw failure("save a defense transition", exception);
        }
    }

    /**
     * Persists a normal terminal aggregate, final core HP, and global-lock release exactly once.
     */
    fun finishEvent(terminalSnapshot: DefenseSessionSnapshot, operationId: UUID, occurredAt: Instant): OperationOutcome {
        Objects.requireNonNull(terminalSnapshot, "terminalSnapshot");
        Objects.requireNonNull(operationId, "operationId");
        var expectedRevision = operationExpectedRevisionOrCurrent(
                terminalSnapshot.eventId(), operationId);
        return finishEvent(terminalSnapshot, expectedRevision, operationId, occurredAt);
    }

    /** Persists a normal terminal aggregate using an operation-bound revision CAS. */
    fun finishEvent(terminalSnapshot: DefenseSessionSnapshot, expectedRevision: Long, operationId: UUID, occurredAt: Instant): OperationOutcome {
        Objects.requireNonNull(terminalSnapshot, "terminalSnapshot");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireRevision(expectedRevision);
        if (!terminalSnapshot.phase().isTerminal()
                || terminalSnapshot.phase() == DefensePhase.RECOVERY) {
            throw IllegalArgumentException(
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
    fun recoverUnfinishedEvent(eventId: UUID, operationId: UUID, occurredAt: Instant): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        var expectedRevision = operationExpectedRevisionOrCurrent(eventId, operationId);
        return recoverUnfinishedEvent(eventId, expectedRevision, operationId, occurredAt);
    }

    /** Completes technical recovery using an operation-bound revision CAS. */
    fun recoverUnfinishedEvent(eventId: UUID, expectedRevision: Long, operationId: UUID, occurredAt: Instant): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireRevision(expectedRevision);
        var targetRevision = nextRevision(expectedRevision);

        try {
            return database.inImmediateTransaction({ connection ->
                var current = requireEvent(connection, eventId);
                var recovery = recoverySnapshot(current);
                var payloadFingerprint = payloadFingerprint(recovery);
                var existingOperation = loadOperation(connection, operationId);
                if (existingOperation.isPresent()) {
                    requireMatchingOperation(
                            existingOperation.orElseThrow(),
                            eventId,
                            OperationKind.RECOVER,
                            targetRevision,
                            payloadFingerprint);
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }

                if (current.session().phase().isTerminal()) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }
                var lockOwner = loadActiveEventId(connection);
                if (lockOwner.isPresent() && !lockOwner.orElseThrow().equals(eventId)) {
                    throw PersistenceException(
                            "Cannot recover an event while another event owns the global lock",
                            null);
                }
                if (BlockChangeRepository.hasUnresolved(connection, eventId)) {
                    throw PersistenceConflictException(
                            "Block changes must be rolled back or marked as conflicts before event recovery");
                }

                if (!persistSnapshot(
                        connection,
                        current.coreId(),
                        recovery,
                        expectedRevision,
                        occurredAt)) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
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
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The recovery operation UUID conflicts with persisted data", exception);
            }
            throw failure("recover an unfinished defense event", exception);
        }
    }

    /** Inserts or updates one logical enemy ledger entry. */
    fun upsertEnemy(enemy: EnemyLedgerEntry): Unit {
        Objects.requireNonNull(enemy, "enemy");
        try {
            database.inImmediateTransaction({ connection ->
                var event = requireEvent(connection, enemy.eventId());
                if (event.session().phase().isTerminal()) {
                    throw IllegalStateException("Cannot mutate enemies of a terminal event");
                }
                connection.prepareStatement("""
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
                        """.trimIndent()).use { statement ->
                    statement.setString(1, enemy.eventId().toString());
                    statement.setString(2, enemy.enemyId().toString());
                    statement.setString(3, enemy.entityId().toString());
                    statement.setString(4, enemy.enemyType());
                    statement.setInt(5, enemy.waveIndex());
                    statement.setString(6, enemy.status().name);
                    statement.setString(7, enemy.snapshot());
                    statement.setInt(8, enemy.snapshotVersion());
                    statement.setString(9, enemy.updatedAt().toString());
                    statement.executeUpdate();

}
                return@inImmediateTransaction null;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The enemy ledger entry conflicts with persisted identity data", exception);
            }
            throw failure("save an enemy ledger entry", exception);
        }
    }

    /**
     * Updates only the lifecycle status of an existing logical enemy. The physical entity UUID is
     * part of the compare key so a stale entity callback cannot mutate a respawned ledger row.
     */
    fun updateEnemyStatus(eventId: UUID, enemyId: UUID, entityId: UUID, status: EnemyStatus, updatedAt: Instant): Unit {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(enemyId, "enemyId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        try {
            database.inImmediateTransaction({ connection ->
                var event = requireEvent(connection, eventId);
                if (event.session().phase().isTerminal()) {
                    throw IllegalStateException("Cannot mutate enemies of a terminal event");
                }
                connection.prepareStatement("""
                        UPDATE event_enemies
                        SET status = ?, updated_at = ?
                        WHERE event_id = ? AND enemy_id = ? AND entity_id = ?
                        """.trimIndent()).use { statement ->
                    statement.setString(1, status.name);
                    statement.setString(2, updatedAt.toString());
                    statement.setString(3, eventId.toString());
                    statement.setString(4, enemyId.toString());
                    statement.setString(5, entityId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                                "Enemy identity does not match an existing ledger row");
                    }

}
                return@inImmediateTransaction null;
            });
        } catch (exception: SQLException) {
            throw failure("update an enemy ledger status", exception);
        }
    }

    fun loadEnemyLedger(eventId: UUID): List<EnemyLedgerEntry> {
        Objects.requireNonNull(eventId, "eventId");
        return read("load an enemy ledger", { connection ->
            var entries = ArrayList<EnemyLedgerEntry>();
            connection.prepareStatement("""
                    SELECT event_id, enemy_id, entity_id, enemy_type, wave_index, status,
                           snapshot, snapshot_version, updated_at
                    FROM event_enemies
                    WHERE event_id = ?
                    ORDER BY wave_index, enemy_id
                    """.trimIndent()).use { statement ->
                statement.setString(1, eventId.toString());
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        entries.add(EnemyLedgerEntry(
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
            return@read java.util.List.copyOf(entries);
        });
    }

    fun loadTransitions(eventId: UUID): List<EventTransitionRecord> {
        Objects.requireNonNull(eventId, "eventId");
        return read("load event transitions", { connection ->
            var transitions = ArrayList<EventTransitionRecord>();
            connection.prepareStatement("""
                    SELECT sequence, event_id, operation_id, from_state, to_state,
                           wave_index, pending_enemies, alive_enemies, occurred_at
                    FROM event_transitions
                    WHERE event_id = ?
                    ORDER BY sequence
                    """.trimIndent()).use { statement ->
                statement.setString(1, eventId.toString());
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        transitions.add(EventTransitionRecord(
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
            return@read java.util.List.copyOf(transitions);
        });
    }

    private fun finish(terminalSnapshot: DefenseSessionSnapshot, expectedRevision: Long, operationId: UUID, occurredAt: Instant, operationKind: OperationKind): OperationOutcome {
        var targetRevision = nextRevision(expectedRevision);
        var payloadFingerprint = payloadFingerprint(terminalSnapshot);
        try {
            return database.inImmediateTransaction({ connection ->
                var existingOperation = loadOperation(connection, operationId);
                if (existingOperation.isPresent()) {
                    requireMatchingOperation(
                            existingOperation.orElseThrow(),
                            terminalSnapshot.eventId(),
                            operationKind,
                            targetRevision,
                            payloadFingerprint);
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED;
                }

                var current = requireEvent(
                        connection, terminalSnapshot.eventId());
                var from = current.session().phase();
                if (from.isTerminal()) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_TERMINAL;
                }
                if (current.revision() != expectedRevision) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
                }
                ensureSameSession(current.session(), terminalSnapshot);
                if (!from.canTransitionTo(terminalSnapshot.phase())) {
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
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
                    return@inImmediateTransaction OperationOutcome.STATE_MISMATCH;
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
                return@inImmediateTransaction OperationOutcome.APPLIED;
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
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
    private fun mutateBattleFunds(eventId: UUID, teamId: UUID, actorId: UUID?, operationId: UUID, operationKind: String, amount: Long, appliedAt: Instant, spend: Boolean): BattleFundsMutationResult {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationKind, "operationKind");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (operationKind.isBlank()) {
            throw IllegalArgumentException("operationKind must not be blank");
        }
        if (amount <= 0L) {
            throw IllegalArgumentException("amount must be positive");
        }
        var fingerprint = managementFingerprint(
                if (spend) "BATTLE_FUNDS_SPEND" else "BATTLE_FUNDS_CREDIT",
                eventId,
                teamId,
                if (actorId == null) "SYSTEM" else actorId,
                operationKind,
                amount);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadBattleFundsOperation(
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
                    return@inImmediateTransaction BattleFundsMutationResult(
                            OperationOutcome.ALREADY_APPLIED,
                            requireBattleFunds(connection, eventId));
                }

                requireActiveBattleFundsEvent(connection, eventId, spend);
                var current = requireBattleFunds(connection, eventId);
                if (!current.teamId().equals(teamId)) {
                    throw PersistenceConflictException(
                            "The battle-funds operation belongs to another team");
                }
                if (spend) {
                    requireTeamMember(connection, teamId, actorId!!);
                    if (current.balance() < amount) {
                        throw PersistenceConflictException(
                                "The team does not have enough battle funds");
                    }
                }
                var nextBalance = 0L
                var nextEarned = current.totalEarned();
                var nextSpent = current.totalSpent();
                try {
                    if (spend) {
                        nextBalance = Math.subtractExact(current.balance(), amount);
                        nextSpent = Math.addExact(current.totalSpent(), amount);
                    } else {
                        nextBalance = Math.addExact(current.balance(), amount);
                        nextEarned = Math.addExact(current.totalEarned(), amount);
                    }
                } catch (overflow: ArithmeticException) {
                    throw PersistenceConflictException(
                            "The battle-funds account cannot represent this mutation", overflow);
                }
                var updated = BattleFunds(
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
                return@inImmediateTransaction BattleFundsMutationResult(OperationOutcome.APPLIED, updated);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The battle-funds operation conflicts with persisted data", exception);
            }
            throw failure("mutate event battle funds", exception);
        }
    }

    private fun requireActiveBattleFundsEvent(connection: Connection, eventId: UUID, spend: Boolean): Unit {
        var activeEvent = loadActiveEventId(connection);
        if (activeEvent.isEmpty() || !activeEvent.orElseThrow().equals(eventId)) {
            throw PersistenceConflictException(
                    "Battle funds are available only for the active defense event");
        }
        var event = requireEvent(connection, eventId);
        if (event.session().phase().isTerminal()
                || (spend && (event.session().phase() == DefensePhase.WAVE_ACTIVE
                        || event.session().phase() == DefensePhase.COUNTDOWN))) {
            throw PersistenceConflictException(
                    if (spend) {
                        "Battle funds may only be spent during preparation or intermission"
                    } else {
                        "The defense event is already terminal"
                    });
        }
    }

    private fun requireActiveTowerDamageEvent(connection: Connection, eventId: UUID, teamId: UUID): Unit {
        var activeEvent = loadActiveEventId(connection);
        if (activeEvent.isEmpty() || !activeEvent.orElseThrow().equals(eventId)) {
            throw PersistenceConflictException(
                    "Tower damage is available only for the active defense event");
        }
        var event = requireEvent(connection, eventId);
        if (!event.session().teamId().equals(teamId)
                || event.session().phase() != DefensePhase.WAVE_ACTIVE) {
            throw PersistenceConflictException(
                    "Tower damage is available only during the owning team's active wave");
        }
    }

    private fun insertBattleFunds(connection: Connection, eventId: UUID, teamId: UUID, createdAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_battle_funds(
                    event_id, team_id, balance, total_earned, total_spent, state, updated_at
                ) VALUES (?, ?, 0, 0, 0, 'ACTIVE', ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, eventId.toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, createdAt.toString());
            statement.executeUpdate();

}
    }

    private fun settleBattleFunds(connection: Connection, eventId: UUID, settledAt: Instant): Unit {
        connection.prepareStatement("""
                UPDATE event_battle_funds
                SET balance = 0, state = 'SETTLED', updated_at = ?
                WHERE event_id = ? AND state = 'ACTIVE'
                """.trimIndent()).use { statement ->
            statement.setString(1, settledAt.toString());
            statement.setString(2, eventId.toString());
            statement.executeUpdate();

}
    }

    private fun clearBattleBoosts(connection: Connection, eventId: UUID): Unit {
        connection.prepareStatement("""
                DELETE FROM event_tower_boosts WHERE event_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, eventId.toString());
            statement.executeUpdate();

}
    }

    private fun updateBattleFunds(connection: Connection, funds: BattleFunds): Unit {
        connection.prepareStatement("""
                UPDATE event_battle_funds
                SET balance = ?, total_earned = ?, total_spent = ?, state = ?, updated_at = ?
                WHERE event_id = ? AND state = 'ACTIVE'
                """.trimIndent()).use { statement ->
            statement.setLong(1, funds.balance());
            statement.setLong(2, funds.totalEarned());
            statement.setLong(3, funds.totalSpent());
            statement.setString(4, funds.state().name);
            statement.setString(5, funds.updatedAt().toString());
            statement.setString(6, funds.eventId().toString());
            if (statement.executeUpdate() != 1) {
                throw PersistenceConflictException(
                        "The battle-funds account was concurrently settled");
            }

}
    }

    private fun loadBattleFunds(connection: Connection, eventId: UUID): Optional<BattleFunds> {
        connection.prepareStatement("""
                SELECT event_id, team_id, balance, total_earned, total_spent, state, updated_at
                FROM event_battle_funds WHERE event_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, eventId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(battleFundsFromRow(resultSet)) else Optional.empty();

}

}
    }

    private fun requireBattleFunds(connection: Connection, eventId: UUID): BattleFunds {
        return loadBattleFunds(connection, eventId).orElseThrow { PersistenceConflictException(
                        "Defense event " + eventId + " has no battle-funds account") };
    }

    private fun battleFundsFromRow(resultSet: ResultSet): BattleFunds {
        return BattleFunds(
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("team_id")),
                resultSet.getLong("balance"),
                resultSet.getLong("total_earned"),
                resultSet.getLong("total_spent"),
                BattleFundsState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("updated_at")));
    }

    private fun battleBoostFromRow(resultSet: ResultSet): BattleBoost {
        return BattleBoost(
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("tower_id")),
                battleBoostKind(resultSet.getString("boost_kind")),
                resultSet.getInt("level"),
                resultSet.getDouble("multiplier"),
                instant(resultSet.getString("updated_at")));
    }

    private fun battleBoostKind(id: String): BattleBoostKind {
        for (kind in BattleBoostKind.values()) {
            if (kind.id().equals(id)) {
                return kind;
            }
        }
        throw PersistenceException("Unknown persisted battle boost kind: " + id, null);
    }

    private fun loadBattleBoost(connection: Connection, eventId: UUID, towerId: UUID, kind: BattleBoostKind): Optional<BattleBoost> {
        connection.prepareStatement("""
                SELECT event_id, team_id, tower_id, boost_kind, level, multiplier, updated_at
                FROM event_tower_boosts
                WHERE event_id = ? AND tower_id = ? AND boost_kind = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, eventId.toString());
            statement.setString(2, towerId.toString());
            statement.setString(3, kind.id());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(battleBoostFromRow(resultSet)) else Optional.empty();

}

}
    }

    private fun requireBattleBoost(connection: Connection, eventId: UUID, towerId: UUID, kind: BattleBoostKind): BattleBoost {
        return loadBattleBoost(connection, eventId, towerId, kind).orElseThrow { PersistenceConflictException(
                        "The battle boost was not stored for tower " + towerId) };
    }

    private fun upsertBattleBoost(connection: Connection, boost: BattleBoost): Unit {
        connection.prepareStatement("""
                INSERT INTO event_tower_boosts(
                    event_id, team_id, tower_id, boost_kind, level, multiplier, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(event_id, tower_id, boost_kind) DO UPDATE SET
                    team_id = excluded.team_id,
                    level = excluded.level,
                    multiplier = excluded.multiplier,
                    updated_at = excluded.updated_at
                """.trimIndent()).use { statement ->
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

    private fun insertBattleBoostOperation(connection: Connection, operationId: UUID, eventId: UUID, teamId: UUID, actorId: UUID, towerId: UUID, kind: BattleBoostKind, cost: Long, boostMultiplier: Double, fingerprint: String, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_tower_boost_operations(
                    operation_id, event_id, team_id, actor_id, tower_id, boost_kind,
                    cost, boost_multiplier, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
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

    private fun loadBattleBoostOperation(connection: Connection, operationId: UUID): Optional<BattleBoostOperation> {
        connection.prepareStatement("""
                SELECT event_id, team_id, actor_id, tower_id, boost_kind,
                       cost, boost_multiplier, payload_fingerprint
                FROM event_tower_boost_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(BattleBoostOperation(
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

    private fun requireMatchingBattleBoostOperation(existing: BattleBoostOperation, eventId: UUID, teamId: UUID, actorId: UUID, towerId: UUID, kind: BattleBoostKind, cost: Long, boostMultiplier: Double, fingerprint: String): Unit {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !existing.actorId().equals(actorId)
                || !existing.towerId().equals(towerId)
                || existing.kind() != kind
                || existing.cost() != cost
                || java.lang.Double.compare(existing.boostMultiplier(), boostMultiplier) != 0
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw PersistenceConflictException(
                    "The battle-boost operation UUID is already assigned to another payload");
        }
    }

    private fun loadTowerDurability(connection: Connection, towerId: UUID): Optional<TowerDurability> {
        connection.prepareStatement("""
                SELECT tower_id, team_id, current_hp, max_hp
                FROM towers WHERE tower_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, towerId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(TowerDurability(
                                uuid(resultSet.getString("tower_id")),
                                uuid(resultSet.getString("team_id")),
                                resultSet.getLong("current_hp"),
                                resultSet.getLong("max_hp"))) else Optional.empty()

}

}
    }

    private fun requireTowerDurability(connection: Connection, towerId: UUID): TowerDurability {
        return loadTowerDurability(connection, towerId).orElseThrow { PersistenceConflictException(
                        "The tower to repair does not exist") };
    }

    private fun updateTowerDurability(connection: Connection, durability: TowerDurability, updatedAt: Instant): Unit {
        connection.prepareStatement("""
                UPDATE towers
                SET current_hp = ?, updated_at = ?
                WHERE tower_id = ?
                """.trimIndent()).use { statement ->
            statement.setLong(1, durability.currentHitPoints);
            statement.setString(2, updatedAt.toString());
            statement.setString(3, durability.towerId.toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The tower durability update affected no rows");
            }

}
    }

    private fun deleteTower(connection: Connection, towerId: UUID, teamId: UUID): Unit {
        connection.prepareStatement(
                "DELETE FROM towers WHERE tower_id = ? AND team_id = ?").use { statement ->
            statement.setString(1, towerId.toString());
            statement.setString(2, teamId.toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The destroyed tower delete affected no rows");
            }

}
    }

    private fun insertTowerDamageOperation(connection: Connection, operation: TowerDamageOperation, operationId: UUID, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_tower_damage_operations(
                    operation_id, event_id, team_id, attacker_enemy_id, tower_id,
                    damage, remaining_hp, destroyed, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.setString(2, operation.eventId().toString());
            statement.setString(3, operation.teamId().toString());
            statement.setString(4, operation.attackerLogicalEnemyId().toString());
            statement.setString(5, operation.towerId().toString());
            statement.setLong(6, operation.damage());
            statement.setLong(7, operation.remainingHitPoints());
            statement.setInt(8, if (operation.destroyed()) 1 else 0);
            statement.setString(9, operation.payloadFingerprint());
            statement.setString(10, appliedAt.toString());
            statement.executeUpdate();

}
    }

    private fun loadTowerDamageOperation(connection: Connection, operationId: UUID): Optional<TowerDamageOperation> {
        connection.prepareStatement("""
                SELECT event_id, team_id, attacker_enemy_id, tower_id,
                       damage, remaining_hp, destroyed, payload_fingerprint
                FROM event_tower_damage_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(TowerDamageOperation(
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

    private fun requireMatchingTowerDamageOperation(existing: TowerDamageOperation, eventId: UUID, teamId: UUID, attackerLogicalEnemyId: UUID, towerId: UUID, damage: Long, fingerprint: String): Unit {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !existing.attackerLogicalEnemyId().equals(attackerLogicalEnemyId)
                || !existing.towerId().equals(towerId)
                || existing.damage() != damage
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw PersistenceConflictException(
                    "The tower-damage operation UUID is already assigned to another payload");
        }
    }

    private fun damageResult(operation: TowerDamageOperation, outcome: OperationOutcome): TowerDamageMutationResult {
        return TowerDamageMutationResult(
                outcome,
                operation.eventId(),
                operation.teamId(),
                operation.towerId(),
                operation.attackerLogicalEnemyId(),
                operation.damage(),
                operation.remainingHitPoints(),
                operation.destroyed());
    }

    private fun insertTowerRepairOperation(connection: Connection, operationId: UUID, eventId: UUID, teamId: UUID, actorId: UUID, towerId: UUID, repairedHitPoints: Long, cost: Long, fingerprint: String, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_tower_repair_operations(
                    operation_id, event_id, team_id, actor_id, tower_id,
                    repaired_hit_points, cost, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
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

    private fun loadTowerRepairOperation(connection: Connection, operationId: UUID): Optional<TowerRepairOperation> {
        connection.prepareStatement("""
                SELECT event_id, team_id, actor_id, tower_id,
                       repaired_hit_points, cost, payload_fingerprint
                FROM event_tower_repair_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(TowerRepairOperation(
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

    private fun requireMatchingTowerRepairOperation(existing: TowerRepairOperation, eventId: UUID, teamId: UUID, actorId: UUID, towerId: UUID, repairedHitPoints: Long, cost: Long, fingerprint: String): Unit {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !existing.actorId().equals(actorId)
                || !existing.towerId().equals(towerId)
                || existing.repairedHitPoints() != repairedHitPoints
                || existing.cost() != cost
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw PersistenceConflictException(
                    "The tower-repair operation UUID is already assigned to another payload");
        }
    }

    private fun requireTowerBelongsToTeam(connection: Connection, towerId: UUID, teamId: UUID): Unit {
        connection.prepareStatement("""
                SELECT team_id FROM towers WHERE tower_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, towerId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()
                        || !teamId.toString().equals(resultSet.getString("team_id"))) {
                    throw PersistenceConflictException(
                            "The tower is missing or belongs to another team");
                }

}

}
    }

    private fun insertBattleFundsOperation(connection: Connection, operationId: UUID, eventId: UUID, teamId: UUID, actorId: UUID?, operationKind: String, amount: Long, fingerprint: String, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_battle_fund_operations(
                    operation_id, event_id, team_id, actor_id, operation_kind,
                    amount, payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, teamId.toString());
            statement.setString(4, if (actorId == null) null else actorId.toString());
            statement.setString(5, operationKind);
            statement.setLong(6, amount);
            statement.setString(7, fingerprint);
            statement.setString(8, appliedAt.toString());
            statement.executeUpdate();

}
    }

    private fun loadBattleFundsOperation(connection: Connection, operationId: UUID): Optional<BattleFundsOperation> {
        connection.prepareStatement("""
                SELECT event_id, team_id, actor_id, operation_kind, amount, payload_fingerprint
                FROM event_battle_fund_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                var actor = resultSet.getString("actor_id");
                return Optional.of(BattleFundsOperation(
                        uuid(resultSet.getString("event_id")),
                        uuid(resultSet.getString("team_id")),
                        if (actor == null) null else uuid(actor),
                        resultSet.getString("operation_kind"),
                        resultSet.getLong("amount"),
                        resultSet.getString("payload_fingerprint")));

}

}
    }

    private fun requireMatchingBattleFundsOperation(existing: BattleFundsOperation, eventId: UUID, teamId: UUID, actorId: UUID?, operationKind: String, amount: Long, fingerprint: String): Unit {
        if (!existing.eventId().equals(eventId)
                || !existing.teamId().equals(teamId)
                || !Objects.equals(existing.actorId(), actorId)
                || !existing.operationKind().equals(operationKind)
                || existing.amount() != amount
                || !existing.payloadFingerprint().equals(fingerprint)) {
            throw PersistenceConflictException(
                    "The battle-funds operation UUID is already assigned to another payload");
        }
    }

    private fun issueVictoryResearchCrystals(connection: Connection, terminalSnapshot: DefenseSessionSnapshot, terminalOperationId: UUID, issuedAt: Instant): Unit {
        var beforeVictory = loadTeamProgress(connection, terminalSnapshot.teamId())
                .orElseThrow { PersistenceConflictException(
                        "Team " + terminalSnapshot.teamId() + " has no progression row") };
        var quantity = rewardSettings.researchCrystalQuantity(
                terminalSnapshot.stageLevel(), beforeVictory.highestClearedLevel());
        if (quantity <= 0) {
            return;
        }
        var batchId = deterministicUuid(
                terminalOperationId,
                "RESEARCH_CRYSTAL_BATCH",
                terminalSnapshot.eventId().toString());
        var createOperationId = deterministicUuid(
                terminalOperationId,
                "RESEARCH_CRYSTAL_DROP",
                batchId.toString());
        var payload = researchCrystalPayload(
                batchId,
                terminalSnapshot.teamId(),
                quantity);
        connection.prepareStatement("""
                INSERT INTO research_crystal_batches(
                    batch_id, event_id, team_id, stage_level, issued_quantity,
                    redeemed_quantity, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'ISSUED', ?, ?)
                ON CONFLICT(batch_id) DO NOTHING
                """.trimIndent()).use { statement ->
            statement.setString(1, batchId.toString());
            statement.setString(2, terminalSnapshot.eventId().toString());
            statement.setString(3, terminalSnapshot.teamId().toString());
            statement.setLong(4, terminalSnapshot.stageLevel());
            statement.setInt(5, quantity);
            statement.setString(6, issuedAt.toString());
            statement.setString(7, issuedAt.toString());
            statement.executeUpdate();

}
        var existing = loadResearchCrystalBatch(connection, batchId)
                .orElseThrow { SQLException(
                        "The research crystal batch was not persisted") };
        if (!existing.eventId().equals(terminalSnapshot.eventId())
                || !existing.teamId().equals(terminalSnapshot.teamId())
                || existing.stageLevel() != terminalSnapshot.stageLevel()
                || existing.issuedQuantity() != quantity) {
            throw PersistenceConflictException(
                    "The research crystal batch UUID is already assigned to another payload");
        }
        ensureResearchCrystalSegments(connection, batchId, quantity);
        connection.prepareStatement("""
                INSERT INTO event_drop_escrow(
                    drop_id, event_id, source_kind, source_id, item_id, item_payload,
                    quantity, claimed_quantity, status, display_entity_id,
                    create_operation_id, created_at, updated_at
                ) VALUES (?, ?, 'ENEMY', ?, 'research_crystal', ?, ?, 0, 'HELD', NULL, ?, ?, ?)
                ON CONFLICT(drop_id) DO NOTHING
                """.trimIndent()).use { statement ->
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

    private fun advanceTeamProgressAfterVictory(connection: Connection, teamId: UUID, stageLevel: Long, updatedAt: Instant): Unit {
        var current = loadTeamProgress(connection, teamId).orElseThrow { PersistenceConflictException(
                        "Team " + teamId + " has no progression row") };
        var advanced = current.afterVictory(stageLevel);
        if (advanced.equals(current)) {
            return;
        }
        connection.prepareStatement("""
                UPDATE team_progress
                SET highest_cleared_level = ?, unlocked_level = ?, updated_at = ?
                WHERE team_id = ?
                """.trimIndent()).use { statement ->
            statement.setLong(1, advanced.highestClearedLevel());
            statement.setLong(2, advanced.unlockedLevel());
            statement.setString(3, updatedAt.toString());
            statement.setString(4, teamId.toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The victory progression update affected no rows");
            }

}
    }

    private fun resolveTeamInvitation(invitationId: UUID, inviteeId: UUID, operationId: UUID, resolvedAt: Instant, operationKind: String, accept: Boolean): TeamInvitationMutationResult {
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(inviteeId, "inviteeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        var fingerprint = managementFingerprint(operationKind, invitationId, inviteeId);
        try {
            return database.inImmediateTransaction({ connection ->
                var existing = loadTeamInviteOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    requireMatchingTeamInviteOperation(
                            existing.orElseThrow(),
                            operationKind,
                            inviteeId,
                            fingerprint);
                    var invitation = requireTeamInvitation(
                            connection, existing.orElseThrow().inviteId());
                    return@inImmediateTransaction invitationMutation(
                            ManagementOutcome.ALREADY_APPLIED,
                            connection,
                            invitation);
                }
                requireNoActiveEvent(connection, if (accept) "accept a team invitation" else "decline a team invitation")
                var invitation = requireTeamInvitation(connection, invitationId);
                if (!invitation.inviteeId().equals(inviteeId)) {
                    throw PersistenceConflictException(
                            "This invitation is addressed to another player");
                }
                if (invitation.state() != TeamInvitationState.PENDING) {
                    throw PersistenceConflictException(
                            "This invitation is no longer pending");
                }
                if (!resolvedAt.isBefore(invitation.expiresAt())) {
                    expireInvitation(connection, invitationId, resolvedAt);
                    return@inImmediateTransaction invitationMutation(
                            ManagementOutcome.APPLIED,
                            connection,
                            requireTeamInvitation(connection, invitationId));
                }
                if (accept) {
                    var team = requireTeam(connection, invitation.teamId());
                    if (findTeamByMember(connection, inviteeId).isPresent()) {
                        throw PersistenceConflictException(
                                "The invited player already belongs to a team");
                    }
                    if (team.members().size >= MAX_TEAM_MEMBERS) {
                        throw PersistenceConflictException(
                                "The team has reached the maximum of " + MAX_TEAM_MEMBERS
                                        + " members");
                    }
                    connection.prepareStatement("""
                            INSERT INTO team_members(team_id, player_id, role, joined_at)
                            VALUES (?, ?, 'MEMBER', ?)
                            """.trimIndent()).use { statement ->
                        statement.setString(1, team.id().toString());
                        statement.setString(2, inviteeId.toString());
                        statement.setString(3, resolvedAt.toString());
                        statement.executeUpdate();

}
                }
                updateInvitationState(
                        connection,
                        invitationId,
                        if (accept) TeamInvitationState.ACCEPTED else TeamInvitationState.DECLINED,
                        resolvedAt);
                insertTeamInviteOperation(
                        connection,
                        operationId,
                        invitationId,
                        inviteeId,
                        operationKind,
                        fingerprint,
                        resolvedAt);
                var updated = requireTeamInvitation(connection, invitationId);
                return@inImmediateTransaction invitationMutation(ManagementOutcome.APPLIED, connection, updated);
            });
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                        "The invitation cannot change team membership", exception);
            }
            throw failure(
                    if (accept) "accept a team invitation" else "decline a team invitation",
                    exception);
        }
    }

    private fun invitationMutation(outcome: ManagementOutcome, connection: Connection, invitation: TeamInvitation): TeamInvitationMutationResult {
        return TeamInvitationMutationResult(
                outcome,
                invitation,
                loadTeam(connection, invitation.teamId()));
    }

    private fun insertTeamInvitation(connection: Connection, invitation: TeamInvitation, payloadFingerprint: String): Unit {
        connection.prepareStatement("""
                INSERT INTO team_invites(
                    invite_id, team_id, inviter_id, invitee_id, state,
                    created_at, expires_at, resolved_at, create_payload_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, invitation.id().toString());
            statement.setString(2, invitation.teamId().toString());
            statement.setString(3, invitation.inviterId().toString());
            statement.setString(4, invitation.inviteeId().toString());
            statement.setString(5, invitation.state().name);
            statement.setString(6, invitation.createdAt().toString());
            statement.setString(7, invitation.expiresAt().toString());
            statement.setString(8, nullableInstantString(invitation.resolvedAt()));
            statement.setString(9, payloadFingerprint);
            statement.executeUpdate();

}
    }

    private fun updateInvitationState(connection: Connection, invitationId: UUID, state: TeamInvitationState, resolvedAt: Instant): Unit {
        connection.prepareStatement("""
                UPDATE team_invites
                SET state = ?, resolved_at = ?
                WHERE invite_id = ? AND state = 'PENDING'
                """.trimIndent()).use { statement ->
            statement.setString(1, state.name);
            statement.setString(2, resolvedAt.toString());
            statement.setString(3, invitationId.toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The invitation state update affected no rows");
            }

}
    }

    private fun expireInvitation(connection: Connection, invitationId: UUID, resolvedAt: Instant): Unit {
        connection.prepareStatement("""
                UPDATE team_invites
                SET state = 'EXPIRED', resolved_at = ?
                WHERE invite_id = ? AND state = 'PENDING'
                """.trimIndent()).use { statement ->
            statement.setString(1, resolvedAt.toString());
            statement.setString(2, invitationId.toString());
            statement.executeUpdate();

}
    }

    private fun hasPendingInvitation(connection: Connection, teamId: UUID, inviteeId: UUID): Boolean {
        connection.prepareStatement("""
                SELECT 1 FROM team_invites
                WHERE team_id = ? AND invitee_id = ? AND state = 'PENDING'
                LIMIT 1
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.setString(2, inviteeId.toString());
            statement.executeQuery().use { resultSet ->
                return resultSet.next();

}

}
    }

    private fun loadTeamInvitation(connection: Connection, invitationId: UUID): Optional<TeamInvitation> {
        connection.prepareStatement("""
                SELECT invite_id, team_id, inviter_id, invitee_id, state,
                       created_at, expires_at, resolved_at
                FROM team_invites WHERE invite_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, invitationId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(teamInvitationFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun requireTeamInvitation(connection: Connection, invitationId: UUID): TeamInvitation {
        return loadTeamInvitation(connection, invitationId).orElseThrow { PersistenceConflictException(
                        "Team invitation " + invitationId + " does not exist") };
    }

    private fun teamInvitationFromRow(resultSet: ResultSet): TeamInvitation {
        return TeamInvitation(
                uuid(resultSet.getString("invite_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("inviter_id")),
                uuid(resultSet.getString("invitee_id")),
                TeamInvitationState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("expires_at")),
                nullableInstant(resultSet.getString("resolved_at")));
    }

    private fun insertTeamInviteOperation(connection: Connection, operationId: UUID, invitationId: UUID, actorId: UUID, operationKind: String, payloadFingerprint: String, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO team_invite_operations(
                    operation_id, invite_id, actor_id, operation_kind,
                    payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.setString(2, invitationId.toString());
            statement.setString(3, actorId.toString());
            statement.setString(4, operationKind);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, appliedAt.toString());
            statement.executeUpdate();

}
    }

    private fun loadTeamInviteOperation(connection: Connection, operationId: UUID): Optional<TeamInviteOperation> {
        connection.prepareStatement("""
                SELECT invite_id, actor_id, operation_kind, payload_fingerprint
                FROM team_invite_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(TeamInviteOperation(
                        uuid(resultSet.getString("invite_id")),
                        uuid(resultSet.getString("actor_id")),
                        resultSet.getString("operation_kind"),
                        resultSet.getString("payload_fingerprint")));

}

}
    }

    private fun requireMatchingTeamInviteOperation(operation: TeamInviteOperation, operationKind: String, actorId: UUID, payloadFingerprint: String): Unit {
        if (!operation.actorId().equals(actorId)
                || !operation.operationKind().equals(operationKind)
                || !operation.payloadFingerprint().equals(payloadFingerprint)) {
            throw PersistenceConflictException(
                    "The invitation operation UUID is already assigned to a different payload");
        }
    }

    private fun findTeamByMember(connection: Connection, playerId: UUID): Optional<TeamRecord> {
        connection.prepareStatement("""
                SELECT team_id FROM team_members WHERE player_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, playerId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return loadTeam(connection, uuid(resultSet.getString("team_id")));

}

}
    }

    private fun teamFromRow(connection: Connection, resultSet: ResultSet): TeamRecord {
        var teamId = uuid(resultSet.getString("team_id"));
        var ownerId = uuid(resultSet.getString("owner_player_id"));
        var createdAt = instant(resultSet.getString("created_at"));
        var members = LinkedHashSet<UUID>();
        connection.prepareStatement("""
                SELECT player_id FROM team_members WHERE team_id = ? ORDER BY player_id
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.executeQuery().use { membersResult ->
                while (membersResult.next()) {
                    members.add(uuid(membersResult.getString("player_id")));
                }

}

}
        return TeamRecord(
                teamId,
                ownerId,
                members,
                resultSet.getString("display_name"),
                createdAt);
    }

    private fun loadTeam(connection: Connection, teamId: UUID): Optional<TeamRecord> {
        connection.prepareStatement("""
                SELECT team_id, owner_player_id, display_name, created_at
                FROM teams WHERE team_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(teamFromRow(connection, resultSet));

}

}
    }

    private fun loadTeamProgress(connection: Connection, teamId: UUID): Optional<TeamProgress> {
        connection.prepareStatement("""
                SELECT team_id, highest_cleared_level, unlocked_level, research_points
                FROM team_progress WHERE team_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(TeamProgress(
                                uuid(resultSet.getString("team_id")),
                                resultSet.getLong("highest_cleared_level"),
                                resultSet.getLong("unlocked_level"),
                                resultSet.getLong("research_points"))) else Optional.empty()

}

}
    }

    private fun loadResearchCrystalBatch(connection: Connection, batchId: UUID): Optional<ResearchCrystalBatch> {
        connection.prepareStatement("""
                SELECT batch_id, event_id, team_id, stage_level, issued_quantity,
                       redeemed_quantity, state, created_at, updated_at
                FROM research_crystal_batches WHERE batch_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, batchId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(researchCrystalBatchFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun loadResearchCrystalSegment(connection: Connection, batchId: UUID, segmentOffset: Int): Optional<ResearchCrystalSegment> {
        connection.prepareStatement("""
                SELECT segment_quantity, redeemed_quantity
                FROM research_crystal_segments
                WHERE batch_id = ? AND segment_offset = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, batchId.toString());
            statement.setInt(2, segmentOffset);
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(ResearchCrystalSegment(
                                resultSet.getInt("segment_quantity"),
                                resultSet.getInt("redeemed_quantity"))) else Optional.empty()

}

}
    }

    private fun ensureResearchCrystalSegments(connection: Connection, batchId: UUID, issuedQuantity: Int): Unit {
        connection.prepareStatement("""
                INSERT INTO research_crystal_segments(
                    batch_id, segment_offset, segment_quantity)
                VALUES (?, ?, ?)
                ON CONFLICT(batch_id, segment_offset) DO NOTHING
                """.trimIndent()).use { statement ->
            for (offset in 0 until issuedQuantity step RESEARCH_CRYSTAL_SEGMENT_SIZE) {
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

    private fun researchCrystalBatchFromRow(resultSet: ResultSet): ResearchCrystalBatch {
        return ResearchCrystalBatch(
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

    private fun loadResearchCrystalRedemption(connection: Connection, operationId: UUID): Optional<ResearchCrystalRedemption> {
        connection.prepareStatement("""
                SELECT operation_id, batch_id, core_id, team_id, actor_id, quantity,
                       payload_fingerprint, segment_offset, segment_quantity, state,
                       prepared_at, applied_at, rolled_back_at
                FROM research_crystal_redemptions WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(researchCrystalRedemptionFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun researchCrystalRedemptionFromRow(resultSet: ResultSet): ResearchCrystalRedemption {
        return ResearchCrystalRedemption(
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

    private fun insertResearchCrystalRedemption(connection: Connection, redemption: ResearchCrystalRedemption): Unit {
        connection.prepareStatement("""
                INSERT INTO research_crystal_redemptions(
                    operation_id, batch_id, core_id, team_id, actor_id, quantity,
                    payload_fingerprint, segment_offset, segment_quantity,
                    state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """.trimIndent()).use { statement ->
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

    private fun crystalRedemptionResult(connection: Connection, outcome: OperationOutcome, batchId: UUID): ResearchCrystalRedemptionResult {
        var batch = loadResearchCrystalBatch(connection, batchId)
                .orElseThrow { SQLException("The crystal batch disappeared") };
        var progress = loadTeamProgress(connection, batch.teamId())
                .orElseThrow { SQLException("The crystal team progression disappeared") };
        return ResearchCrystalRedemptionResult(outcome, progress, batch);
    }

    private fun requireMatchingCrystalRedemption(redemption: ResearchCrystalRedemption, operationId: UUID, batchId: UUID, coreId: UUID, actorId: UUID, quantity: Int, fingerprint: String): Unit {
        if (!redemption.operationId().equals(operationId)
                || !redemption.batchId().equals(batchId)
                || !redemption.coreId().equals(coreId)
                || !redemption.actorId().equals(actorId)
                || redemption.quantity() != quantity
                || !redemption.payloadFingerprint().equals(fingerprint)) {
            throw PersistenceConflictException(
                    "The research crystal redemption UUID is already assigned to another payload");
        }
    }

    private fun requireTeam(connection: Connection, teamId: UUID): TeamRecord {
        return loadTeam(connection, teamId).orElseThrow { PersistenceConflictException("Team " + teamId + " does not exist") };
    }

    private fun requireCore(connection: Connection, coreId: UUID): CoreRecord {
        return loadCore(connection, coreId).orElseThrow { PersistenceConflictException("Core " + coreId + " does not exist") };
    }

    private fun requireTeamOwner(team: TeamRecord, actorId: UUID): Unit {
        if (!team.ownerId().equals(actorId)) {
            throw PersistenceConflictException(
                    "Only the team owner may perform this operation");
        }
    }

    private fun requireTeamMember(connection: Connection, teamId: UUID, playerId: UUID): Unit {
        connection.prepareStatement("""
                SELECT 1 FROM team_members WHERE team_id = ? AND player_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.setString(2, playerId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    throw PersistenceConflictException(
                            "Player " + playerId + " is not a member of team " + teamId);
                }

}

}
    }

    private fun requireNoActiveEvent(connection: Connection, operation: String): Unit {
        if (loadActiveEventId(connection).isPresent()) {
            throw PersistenceConflictException(
                    "Cannot " + operation + " while a defense event owns the global lock");
        }
    }

    private fun requireTeamCanBeDeleted(connection: Connection, teamId: UUID): Unit {
        connection.prepareStatement("""
                SELECT 1 FROM team_resource_balances
                WHERE team_id = ? AND balance > 0 LIMIT 1
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    throw PersistenceConflictException(
                            "A team with resource wallet points cannot be disbanded");
                }

}

}
        if (ResourceVoucherRepository.hasLiveVouchers(connection, teamId)) {
            throw PersistenceConflictException(
                    "A team with an unredeemed resource voucher cannot be disbanded");
        }
        if (loadCoreByTeam(connection, teamId).isPresent()) {
            throw PersistenceConflictException(
                    "A team with a core cannot be disbanded or left by its sole owner");
        }
        connection.prepareStatement("""
                SELECT 1 FROM defense_events WHERE team_id = ? LIMIT 1
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    throw PersistenceConflictException(
                            "A team with defense history cannot be deleted");
                }

}

}
        connection.prepareStatement("""
                SELECT 1 FROM team_resource_balances
                WHERE team_id = ? AND balance > 0
                LIMIT 1
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    throw PersistenceConflictException(
                            "A team with a non-zero resource wallet cannot be deleted");
                }

}

}
    }

    private fun deleteTeam(connection: Connection, teamId: UUID): Unit {
        connection.prepareStatement(
                "DELETE FROM teams WHERE team_id = ?").use { statement ->
            statement.setString(1, teamId.toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The team delete affected no rows");
            }

}
    }

    private fun insertTeamProfileOperation(connection: Connection, operationId: UUID, teamId: UUID, actorId: UUID, operationKind: String, payloadFingerprint: String, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO team_profile_operations(
                    operation_id, team_id, actor_id, operation_kind,
                    payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, actorId.toString());
            statement.setString(4, operationKind);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, appliedAt.toString());
            statement.executeUpdate();

}
    }

    private fun loadTeamProfileOperation(connection: Connection, operationId: UUID): Optional<TeamProfileOperation> {
        connection.prepareStatement("""
                SELECT team_id, actor_id, operation_kind, payload_fingerprint
                FROM team_profile_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(TeamProfileOperation(
                        uuid(resultSet.getString("team_id")),
                        uuid(resultSet.getString("actor_id")),
                        resultSet.getString("operation_kind"),
                        resultSet.getString("payload_fingerprint")));

}

}
    }

    private fun requireMatchingTeamProfileOperation(operation: TeamProfileOperation, teamId: UUID, actorId: UUID, payloadFingerprint: String): Unit {
        if (!operation.teamId().equals(teamId)
                || !operation.actorId().equals(actorId)
                || !operation.operationKind().equals("TEAM_RENAME")
                || !operation.payloadFingerprint().equals(payloadFingerprint)) {
            throw PersistenceConflictException(
                    "The team profile operation UUID is already assigned to a different payload");
        }
    }

    private fun insertCoreRepairOperation(connection: Connection, operation: CoreRepairOperation): Unit {
        connection.prepareStatement("""
                INSERT INTO core_repair_operations(
                    operation_id, core_id, team_id, actor_id, expected_current_hp,
                    repair_amount, defense_point_cost, payment_mode, vanilla_material,
                    vanilla_material_amount, legacy_defense_shard_amount,
                    payload_fingerprint, state, prepared_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, operation.coreId().toString());
            statement.setString(3, operation.teamId().toString());
            statement.setString(4, operation.actorId().toString());
            statement.setLong(5, operation.expectedCurrentHitPoints());
            statement.setLong(6, operation.repairAmount());
            statement.setLong(7, operation.defensePointCost());
            statement.setString(8, operation.paymentMode().name);
            statement.setString(9, operation.vanillaMaterial());
            statement.setLong(10, operation.vanillaMaterialAmount());
            statement.setLong(11, operation.legacyDefenseShardAmount());
            statement.setString(12, operation.payloadFingerprint());
            statement.setString(13, operation.preparedAt().toString());
            statement.executeUpdate();

}
    }

    private fun loadCoreRepairOperation(connection: Connection, operationId: UUID): Optional<CoreRepairOperation> {
        connection.prepareStatement("""
                SELECT operation_id, core_id, team_id, actor_id, expected_current_hp,
                       repair_amount, defense_point_cost, payment_mode, vanilla_material,
                       vanilla_material_amount, legacy_defense_shard_amount,
                       payload_fingerprint, state, prepared_at,
                       applied_at, rolled_back_at
                FROM core_repair_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(coreRepairOperationFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun coreRepairOperationFromRow(resultSet: ResultSet): CoreRepairOperation {
        return CoreRepairOperation(
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

    private fun requireMatchingCoreRepairOperation(existing: CoreRepairOperation, coreId: UUID, actorId: UUID, amount: Long, defensePointCost: Long, paymentMode: PaymentMode, vanillaMaterial: String, vanillaMaterialAmount: Long, legacyDefenseShardAmount: Long, payloadFingerprint: String): Unit {
        if (!existing.coreId().equals(coreId)
                || !existing.actorId().equals(actorId)
                || existing.repairAmount() != amount
                || existing.defensePointCost() != defensePointCost
                || existing.paymentMode() != paymentMode
                || !existing.vanillaMaterial().equals(vanillaMaterial)
                || existing.vanillaMaterialAmount() != vanillaMaterialAmount
                || existing.legacyDefenseShardAmount() != legacyDefenseShardAmount
                || !existing.payloadFingerprint().equals(payloadFingerprint)) {
            throw PersistenceConflictException(
                    "The core repair operation UUID is already assigned to another payload");
        }
    }

    private fun expectedReceiptMaterial(operation: CoreRepairOperation): String {
        return if (operation.paymentMode() == PaymentMode.LEGACY_ITEMS && operation.legacyDefenseShardAmount() > 0L) "CORE_REPAIR_BUNDLE" else operation.vanillaMaterial();
    }

    private fun expectedReceiptQuantity(operation: CoreRepairOperation): Long {
        return Math.addExact(operation.vanillaMaterialAmount(), operation.legacyDefenseShardAmount());
    }

    private fun updateCoreRepairOperationState(connection: Connection, operationId: UUID, state: CoreRepairOperationState, at: Instant): Unit {
        var sql = when (state) {
            CoreRepairOperationState.APPLIED -> """
                    UPDATE core_repair_operations
                    SET state = 'APPLIED', applied_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent()
            CoreRepairOperationState.ROLLED_BACK -> """
                    UPDATE core_repair_operations
                    SET state = 'ROLLED_BACK', rolled_back_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent()
            CoreRepairOperationState.PREPARED -> throw IllegalArgumentException(
                "A core repair cannot transition back to PREPARED")
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, at.toString());
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw PersistenceConflictException(
                        "The core repair state changed concurrently");
            }

}
    }

    private fun insertCoreRepairReceipt(connection: Connection, receipt: CoreRepairReceipt): Unit {
        connection.prepareStatement("""
                INSERT INTO core_repair_receipts(
                    operation_id, player_id, material, quantity, state, reserved_at)
                VALUES (?, ?, ?, ?, 'RESERVED', ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, receipt.operationId().toString());
            statement.setString(2, receipt.playerId().toString());
            statement.setString(3, receipt.material());
            statement.setLong(4, receipt.quantity());
            statement.setString(5, receipt.reservedAt().toString());
            statement.executeUpdate();

}
    }

    private fun loadCoreRepairReceipt(connection: Connection, operationId: UUID): Optional<CoreRepairReceipt> {
        connection.prepareStatement("""
                SELECT operation_id, player_id, material, quantity, state,
                       reserved_at, resolved_at
                FROM core_repair_receipts WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(CoreRepairReceipt(
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

    private fun updateCoreRepairReceiptState(connection: Connection, operationId: UUID, state: CoreRepairReceiptState, at: Instant): Unit {
        connection.prepareStatement("""
                UPDATE core_repair_receipts
                SET state = ?, resolved_at = ?
                WHERE operation_id = ?
                  AND ((? = 'CLEARED' AND state IN ('SECURED', 'CLEAR_PENDING'))
                       OR (? = 'RESTORED' AND state IN ('RESERVED', 'SECURED', 'RETURN_PENDING'))
                       OR (? = 'RETURN_PENDING' AND state IN ('RESERVED', 'SECURED'))
                       OR (? = 'CLEAR_PENDING' AND state = 'SECURED'))
                """.trimIndent()).use { statement ->
            statement.setString(1, state.name);
            statement.setString(2, at.toString());
            statement.setString(3, operationId.toString());
            statement.setString(4, state.name);
            statement.setString(5, state.name);
            statement.setString(6, state.name);
            statement.setString(7, state.name);
            statement.executeUpdate();

}
    }

    private fun insertManagementOperation(connection: Connection, operationId: UUID, resourceType: String, resourceId: UUID, operationKind: String, payloadFingerprint: String, appliedAt: Instant): Unit {
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

    private fun insertManagementOperation(connection: Connection, operationId: UUID, resourceType: String, resourceId: UUID, operationKind: String, payloadFingerprint: String, paymentMode: PaymentMode, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO management_operations(
                    operation_id, resource_type, resource_id, operation_kind,
                    payload_fingerprint, payment_mode, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.setString(2, resourceType);
            statement.setString(3, resourceId.toString());
            statement.setString(4, operationKind);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, paymentMode.name);
            statement.setString(7, appliedAt.toString());
            statement.executeUpdate();

}
    }

    private fun loadManagementOperation(connection: Connection, operationId: UUID): Optional<ManagementOperation> {
        connection.prepareStatement("""
                SELECT resource_type, resource_id, operation_kind, payload_fingerprint, payment_mode
                FROM management_operations WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(ManagementOperation(
                        resultSet.getString("resource_type"),
                        uuid(resultSet.getString("resource_id")),
                        resultSet.getString("operation_kind"),
                        resultSet.getString("payload_fingerprint"),
                        PaymentMode.valueOf(resultSet.getString("payment_mode"))));

}

}
    }

    private fun requireMatchingManagementOperation(operation: ManagementOperation, resourceType: String, resourceId: UUID, operationKind: String, payloadFingerprint: String): Unit {
        requireMatchingManagementOperation(
                operation,
                resourceType,
                resourceId,
                operationKind,
                payloadFingerprint,
                PaymentMode.LEGACY_ITEMS);
    }

    private fun requireMatchingManagementOperation(operation: ManagementOperation, resourceType: String, resourceId: UUID, operationKind: String, payloadFingerprint: String, paymentMode: PaymentMode): Unit {
        if (!operation.resourceType().equals(resourceType)
                || !operation.resourceId().equals(resourceId)
                || !operation.operationKind().equals(operationKind)
                || !operation.payloadFingerprint().equals(payloadFingerprint)
                || operation.paymentMode() != paymentMode) {
            throw PersistenceConflictException(
                    "The management operation UUID is already assigned to a different payload");
        }
    }

    private fun managementFingerprint(operationKind: String, vararg values: Any?): String {
        var canonical = StringBuilder(operationKind);
        canonical.append('|');
        for (value in values) {
            canonical.append(Objects.requireNonNull(value, "management fingerprint value"));
            canonical.append('|');
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().toByteArray(StandardCharsets.UTF_8)));
        } catch (exception: NoSuchAlgorithmException) {
            throw AssertionError("Every Java runtime must provide SHA-256", exception);
        }
    }

    private fun placeCore(connection: Connection, core: CoreRecord, minimumCoreDistance: Double): CoreRecord {
        requireNoActiveEvent(connection, "place a core");
        if (loadCoreByTeam(connection, core.teamId()).isPresent()) {
            throw PersistenceConflictException(
                    "Team " + core.teamId() + " already owns a core");
        }
        var nearby = findDistanceConflict(
                connection,
                core.worldId(),
                core.blockX(),
                core.blockZ(),
                minimumCoreDistance,
                null);
        if (nearby.isPresent()) {
            throw PersistenceConflictException(
                    "Core position is too close to core " + nearby.orElseThrow().id());
        }
        insertCore(connection, core);
        return core;
    }

    private fun updateCoreHealth(connection: Connection, core: CoreRecord): Unit {
        connection.prepareStatement("""
                UPDATE cores SET current_hp = ?, updated_at = ? WHERE core_id = ?
                """.trimIndent()).use { statement ->
            statement.setLong(1, core.currentHitPoints());
            statement.setString(2, core.updatedAt().toString());
            statement.setString(3, core.id().toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The core health update affected no rows");
            }

}
    }

    private fun updateCorePosition(connection: Connection, core: CoreRecord): Unit {
        connection.prepareStatement("""
                UPDATE cores
                SET world_id = ?, block_x = ?, block_y = ?, block_z = ?, updated_at = ?
                WHERE core_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, core.worldId().toString());
            statement.setInt(2, core.blockX());
            statement.setInt(3, core.blockY());
            statement.setInt(4, core.blockZ());
            statement.setString(5, core.updatedAt().toString());
            statement.setString(6, core.id().toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The core position update affected no rows");
            }

}
    }

    private fun updateCore(connection: Connection, core: CoreRecord): Unit {
        connection.prepareStatement("""
                UPDATE cores
                SET team_id = ?, world_id = ?, block_x = ?, block_y = ?, block_z = ?,
                    current_hp = ?, max_hp = ?, created_at = ?, updated_at = ?
                WHERE core_id = ?
                """.trimIndent()).use { statement ->
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
                throw SQLException("The core rebuild update affected no rows");
            }

}
    }

    private fun insertCore(connection: Connection, core: CoreRecord): Unit {
        connection.prepareStatement("""
                INSERT INTO cores(
                    core_id, team_id, world_id, block_x, block_y, block_z,
                    current_hp, max_hp, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
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

    private fun loadCore(connection: Connection, coreId: UUID): Optional<CoreRecord> {
        connection.prepareStatement("""
                SELECT core_id, team_id, world_id, block_x, block_y, block_z,
                       current_hp, max_hp, created_at, updated_at
                FROM cores WHERE core_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, coreId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(coreFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun loadCoreByTeam(connection: Connection, teamId: UUID): Optional<CoreRecord> {
        connection.prepareStatement("""
                SELECT core_id, team_id, world_id, block_x, block_y, block_z,
                       current_hp, max_hp, created_at, updated_at
                FROM cores WHERE team_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, teamId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(coreFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun loadCorePlacementsByState(state: CorePlacementState): List<CorePlacement> {
        Objects.requireNonNull(state, "state");
        return read("load core placements", { connection ->
            var placements = ArrayList<CorePlacement>();
            connection.prepareStatement("""
                    SELECT operation_id, item_id, core_id, actor_id, team_id, world_id,
                           block_x, block_y, block_z, max_hp, minimum_core_distance,
                           rebuilding_destroyed_core, relocating_existing_core,
                           previous_block_data, state,
                           prepared_at, applied_at, rolled_back_at
                    FROM core_placement_operations
                    WHERE state = ?
                    ORDER BY prepared_at, operation_id
                    """.trimIndent()).use { statement ->
                statement.setString(1, state.name);
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        placements.add(corePlacementFromRow(resultSet));
                    }

}

}
            return@read java.util.List.copyOf(placements);
        });
    }

    private fun insertEmptyResourceBalances(connection: Connection, teamId: UUID, createdAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO team_resource_balances(team_id, resource_type, balance, updated_at)
                VALUES (?, ?, 0, ?)
                """.trimIndent()).use { statement ->
            for (resourceType in ResourceType.values()) {
                statement.setString(1, teamId.toString());
                statement.setString(2, resourceType.name);
                statement.setString(3, createdAt.toString());
                statement.addBatch();
            }
            statement.executeBatch();

}
    }

    private fun loadCorePlacement(connection: Connection, operationId: UUID): Optional<CorePlacement> {
        connection.prepareStatement("""
                SELECT operation_id, item_id, core_id, actor_id, team_id, world_id,
                       block_x, block_y, block_z, max_hp, minimum_core_distance,
                       rebuilding_destroyed_core, relocating_existing_core,
                       previous_block_data, state,
                       prepared_at, applied_at, rolled_back_at
                FROM core_placement_operations
                WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(corePlacementFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun insertCorePlacement(connection: Connection, placement: CorePlacement): Unit {
        connection.prepareStatement("""
                INSERT INTO core_placement_operations(
                    operation_id, item_id, core_id, actor_id, team_id, world_id,
                    block_x, block_y, block_z, max_hp, minimum_core_distance,
                    rebuilding_destroyed_core, relocating_existing_core,
                    previous_block_data, state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """.trimIndent()).use { statement ->
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
            statement.setInt(12, if (placement.rebuildingDestroyedCore()) 1 else 0);
            statement.setInt(13, if (placement.relocatingExistingCore()) 1 else 0);
            statement.setString(14, placement.previousBlockData());
            statement.setString(15, placement.preparedAt().toString());
            statement.executeUpdate();

}
    }

    private fun updateCorePlacementState(connection: Connection, placement: CorePlacement): Unit {
        var sql = ""
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
            throw IllegalArgumentException("Only terminal placement states can be persisted");
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(
                    1,
                    if (placement.state() == CorePlacementState.APPLIED) {
                        placement.appliedAt().toString()
                    } else {
                        placement.rolledBackAt().toString()
                    });
            statement.setString(2, placement.operationId().toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The core placement state update affected no rows");
            }

}
    }

    private fun corePlacementFromRow(resultSet: ResultSet): CorePlacement {
        return CorePlacement(
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

    private fun requireMatchingCorePlacement(existing: CorePlacement, requested: CorePlacement): Unit {
        if (!existing.itemId().equals(requested.itemId())
                || !existing.coreId().equals(requested.coreId())
                || !existing.actorId().equals(requested.actorId())
                || !existing.teamId().equals(requested.teamId())
                || !existing.worldId().equals(requested.worldId())
                || existing.blockX() != requested.blockX()
                || existing.blockY() != requested.blockY()
                || existing.blockZ() != requested.blockZ()
                || existing.maximumHitPoints() != requested.maximumHitPoints()
                || java.lang.Double.compare(
                                existing.minimumCoreDistance(), requested.minimumCoreDistance())
                        != 0
                || existing.rebuildingDestroyedCore() != requested.rebuildingDestroyedCore()
                || existing.relocatingExistingCore() != requested.relocatingExistingCore()
                || !existing.previousBlockData().equals(requested.previousBlockData())) {
            throw PersistenceConflictException(
                    "The operation UUID was reused with a different core placement payload");
        }
    }

    private fun findDistanceConflict(connection: Connection, worldId: UUID, blockX: Int, blockZ: Int, minimumCoreDistance: Double): Optional<CoreRecord> {
        return findDistanceConflict(
                connection, worldId, blockX, blockZ, minimumCoreDistance, null);
    }

    private fun findDistanceConflict(connection: Connection, worldId: UUID, blockX: Int, blockZ: Int, minimumCoreDistance: Double, excludedCoreId: UUID?): Optional<CoreRecord> {
        if (minimumCoreDistance == 0.0) {
            return Optional.empty();
        }
        connection.prepareStatement("""
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
                """.trimIndent()).use { statement ->
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
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) Optional.of(coreFromRow(resultSet)) else Optional.empty()

}

}
    }

    private fun coreFromRow(resultSet: ResultSet): CoreRecord {
        return CoreRecord(
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

    private fun insertEvent(connection: Connection, request: StartRequest, core: CoreRecord): Unit {
        var snapshot = request.session();
        connection.prepareStatement("""
                INSERT INTO defense_events(
                    event_id, team_id, core_id, state, stage_level, total_waves,
                    participant_limit, participants_frozen, wave_index, pending_enemies,
                    alive_enemies, start_core_hp, start_core_max_hp, core_hp, core_max_hp,
                    core_present, core_world_id, core_block_x, core_block_y, core_block_z,
                    config_snapshot, config_version, started_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, snapshot.eventId().toString());
            statement.setString(2, snapshot.teamId().toString());
            statement.setString(3, core.id().toString());
            statement.setString(4, snapshot.phase().name);
            statement.setLong(5, snapshot.stageLevel());
            statement.setInt(6, snapshot.totalWaves());
            statement.setInt(7, snapshot.participantLimit());
            statement.setInt(8, if (snapshot.participantsFrozen()) 1 else 0);
            statement.setInt(9, snapshot.currentWave());
            statement.setLong(10, snapshot.pendingEnemies());
            statement.setLong(11, snapshot.aliveEnemies());
            statement.setLong(12, core.currentHitPoints());
            statement.setLong(13, core.maximumHitPoints());
            statement.setLong(14, snapshot.coreState().currentHitPoints);
            statement.setLong(15, snapshot.coreState().maximumHitPoints);
            statement.setInt(16, if (snapshot.coreState().present) 1 else 0);
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

    private fun loadEvent(connection: Connection, eventId: UUID): Optional<StoredDefenseEvent> {
        connection.prepareStatement("""
                SELECT * FROM defense_events WHERE event_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, eventId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                var participants = loadParticipants(connection, eventId);
                var snapshot = DefenseSessionSnapshot(
                        eventId,
                        uuid(resultSet.getString("team_id")),
                        resultSet.getLong("stage_level"),
                        resultSet.getInt("total_waves"),
                        resultSet.getInt("participant_limit"),
                        DefensePhase.valueOf(resultSet.getString("state")),
                        resultSet.getInt("wave_index"),
                        java.util.HashSet(participants.registered()),
                        java.util.HashSet(participants.effective()),
                        resultSet.getInt("participants_frozen") != 0,
                        resultSet.getLong("pending_enemies"),
                        resultSet.getLong("alive_enemies"),
                        CoreState(
                                resultSet.getLong("core_max_hp"),
                                resultSet.getLong("core_hp"),
                                resultSet.getInt("core_present") != 0));
                var terminalOperation = resultSet.getString("terminal_operation_id");
                var terminalAt = resultSet.getString("terminal_at");
                return Optional.of(StoredDefenseEvent(
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
                        if (terminalOperation == null) Optional.empty() else Optional.of(uuid(terminalOperation)),
                        if (terminalAt == null) Optional.empty() else Optional.of(instant(terminalAt))));

}

}
    }

    private fun requireEvent(connection: Connection, eventId: UUID): StoredDefenseEvent {
        return loadEvent(connection, eventId).orElseThrow { IllegalArgumentException("Unknown defense event " + eventId) };
    }

    private fun loadParticipants(connection: Connection, eventId: UUID): ParticipantSets {
        var registered = LinkedHashSet<UUID>();
        var effective = LinkedHashSet<UUID>();
        connection.prepareStatement("""
                SELECT player_id, registered, effective
                FROM event_participants
                WHERE event_id = ?
                ORDER BY player_id
                """.trimIndent()).use { statement ->
            statement.setString(1, eventId.toString());
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    var playerId = uuid(resultSet.getString("player_id"));
                    if (resultSet.getInt("registered") != 0) {
                        registered.add(playerId);
                    }
                    if (resultSet.getInt("effective") != 0) {
                        effective.add(playerId);
                    }
                }

}

}
        return ParticipantSets(
                registered as java.util.Set<UUID>,
                effective as java.util.Set<UUID>);
    }

    private fun replaceParticipants(connection: Connection, snapshot: DefenseSessionSnapshot, changedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_participants(
                    event_id, player_id, registered, effective, joined_at
                ) VALUES (?, ?, ?, 1, ?)
                ON CONFLICT(event_id, player_id) DO UPDATE SET
                    registered = excluded.registered,
                    effective = 1,
                    joined_at = excluded.joined_at
                """.trimIndent()).use { statement ->
            for (playerId in snapshot.effectiveParticipants()) {
                statement.setString(1, snapshot.eventId().toString());
                statement.setString(2, playerId.toString());
                statement.setInt(
                        3, if (snapshot.registeredParticipants().contains(playerId)) 1 else 0);
                statement.setString(4, changedAt.toString());
                statement.addBatch();
            }
            statement.executeBatch();

}
    }

    private fun persistSnapshot(connection: Connection, coreId: UUID, snapshot: DefenseSessionSnapshot, expectedRevision: Long, updatedAt: Instant): Boolean {
        connection.prepareStatement("""
                UPDATE defense_events
                SET state = ?, stage_level = ?, total_waves = ?, participant_limit = ?,
                    participants_frozen = ?, wave_index = ?, pending_enemies = ?,
                    alive_enemies = ?, core_hp = ?, core_max_hp = ?, core_present = ?,
                    updated_at = ?, revision = revision + 1
                WHERE event_id = ? AND revision = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, snapshot.phase().name);
            statement.setLong(2, snapshot.stageLevel());
            statement.setInt(3, snapshot.totalWaves());
            statement.setInt(4, snapshot.participantLimit());
            statement.setInt(5, if (snapshot.participantsFrozen()) 1 else 0);
            statement.setInt(6, snapshot.currentWave());
            statement.setLong(7, snapshot.pendingEnemies());
            statement.setLong(8, snapshot.aliveEnemies());
            statement.setLong(9, snapshot.coreState().currentHitPoints);
            statement.setLong(10, snapshot.coreState().maximumHitPoints);
            statement.setInt(11, if (snapshot.coreState().present) 1 else 0);
            statement.setString(12, updatedAt.toString());
            statement.setString(13, snapshot.eventId().toString());
            statement.setLong(14, expectedRevision);
            if (statement.executeUpdate() != 1) {
                return false;
            }

}
        connection.prepareStatement("""
                UPDATE cores
                SET current_hp = ?, max_hp = ?, updated_at = ?
                WHERE core_id = ?
                """.trimIndent()).use { statement ->
            statement.setLong(1, snapshot.coreState().currentHitPoints);
            statement.setLong(2, snapshot.coreState().maximumHitPoints);
            statement.setString(3, updatedAt.toString());
            statement.setString(4, coreId.toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The event core update affected no rows");
            }

}
        return true;
    }

    private fun insertTransition(connection: Connection, operationId: UUID, from: DefensePhase, snapshot: DefenseSessionSnapshot, occurredAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_transitions(
                    event_id, operation_id, from_state, to_state, wave_index,
                    pending_enemies, alive_enemies, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, snapshot.eventId().toString());
            statement.setString(2, operationId.toString());
            statement.setString(3, from.name);
            statement.setString(4, snapshot.phase().name);
            statement.setInt(5, snapshot.currentWave());
            statement.setLong(6, snapshot.pendingEnemies());
            statement.setLong(7, snapshot.aliveEnemies());
            statement.setString(8, occurredAt.toString());
            statement.executeUpdate();

}
    }

    private fun insertOperation(connection: Connection, operationId: UUID, eventId: UUID, kind: OperationKind, targetRevision: Long, payloadFingerprint: String, appliedAt: Instant): Unit {
        connection.prepareStatement("""
                INSERT INTO event_operations(
                    operation_id, event_id, operation_kind, target_revision,
                    payload_fingerprint, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, kind.name);
            statement.setLong(4, targetRevision);
            statement.setString(5, payloadFingerprint);
            statement.setString(6, appliedAt.toString());
            statement.executeUpdate();

}
    }

    private fun loadOperation(connection: Connection, operationId: UUID): Optional<OperationRow> {
        connection.prepareStatement("""
                SELECT event_id, operation_kind, target_revision, payload_fingerprint
                FROM event_operations
                WHERE operation_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(OperationRow(
                        uuid(resultSet.getString("event_id")),
                        OperationKind.valueOf(resultSet.getString("operation_kind")),
                        resultSet.getLong("target_revision"),
                        resultSet.getString("payload_fingerprint")));

}

}
    }

    private fun requireMatchingOperation(operation: OperationRow, eventId: UUID, kind: OperationKind, targetRevision: Long, payloadFingerprint: String): Unit {
        if (!operation.eventId().equals(eventId)
                || operation.kind() != kind
                || operation.targetRevision() != targetRevision
                || !operation.payloadFingerprint().equals(payloadFingerprint)) {
            throw PersistenceConflictException(
                    "The operation UUID is already assigned to a different payload or revision");
        }
    }

    private fun markTerminal(connection: Connection, eventId: UUID, operationId: UUID, terminalAt: Instant): Unit {
        connection.prepareStatement("""
                UPDATE defense_events
                SET terminal_operation_id = ?, terminal_at = ?
                WHERE event_id = ?
                """.trimIndent()).use { statement ->
            statement.setString(1, operationId.toString());
            statement.setString(2, terminalAt.toString());
            statement.setString(3, eventId.toString());
            if (statement.executeUpdate() != 1) {
                throw SQLException("The terminal event update affected no rows");
            }

}
    }

    private fun markEnemiesRecoveryRemoved(connection: Connection, eventId: UUID, occurredAt: Instant): Unit {
        connection.prepareStatement("""
                UPDATE event_enemies
                SET status = 'RECOVERY_REMOVED', updated_at = ?
                WHERE event_id = ? AND status IN ('ALLOCATED', 'SPAWNED')
                """.trimIndent()).use { statement ->
            statement.setString(1, occurredAt.toString());
            statement.setString(2, eventId.toString());
            statement.executeUpdate();

}
    }

    private fun markEnemiesDespawned(connection: Connection, eventId: UUID, occurredAt: Instant): Unit {
        connection.prepareStatement("""
                UPDATE event_enemies
                SET status = 'DESPAWNED', updated_at = ?
                WHERE event_id = ?
                  AND status NOT IN ('DEAD', 'DESPAWNED', 'RECOVERY_REMOVED')
                """.trimIndent()).use { statement ->
            statement.setString(1, occurredAt.toString());
            statement.setString(2, eventId.toString());
            statement.executeUpdate();

}
    }

    private fun releaseEventLock(connection: Connection, eventId: UUID): Unit {
        connection.prepareStatement(
                "DELETE FROM event_lock WHERE singleton = 1 AND event_id = ?").use { statement ->
            statement.setString(1, eventId.toString());
            statement.executeUpdate();

}
    }

    private fun loadActiveEventId(connection: Connection): Optional<UUID> {
        connection.prepareStatement(
                "SELECT event_id FROM event_lock WHERE singleton = 1").use { statement ->
statement.executeQuery().use { resultSet ->
            return if (resultSet.next()) Optional.of(uuid(resultSet.getString("event_id"))) else Optional.empty()

}}
    }

    private fun eventExists(connection: Connection, eventId: UUID): Boolean {
        connection.prepareStatement(
                "SELECT 1 FROM defense_events WHERE event_id = ?").use { statement ->
            statement.setString(1, eventId.toString());
            statement.executeQuery().use { resultSet ->
                return resultSet.next();

}

}
    }

    private fun recoverySnapshot(event: StoredDefenseEvent): DefenseSessionSnapshot {
        var current = event.session();
        return DefenseSessionSnapshot(
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
                CoreState(
                        event.startCoreMaximumHitPoints(),
                        event.startCoreHitPoints(),
                        true));
    }

    private fun validateStartCore(snapshot: DefenseSessionSnapshot, core: CoreRecord): Unit {
        if (!snapshot.teamId().equals(core.teamId())) {
            throw PersistenceConflictException("The selected core belongs to another team");
        }
        if (snapshot.coreState().maximumHitPoints != core.maximumHitPoints()
                || snapshot.coreState().currentHitPoints != core.currentHitPoints()) {
            throw PersistenceConflictException(
                    "The session core snapshot is stale compared with the database");
        }
    }

    private fun ensureSameSession(current: DefenseSessionSnapshot, next: DefenseSessionSnapshot): Unit {
        if (!current.eventId().equals(next.eventId())
                || !current.teamId().equals(next.teamId())
                || current.stageLevel() != next.stageLevel()
                || current.totalWaves() != next.totalWaves()
                || current.participantLimit() != next.participantLimit()
                || current.coreState().maximumHitPoints
                        != next.coreState().maximumHitPoints) {
            throw IllegalArgumentException(
                    "A persisted session's immutable identity and configuration cannot change");
        }
    }

    private fun isPermittedInPhaseUpdate(current: DefenseSessionSnapshot, next: DefenseSessionSnapshot): Boolean {
        if (current.currentWave() != next.currentWave()
                || !current.registeredParticipants().equals(next.registeredParticipants())
                || !next.effectiveParticipants().containsAll(current.effectiveParticipants())) {
            return false;
        }
        return next.coreState().currentHitPoints <= current.coreState().currentHitPoints;
    }

    private fun requireDistance(minimumCoreDistance: Double): Unit {
        if (!java.lang.Double.isFinite(minimumCoreDistance) || minimumCoreDistance < 0.0) {
            throw IllegalArgumentException(
                    "minimumCoreDistance must be a finite, non-negative number");
        }
    }

    private fun currentRevision(eventId: UUID): Long {
        Objects.requireNonNull(eventId, "eventId");
        return read(
                "load the current event revision",
                { connection -> requireEvent(connection, eventId).revision() });
    }

    private fun operationExpectedRevisionOrCurrent(eventId: UUID, operationId: UUID): Long {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        return read("load an operation revision", { connection ->
            var operation = loadOperation(connection, operationId);
            if (operation.isEmpty()) {
                return@read requireEvent(connection, eventId).revision();
            }
            var targetRevision = operation.orElseThrow().targetRevision();
            if (targetRevision <= 0L) {
                throw PersistenceConflictException(
                        "A legacy operation has no revision binding and cannot be replayed safely");
            }
            return@read targetRevision - 1L;
        });
    }

    private fun requireRevision(revision: Long): Unit {
        if (revision < 0L) {
            throw IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    private fun nextRevision(revision: Long): Long {
        try {
            return Math.addExact(revision, 1L);
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("event revision overflow", exception);
        }
    }

    private fun payloadFingerprint(snapshot: DefenseSessionSnapshot): String {
        val canonical = StringBuilder(512)
        canonical.append(snapshot.eventId()).append('|')
        canonical.append(snapshot.teamId()).append('|')
        canonical.append(snapshot.stageLevel()).append('|')
        canonical.append(snapshot.totalWaves()).append('|')
        canonical.append(snapshot.participantLimit()).append('|')
        canonical.append(snapshot.phase().name).append('|')
        canonical.append(snapshot.currentWave()).append('|')
        canonical.append(snapshot.participantsFrozen()).append('|')
        canonical.append(snapshot.pendingEnemies()).append('|')
        canonical.append(snapshot.aliveEnemies()).append('|')
        canonical.append(snapshot.coreState().maximumHitPoints).append('|')
        canonical.append(snapshot.coreState().currentHitPoints).append('|')
        canonical.append(snapshot.coreState().present).append('|')
        appendSortedUuids(canonical, snapshot.registeredParticipants());
        canonical.append('|');
        appendSortedUuids(canonical, snapshot.effectiveParticipants());

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().toByteArray(StandardCharsets.UTF_8)));
        } catch (exception: NoSuchAlgorithmException) {
            throw AssertionError("Every Java runtime must provide SHA-256", exception);
        }
    }

    private fun researchCrystalPayload(batchId: UUID, teamId: UUID, issuedQuantity: Int): String {
        return "research_crystal:v2:" + batchId + ":" + teamId + ":" + issuedQuantity;
    }

    private fun crystalRedemptionFingerprint(batchId: UUID, coreId: UUID, actorId: UUID, quantity: Int): String {
        return "CRYSTAL|" + batchId + "|" + coreId + "|" + actorId + "|" + quantity;
    }

    private fun crystalRedemptionFingerprint(batchId: UUID, coreId: UUID, actorId: UUID, itemTeamId: UUID, itemIssuedQuantity: Int, itemSegmentOffset: Int?, itemSegmentQuantity: Int?, quantity: Int): String {
        return "CRYSTAL|$batchId|$coreId|$actorId|$itemTeamId|$itemIssuedQuantity|" +
                "${itemSegmentOffset ?: "legacy"}|${itemSegmentQuantity ?: "legacy"}|$quantity"
    }

    private fun deterministicUuid(base: UUID, namespace: String, value: String): UUID {
        return UUID.nameUUIDFromBytes(
                (base.toString() + "|" + namespace + "|" + value).toByteArray(StandardCharsets.UTF_8));
    }

    private fun appendSortedUuids(target: StringBuilder, values: kotlin.collections.Set<UUID>) {
        values.asSequence()
                .map(UUID::toString)
                .sorted()
                .forEach { value -> target.append(value).append(',') }
    }

    private fun <T> read(action: String, work: Database.SqlWork<T>): T {
        return try {
            database.openConnection().use { connection ->
                work.execute(connection)
            }
        } catch (exception: SQLException) {
            throw failure(action, exception)
        }
    }

    private fun failure(action: String, exception: SQLException): PersistenceException {
        return PersistenceException("Could not " + action, exception);
    }

    private fun isConstraintViolation(exception: SQLException): Boolean {
        var current = exception;
        while (current != null) {
            val message = current.message
            if (current.errorCode == 19
                    || (message != null && message.lowercase(java.util.Locale.ROOT)
                            .contains("constraint"))) {
                return true;
            }
            current = current.nextException;
        }
        return false;
    }

    private fun uuid(value: String): UUID {
        return UUID.fromString(value);
    }

    private fun instant(value: String): Instant {
        return Instant.parse(value);
    }

    private fun nullableInstant(value: String?): Instant? {
        return if (value == null) null else Instant.parse(value);
    }

    private fun nullableInteger(resultSet: ResultSet, column: String): Int? {
        var value = resultSet.getInt(column);
        return if (resultSet.wasNull()) null else value;
    }

    private fun nullableInstantString(value: Instant?): String? {
        return if (value == null) null else value.toString()
    }

    private class ResearchCrystalSegment(
        private val segmentQuantityValue: Int,
        private val redeemedQuantityValue: Int,
    ) {
        fun segmentQuantity(): Int = segmentQuantityValue
        fun redeemedQuantity(): Int = redeemedQuantityValue
        fun remainingQuantity(): Int = segmentQuantityValue - redeemedQuantityValue
    }

    private class ParticipantSets(
        private val registeredValue: Set<UUID>,
        private val effectiveValue: Set<UUID>,
    ) {
        fun registered(): Set<UUID> = registeredValue
        fun effective(): Set<UUID> = effectiveValue
    }

    private class OperationRow(
        private val eventIdValue: UUID,
        private val kindValue: OperationKind,
        private val targetRevisionValue: Long,
        private val payloadFingerprintValue: String,
    ) {
        fun eventId(): UUID = eventIdValue
        fun kind(): OperationKind = kindValue
        fun targetRevision(): Long = targetRevisionValue
        fun payloadFingerprint(): String = payloadFingerprintValue
    }

    private class ManagementOperation(
        private val resourceTypeValue: String,
        private val resourceIdValue: UUID,
        private val operationKindValue: String,
        private val payloadFingerprintValue: String,
        private val paymentModeValue: PaymentMode,
    ) {
        fun resourceType(): String = resourceTypeValue
        fun resourceId(): UUID = resourceIdValue
        fun operationKind(): String = operationKindValue
        fun payloadFingerprint(): String = payloadFingerprintValue
        fun paymentMode(): PaymentMode = paymentModeValue
    }

    private class TeamProfileOperation(
        private val teamIdValue: UUID,
        private val actorIdValue: UUID,
        private val operationKindValue: String,
        private val payloadFingerprintValue: String,
    ) {
        fun teamId(): UUID = teamIdValue
        fun actorId(): UUID = actorIdValue
        fun operationKind(): String = operationKindValue
        fun payloadFingerprint(): String = payloadFingerprintValue
    }

    private class TeamInviteOperation(
        private val inviteIdValue: UUID,
        private val actorIdValue: UUID,
        private val operationKindValue: String,
        private val payloadFingerprintValue: String,
    ) {
        fun inviteId(): UUID = inviteIdValue
        fun actorId(): UUID = actorIdValue
        fun operationKind(): String = operationKindValue
        fun payloadFingerprint(): String = payloadFingerprintValue
    }

    private class BattleFundsOperation(
        private val eventIdValue: UUID,
        private val teamIdValue: UUID,
        private val actorIdValue: UUID?,
        private val operationKindValue: String,
        private val amountValue: Long,
        private val payloadFingerprintValue: String,
    ) {
        fun eventId(): UUID = eventIdValue
        fun teamId(): UUID = teamIdValue
        fun actorId(): UUID? = actorIdValue
        fun operationKind(): String = operationKindValue
        fun amount(): Long = amountValue
        fun payloadFingerprint(): String = payloadFingerprintValue
    }

    private class BattleBoostOperation(
        private val eventIdValue: UUID,
        private val teamIdValue: UUID,
        private val actorIdValue: UUID,
        private val towerIdValue: UUID,
        private val kindValue: BattleBoostKind,
        private val costValue: Long,
        private val boostMultiplierValue: Double,
        private val payloadFingerprintValue: String,
    ) {
        fun eventId(): UUID = eventIdValue
        fun teamId(): UUID = teamIdValue
        fun actorId(): UUID = actorIdValue
        fun towerId(): UUID = towerIdValue
        fun kind(): BattleBoostKind = kindValue
        fun cost(): Long = costValue
        fun boostMultiplier(): Double = boostMultiplierValue
        fun payloadFingerprint(): String = payloadFingerprintValue
    }

    private class TowerRepairOperation(
        private val eventIdValue: UUID,
        private val teamIdValue: UUID,
        private val actorIdValue: UUID,
        private val towerIdValue: UUID,
        private val repairedHitPointsValue: Long,
        private val costValue: Long,
        private val payloadFingerprintValue: String,
    ) {
        fun eventId(): UUID = eventIdValue
        fun teamId(): UUID = teamIdValue
        fun actorId(): UUID = actorIdValue
        fun towerId(): UUID = towerIdValue
        fun repairedHitPoints(): Long = repairedHitPointsValue
        fun cost(): Long = costValue
        fun payloadFingerprint(): String = payloadFingerprintValue
    }

    private class TowerDamageOperation(
        private val eventIdValue: UUID,
        private val teamIdValue: UUID,
        private val attackerLogicalEnemyIdValue: UUID,
        private val towerIdValue: UUID,
        private val damageValue: Long,
        private val remainingHitPointsValue: Long,
        private val destroyedValue: Boolean,
        private val payloadFingerprintValue: String,
    ) {
        fun eventId(): UUID = eventIdValue
        fun teamId(): UUID = teamIdValue
        fun attackerLogicalEnemyId(): UUID = attackerLogicalEnemyIdValue
        fun towerId(): UUID = towerIdValue
        fun damage(): Long = damageValue
        fun remainingHitPoints(): Long = remainingHitPointsValue
        fun destroyed(): Boolean = destroyedValue
        fun payloadFingerprint(): String = payloadFingerprintValue
    }

    private enum class OperationKind {
        TRANSITION,
        TERMINATE,
        RECOVER
    }
}
