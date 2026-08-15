package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.DefensePhase
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.ArrayList
import java.util.HashMap
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmStatic

/**
 * Database-owned virtual drops and reward queues.
 *
 * No method in this class returns an ItemStack. A Paper listener may display a tagged entity,
 * but a pickup first goes through the prepare/apply claim boundary. This keeps a physical entity
 * from becoming a usable item before the event has reached a normal terminal state.
 */
class EscrowRepository(database: Database) {
    private val database: Database = Objects.requireNonNull(database, "database")

    /** Creates one held drop before its visual entity is spawned. */
    fun prepare(
        drop: EscrowDrop,
        createOperationId: UUID,
        createdAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(drop, "drop")
        Objects.requireNonNull(createOperationId, "createOperationId")
        Objects.requireNonNull(createdAt, "createdAt")
        return try {
            database.inImmediateTransaction { connection ->
                val existing = loadDrop(connection, drop.dropId())
                if (existing.isPresent) {
                    val value = existing.orElseThrow()
                    if (!value.drop().equals(drop)
                        || !loadCreateOperationId(connection, drop.dropId())
                            .equals(createOperationId)
                    ) {
                        throw PersistenceConflictException(
                            "The escrow drop UUID is already assigned to another payload",
                        )
                    }
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                requireActiveEvent(connection, drop.eventId())
                connection.prepareStatement(
                    """
                    INSERT INTO event_drop_escrow(
                        drop_id, event_id, source_kind, source_id, item_id, item_payload,
                        quantity, status, display_entity_id, create_operation_id,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'HELD', ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, drop.dropId().toString())
                    statement.setString(2, drop.eventId().toString())
                    statement.setString(3, drop.sourceKind().name)
                    statement.setString(4, drop.sourceId().toString())
                    statement.setString(5, drop.itemId())
                    statement.setString(6, drop.itemPayload())
                    statement.setInt(7, drop.quantity())
                    statement.setString(
                        8,
                        if (drop.displayEntityId().isPresent) {
                            drop.displayEntityId().orElseThrow().toString()
                        } else {
                            null
                        },
                    )
                    statement.setString(9, createOperationId.toString())
                    statement.setString(10, createdAt.toString())
                    statement.setString(11, createdAt.toString())
                    statement.executeUpdate()
                }
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The escrow drop conflicts with persisted data",
                    exception,
                )
            }
            throw failure("prepare an escrow drop", exception)
        }
    }

    /** Associates or clears the visual entity which represents a held drop. */
    fun updateDisplayEntity(
        eventId: UUID,
        dropId: UUID,
        displayEntityId: Optional<UUID>,
        updatedAt: Instant,
    ) {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(dropId, "dropId")
        val display = Objects.requireNonNull(displayEntityId, "displayEntityId")
        Objects.requireNonNull(updatedAt, "updatedAt")
        try {
            database.inImmediateTransaction<Any?> { connection ->
                val drop = requireDrop(connection, dropId)
                if (!drop.drop().eventId().equals(eventId)) {
                    throw PersistenceConflictException(
                        "The escrow drop belongs to another defense event",
                    )
                }
                if (drop.status() != EscrowDropStatus.HELD && display.isPresent) {
                    throw IllegalStateException(
                        "A terminal escrow drop cannot receive a display entity",
                    )
                }
                connection.prepareStatement(
                    """
                    UPDATE event_drop_escrow
                    SET display_entity_id = ?, updated_at = ?
                    WHERE drop_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(
                        1,
                        if (display.isPresent) display.orElseThrow().toString() else null,
                    )
                    statement.setString(2, updatedAt.toString())
                    statement.setString(3, dropId.toString())
                    statement.executeUpdate()
                }
                null
            }
        } catch (exception: SQLException) {
            throw failure("update an escrow display entity", exception)
        }
    }

    /** Clears stale physical display references after the Paper entity has been removed. */
    fun clearDisplayEntity(eventId: UUID, dropId: UUID, clearedAt: Instant) {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(dropId, "dropId")
        Objects.requireNonNull(clearedAt, "clearedAt")
        try {
            database.inImmediateTransaction<Any?> { connection ->
                val drop = requireDrop(connection, dropId)
                if (!drop.drop().eventId().equals(eventId)) {
                    throw PersistenceConflictException(
                        "The escrow drop belongs to another defense event",
                    )
                }
                connection.prepareStatement(
                    """
                    UPDATE event_drop_escrow
                    SET display_entity_id = NULL, updated_at = ?
                    WHERE drop_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, clearedAt.toString())
                    statement.setString(2, dropId.toString())
                    statement.executeUpdate()
                }
                null
            }
        } catch (exception: SQLException) {
            throw failure("clear an escrow display entity", exception)
        }
    }

    /** Voids a drop prepared for a block action that could not be applied. */
    fun voidPreparedDrop(
        eventId: UUID,
        dropId: UUID,
        operationId: UUID,
        voidedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(dropId, "dropId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(voidedAt, "voidedAt")
        return try {
            database.inImmediateTransaction { connection ->
                requireActiveEvent(connection, eventId)
                val drop = requireDrop(connection, dropId)
                if (!drop.drop().eventId().equals(eventId)) {
                    throw PersistenceConflictException(
                        "The escrow drop belongs to another defense event",
                    )
                }
                val targetId = "$dropId|DISCARD"
                val fingerprint = sha256("$dropId|DISCARD")
                if (drop.status() == EscrowDropStatus.VOIDED) {
                    ensureResourceOperationApplied(
                        connection,
                        operationId,
                        eventId,
                        "DROP_VOID",
                        targetId,
                        fingerprint,
                        voidedAt,
                    )
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (drop.status() != EscrowDropStatus.HELD) {
                    throw PersistenceConflictException(
                        "Only a held escrow drop can be voided before termination",
                    )
                }
                ensureResourceOperationApplied(
                    connection,
                    operationId,
                    eventId,
                    "DROP_VOID",
                    targetId,
                    fingerprint,
                    voidedAt,
                )
                connection.prepareStatement(
                    """
                    UPDATE event_drop_escrow
                    SET status = 'VOIDED', display_entity_id = NULL, updated_at = ?
                    WHERE drop_id = ? AND status = 'HELD'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, voidedAt.toString())
                    statement.setString(2, dropId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The escrow drop was concurrently resolved",
                        )
                    }
                }
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("void an unapplied block drop", exception)
        }
    }

    /** Reserves a pickup operation without changing the claimed quantity. */
    fun prepareClaim(
        eventId: UUID,
        dropId: UUID,
        recipientId: UUID,
        quantity: Int,
        operationId: UUID,
        preparedAt: Instant,
    ): OperationOutcome {
        requireClaimArguments(eventId, dropId, recipientId, quantity, operationId, preparedAt)
        val targetId = claimTarget(dropId, recipientId)
        val fingerprint = claimFingerprint(dropId, recipientId, quantity)
        return try {
            database.inImmediateTransaction { connection ->
                requireActiveEvent(connection, eventId)
                requireRegisteredParticipant(connection, eventId, recipientId)
                val drop = requireDrop(connection, dropId)
                if (!drop.drop().eventId().equals(eventId)) {
                    throw PersistenceConflictException(
                        "The escrow drop belongs to another defense event",
                    )
                }
                if (drop.status() != EscrowDropStatus.HELD
                    || quantity > drop.remainingQuantity()
                ) {
                    throw PersistenceConflictException(
                        "The escrow drop has no remaining quantity for this claim",
                    )
                }
                val existing = loadResourceOperation(
                    connection,
                    eventId,
                    "DROP_CLAIM",
                    targetId,
                )
                if (existing.isPresent) {
                    val value = existing.orElseThrow()
                    requireMatchingResourceOperation(
                        value,
                        operationId,
                        eventId,
                        "DROP_CLAIM",
                        targetId,
                        fingerprint,
                    )
                    return@inImmediateTransaction if (
                        value.state == ResourceOperationState.APPLIED
                    ) {
                        OperationOutcome.ALREADY_APPLIED
                    } else {
                        OperationOutcome.APPLIED
                    }
                }
                val sameOperation = loadResourceOperation(connection, operationId)
                if (sameOperation.isPresent) {
                    requireMatchingResourceOperation(
                        sameOperation.orElseThrow(),
                        operationId,
                        eventId,
                        "DROP_CLAIM",
                        targetId,
                        fingerprint,
                    )
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                insertResourceOperation(
                    connection,
                    operationId,
                    eventId,
                    "DROP_CLAIM",
                    targetId,
                    fingerprint,
                    preparedAt,
                )
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The escrow claim conflicts with persisted data",
                    exception,
                )
            }
            throw failure("prepare an escrow claim", exception)
        }
    }

    /** Applies a prepared pickup and records it against the registered participant. */
    fun applyClaim(
        eventId: UUID,
        dropId: UUID,
        recipientId: UUID,
        quantity: Int,
        operationId: UUID,
        claimedAt: Instant,
    ): EscrowClaimResult {
        requireClaimArguments(eventId, dropId, recipientId, quantity, operationId, claimedAt)
        val targetId = claimTarget(dropId, recipientId)
        val fingerprint = claimFingerprint(dropId, recipientId, quantity)
        return try {
            database.inImmediateTransaction { connection ->
                val operation = requireResourceOperation(
                    connection,
                    operationId,
                    eventId,
                    "DROP_CLAIM",
                    targetId,
                    fingerprint,
                )
                if (operation.state == ResourceOperationState.APPLIED) {
                    val existingDrop = requireDrop(connection, dropId)
                    val feedback = loadResourcePickupFeedback(
                        connection,
                        eventId,
                        existingDrop.drop().itemId(),
                        recipientId,
                        quantity,
                    )
                    return@inImmediateTransaction EscrowClaimResult(
                        OperationOutcome.ALREADY_APPLIED,
                        quantity,
                        feedback,
                    )
                }
                requireActiveEvent(connection, eventId)
                requireRegisteredParticipant(connection, eventId, recipientId)
                val drop = requireDrop(connection, dropId)
                if (!drop.drop().eventId().equals(eventId)
                    || drop.status() != EscrowDropStatus.HELD
                ) {
                    throw PersistenceConflictException("The escrow drop is not claimable")
                }
                connection.prepareStatement(
                    """
                    UPDATE event_drop_escrow
                    SET claimed_quantity = claimed_quantity + ?, updated_at = ?
                    WHERE drop_id = ? AND status = 'HELD'
                      AND claimed_quantity + ? <= quantity
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, quantity)
                    statement.setString(2, claimedAt.toString())
                    statement.setString(3, dropId.toString())
                    statement.setInt(4, quantity)
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The escrow drop was claimed by another operation",
                        )
                    }
                }
                connection.prepareStatement(
                    """
                    INSERT INTO event_drop_claims(
                        event_id, drop_id, recipient_id, quantity, operation_id, claimed_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(event_id, drop_id, recipient_id) DO UPDATE SET
                        quantity = event_drop_claims.quantity + excluded.quantity,
                        operation_id = excluded.operation_id,
                        claimed_at = excluded.claimed_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, eventId.toString())
                    statement.setString(2, dropId.toString())
                    statement.setString(3, recipientId.toString())
                    statement.setInt(4, quantity)
                    statement.setString(5, operationId.toString())
                    statement.setString(6, claimedAt.toString())
                    statement.executeUpdate()
                }
                markResourceOperationApplied(connection, operationId, claimedAt)
                val feedback = loadResourcePickupFeedback(
                    connection,
                    eventId,
                    drop.drop().itemId(),
                    recipientId,
                    quantity,
                )
                EscrowClaimResult(OperationOutcome.APPLIED, quantity, feedback)
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The escrow claim conflicts with persisted participant data",
                    exception,
                )
            }
            throw failure("apply an escrow claim", exception)
        }
    }

    /** Convenience method for callers which do not need to interleave a Paper entity action. */
    fun claim(
        eventId: UUID,
        dropId: UUID,
        recipientId: UUID,
        quantity: Int,
        operationId: UUID,
        claimedAt: Instant,
    ): EscrowClaimResult {
        val prepared = prepareClaim(
            eventId,
            dropId,
            recipientId,
            quantity,
            operationId,
            claimedAt,
        )
        if (prepared == OperationOutcome.ALREADY_APPLIED) {
            return EscrowClaimResult(prepared, quantity)
        }
        return applyClaim(eventId, dropId, recipientId, quantity, operationId, claimedAt)
    }

    /** Settles all held drops for a normal terminal event and issues durable reward queue rows. */
    fun settleEvent(
        eventId: UUID,
        terminalOperationId: UUID,
        terminalPhase: DefensePhase,
        settledAt: Instant,
    ): OperationOutcome = settleEvent(
        eventId,
        terminalOperationId,
        terminalPhase,
        settledAt,
        defaultTeamQueueRetention(),
    )

    /** Settles an event with an explicit TEAM queue retention duration. */
    fun settleEvent(
        eventId: UUID,
        terminalOperationId: UUID,
        terminalPhase: DefensePhase,
        settledAt: Instant,
        teamQueueRetention: Duration,
    ): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(terminalOperationId, "terminalOperationId")
        Objects.requireNonNull(terminalPhase, "terminalPhase")
        Objects.requireNonNull(settledAt, "settledAt")
        requirePositiveDuration(teamQueueRetention)
        if (terminalPhase != DefensePhase.VICTORY
            && terminalPhase != DefensePhase.DEFEAT
            && terminalPhase != DefensePhase.ABORTED
        ) {
            throw IllegalArgumentException("settleEvent requires a normal terminal phase")
        }
        return try {
            database.inImmediateTransaction { connection ->
                requireTerminalEvent(connection, eventId, terminalPhase)
                if (hasOnlySettledDrops(connection, eventId)) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                settleForTerminal(
                    connection,
                    eventId,
                    terminalOperationId,
                    terminalPhase,
                    settledAt,
                    teamQueueRetention,
                )
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The escrow settlement conflicts with persisted queue data",
                    exception,
                )
            }
            throw failure("settle event drops", exception)
        }
    }

    /** Voids held drops during technical recovery; claimed quantities remain audit data only. */
    fun voidEvent(
        eventId: UUID,
        recoveryOperationId: UUID,
        voidedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(recoveryOperationId, "recoveryOperationId")
        Objects.requireNonNull(voidedAt, "voidedAt")
        return try {
            database.inImmediateTransaction { connection ->
                requireTerminalEvent(connection, eventId, DefensePhase.RECOVERY)
                if (hasOnlyVoidedDrops(connection, eventId)) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                voidForRecovery(connection, eventId, recoveryOperationId, voidedAt)
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("void event drops", exception)
        }
    }

    /** Loads escrow in stable drop UUID order. */
    fun loadDrops(eventId: UUID): List<StoredEscrowDrop> {
        Objects.requireNonNull(eventId, "eventId")
        return read("load escrow drops") { connection ->
            val drops = ArrayList<StoredEscrowDrop>()
            connection.prepareStatement(
                """
                SELECT drop_id, event_id, source_kind, source_id, item_id, item_payload,
                       quantity, claimed_quantity, status, display_entity_id,
                       create_operation_id, created_at, updated_at
                FROM event_drop_escrow WHERE event_id = ? ORDER BY drop_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        drops.add(dropFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(drops)
        }
    }

    /** Loads all pickup claims for one event. */
    fun loadClaims(eventId: UUID): List<EscrowClaim> {
        Objects.requireNonNull(eventId, "eventId")
        return read("load escrow claims") { connection ->
            val claims = ArrayList<EscrowClaim>()
            connection.prepareStatement(
                """
                SELECT event_id, drop_id, recipient_id, quantity, operation_id, claimed_at
                FROM event_drop_claims WHERE event_id = ?
                ORDER BY drop_id, recipient_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        claims.add(
                            EscrowClaim(
                                uuid(resultSet.getString("event_id")),
                                uuid(resultSet.getString("drop_id")),
                                uuid(resultSet.getString("recipient_id")),
                                resultSet.getInt("quantity"),
                                uuid(resultSet.getString("operation_id")),
                                instant(resultSet.getString("claimed_at")),
                            ),
                        )
                    }
                }
            }
            java.util.List.copyOf(claims)
        }
    }

    /** Loads all pending and delivered reward queue rows for one event. */
    fun loadRewardQueue(eventId: UUID): List<RewardQueueEntry> {
        Objects.requireNonNull(eventId, "eventId")
        return read("load the reward queue") { connection ->
            val entries = ArrayList<RewardQueueEntry>()
            connection.prepareStatement(
                """
                SELECT queue_id, event_id, scope, recipient_id, item_id, item_payload,
                       quantity, source_drop_id, status, issued_operation_id,
                       created_at, updated_at, team_claim_deadline
                FROM event_reward_queue WHERE event_id = ? ORDER BY queue_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        entries.add(rewardQueueFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(entries)
        }
    }

    /** Loads pending rows which a player is currently allowed to receive. */
    fun loadPendingRewardQueueForPlayer(playerId: UUID): List<RewardQueueEntry> =
        loadPendingRewardQueueForPlayer(playerId, Instant.now())

    /**
     * Loads pending rows which a player is allowed to receive at a supplied point in time.
     * TEAM rows remain claimable by registered event participants indefinitely. Once a row's
     * retention deadline has passed, the current team owner is also eligible. Legacy rows without
     * a deadline deliberately remain participant-only.
     */
    fun loadPendingRewardQueueForPlayer(
        playerId: UUID,
        at: Instant,
    ): List<RewardQueueEntry> {
        Objects.requireNonNull(playerId, "playerId")
        Objects.requireNonNull(at, "at")
        return read("load pending rewards for a player") { connection ->
            val entries = HashMap<UUID, RewardQueueEntry>()
            connection.prepareStatement(
                """
                SELECT q.queue_id, q.event_id, q.scope, q.recipient_id, q.item_id,
                       q.item_payload, q.quantity, q.source_drop_id, q.status,
                       q.issued_operation_id, q.created_at, q.updated_at,
                       q.team_claim_deadline
                FROM event_reward_queue q
                JOIN defense_events e ON e.event_id = q.event_id
                WHERE q.status = 'PENDING'
                  AND (
                      (q.scope = 'PLAYER' AND q.recipient_id = ?)
                      OR (
                          q.scope = 'TEAM'
                          AND q.recipient_id = e.team_id
                          AND EXISTS (
                              SELECT 1
                              FROM event_participants p
                              JOIN team_members m
                                ON m.team_id = e.team_id AND m.player_id = p.player_id
                              WHERE p.event_id = q.event_id
                                AND p.player_id = ?
                                AND p.registered = 1
                          )
                      )
                  )
                ORDER BY q.created_at, q.queue_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, playerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        val entry = rewardQueueFromRow(resultSet)
                        entries[entry.queueId] = entry
                    }
                }
            }
            connection.prepareStatement(
                """
                SELECT q.queue_id, q.event_id, q.scope, q.recipient_id, q.item_id,
                       q.item_payload, q.quantity, q.source_drop_id, q.status,
                       q.issued_operation_id, q.created_at, q.updated_at,
                       q.team_claim_deadline
                FROM event_reward_queue q
                JOIN defense_events e ON e.event_id = q.event_id
                JOIN teams t ON t.team_id = e.team_id
                WHERE q.status = 'PENDING'
                  AND q.scope = 'TEAM'
                  AND q.recipient_id = e.team_id
                  AND t.owner_player_id = ?
                  AND q.team_claim_deadline IS NOT NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        val entry = rewardQueueFromRow(resultSet)
                        if (entry.teamClaimDeadline.isPresent
                            && !at.isBefore(entry.teamClaimDeadline.orElseThrow())
                        ) {
                            entries[entry.queueId] = entry
                        }
                    }
                }
            }
            val ordered = ArrayList(entries.values)
            ordered.sortWith(
                compareBy<RewardQueueEntry> { it.createdAt }.thenBy { it.queueId },
            )
            java.util.List.copyOf(ordered)
        }
    }

    /** Loads one queue row for reconciliation after a Paper-side delivery retry. */
    fun findRewardQueue(queueId: UUID): Optional<RewardQueueEntry> {
        Objects.requireNonNull(queueId, "queueId")
        return read("load a reward queue row") { connection ->
            loadRewardQueue(connection, queueId)
        }
    }

    /** Commits one physical inventory delivery reservation after Paper has accepted the item stack. */
    fun prepareRewardDelivery(
        queueId: UUID,
        playerId: UUID,
        operationId: UUID,
        preparedAt: Instant,
    ): RewardDeliveryOutcome {
        Objects.requireNonNull(queueId, "queueId")
        Objects.requireNonNull(playerId, "playerId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(preparedAt, "preparedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val entry = loadRewardQueue(connection, queueId).orElseThrow {
                    PersistenceConflictException("Unknown reward queue row $queueId")
                }
                requireAuthorizedRewardRecipient(connection, entry, playerId, preparedAt)
                if (entry.status == RewardQueueStatus.DELIVERED) {
                    return@inImmediateTransaction RewardDeliveryOutcome.ALREADY_DELIVERED
                }
                if (entry.status == RewardQueueStatus.VOIDED) {
                    return@inImmediateTransaction RewardDeliveryOutcome.VOIDED
                }

                val fingerprint = rewardDeliveryFingerprint(entry, playerId)
                val existing = loadRewardDeliveryOperationForQueue(connection, queueId)
                if (existing.isPresent) {
                    val operation = existing.orElseThrow()
                    if (!operation.playerId.equals(playerId)) {
                        return@inImmediateTransaction RewardDeliveryOutcome.HELD_BY_OTHER
                    }
                    requireMatchingRewardDelivery(
                        operation,
                        operation.operationId,
                        entry,
                        playerId,
                        fingerprint,
                    )
                    if (!operation.operationId.equals(operationId)) {
                        throw PersistenceConflictException(
                            "The reward queue is already reserved by this player"
                                + " with another operation UUID",
                        )
                    }
                    return@inImmediateTransaction if (
                        operation.state == RewardDeliveryState.APPLIED
                    ) {
                        RewardDeliveryOutcome.ALREADY_DELIVERED
                    } else {
                        RewardDeliveryOutcome.ALREADY_ACQUIRED
                    }
                }
                if (loadRewardDeliveryOperation(connection, operationId).isPresent) {
                    throw PersistenceConflictException(
                        "The reward delivery operation UUID is already in use",
                    )
                }

                connection.prepareStatement(
                    """
                    INSERT INTO event_reward_delivery_operations(
                        operation_id, queue_id, event_id, player_id, quantity,
                        payload_fingerprint, state, prepared_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, operationId.toString())
                    statement.setString(2, queueId.toString())
                    statement.setString(3, entry.eventId.toString())
                    statement.setString(4, playerId.toString())
                    statement.setInt(5, entry.quantity)
                    statement.setString(6, fingerprint)
                    statement.setString(7, preparedAt.toString())
                    statement.executeUpdate()
                }
                RewardDeliveryOutcome.ACQUIRED
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The reward delivery reservation conflicts with persisted data",
                    exception,
                )
            }
            throw failure("reserve a reward delivery", exception)
        }
    }

    /** Commits one physical inventory delivery after Paper has accepted the item stack. */
    fun markRewardDelivered(
        queueId: UUID,
        playerId: UUID,
        operationId: UUID,
        deliveredAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(queueId, "queueId")
        Objects.requireNonNull(playerId, "playerId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(deliveredAt, "deliveredAt")
        return try {
            database.inImmediateTransaction { connection ->
                val entry = loadRewardQueue(connection, queueId).orElseThrow {
                    PersistenceConflictException("Unknown reward queue row $queueId")
                }
                requireAuthorizedRewardRecipient(connection, entry, playerId, deliveredAt)
                if (entry.status == RewardQueueStatus.DELIVERED) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (entry.status == RewardQueueStatus.VOIDED) {
                    throw PersistenceConflictException(
                        "A voided reward queue row cannot be delivered",
                    )
                }

                val fingerprint = rewardDeliveryFingerprint(entry, playerId)
                val operation = loadRewardDeliveryOperationForQueue(
                    connection,
                    queueId,
                ).orElseThrow {
                    PersistenceConflictException("The reward delivery was not reserved first")
                }
                requireMatchingRewardDelivery(
                    operation,
                    operationId,
                    entry,
                    playerId,
                    fingerprint,
                )
                if (operation.state == RewardDeliveryState.APPLIED) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                connection.prepareStatement(
                    """
                    UPDATE event_reward_delivery_operations
                    SET state = 'APPLIED', applied_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, deliveredAt.toString())
                    statement.setString(2, operationId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The reward delivery reservation was concurrently resolved",
                        )
                    }
                }
                connection.prepareStatement(
                    """
                    UPDATE event_reward_queue
                    SET status = 'DELIVERED', updated_at = ?
                    WHERE queue_id = ? AND status = 'PENDING'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, deliveredAt.toString())
                    statement.setString(2, queueId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The reward queue row was concurrently resolved",
                        )
                    }
                }
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The reward delivery operation conflicts with persisted data",
                    exception,
                )
            }
            throw failure("mark a reward as delivered", exception)
        }
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

    companion object {
        private fun defaultTeamQueueRetention(): Duration = Duration.ofDays(7L)

        private fun loadResourcePickupFeedback(
            connection: Connection,
            eventId: UUID,
            itemId: String,
            recipientId: UUID,
            quantity: Int,
        ): Optional<ResourcePickupFeedback> {
            val resourceType = ResourceType.fromItemId(itemId)
            if (resourceType.isEmpty) {
                return Optional.empty()
            }
            return Optional.of(
                ResourceRepository.loadPickupFeedback(
                    connection,
                    eventId,
                    recipientId,
                    resourceType.orElseThrow(),
                    quantity,
                ),
            )
        }

        /** Package-private hook used by the event terminal transaction. */
        @JvmStatic
        @Throws(SQLException::class)
        fun settleForTerminal(
            connection: Connection,
            eventId: UUID,
            terminalOperationId: UUID,
            terminalPhase: DefensePhase,
            settledAt: Instant,
        ) {
            settleForTerminal(
                connection,
                eventId,
                terminalOperationId,
                terminalPhase,
                settledAt,
                defaultTeamQueueRetention(),
            )
        }

        @JvmStatic
        @Throws(SQLException::class)
        fun settleForTerminal(
            connection: Connection,
            eventId: UUID,
            terminalOperationId: UUID,
            terminalPhase: DefensePhase,
            settledAt: Instant,
            teamQueueRetention: Duration,
        ) {
            requirePositiveDuration(teamQueueRetention)
            val teamId = loadTeamId(connection, eventId)
            val teamClaimDeadline = settledAt.plus(teamQueueRetention)
            ResourceRepository.settleForTerminal(
                connection,
                eventId,
                terminalOperationId,
                terminalPhase,
                settledAt,
            )
            val drops = loadDrops(connection, eventId)
            for (drop in drops) {
                if (drop.status() != EscrowDropStatus.HELD) {
                    continue
                }
                val settleOperationId = deterministicOperation(
                    terminalOperationId,
                    "DROP_SETTLE",
                    drop.drop().dropId().toString(),
                )
                ensureResourceOperationApplied(
                    connection,
                    settleOperationId,
                    eventId,
                    "DROP_SETTLE",
                    drop.drop().dropId().toString(),
                    sha256(drop.drop().dropId().toString() + "|" + terminalPhase),
                    settledAt,
                )

                if (ResourceRepository.isWalletResource(drop.drop().itemId())) {
                    markSettled(connection, drop, settledAt)
                    continue
                }

                for (claim in loadClaims(connection, eventId, drop.drop().dropId())) {
                    val issueOperationId = deterministicOperation(
                        terminalOperationId,
                        "PLAYER",
                        drop.drop().dropId().toString() + "|" + claim.recipientId(),
                    )
                    issueReward(
                        connection,
                        issueOperationId,
                        eventId,
                        RewardQueueScope.PLAYER,
                        claim.recipientId(),
                        drop,
                        claim.quantity(),
                        Optional.empty(),
                        settledAt,
                    )
                }
                val remaining = drop.remainingQuantity()
                if (terminalPhase == DefensePhase.VICTORY && remaining > 0) {
                    val issueOperationId = deterministicOperation(
                        terminalOperationId,
                        "TEAM",
                        drop.drop().dropId().toString(),
                    )
                    issueReward(
                        connection,
                        issueOperationId,
                        eventId,
                        RewardQueueScope.TEAM,
                        teamId,
                        drop,
                        remaining,
                        Optional.of(teamClaimDeadline),
                        settledAt,
                    )
                }
                connection.prepareStatement(
                    """
                    UPDATE event_drop_escrow
                    SET status = 'SETTLED', display_entity_id = NULL, updated_at = ?
                    WHERE drop_id = ? AND status = 'HELD'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, settledAt.toString())
                    statement.setString(2, drop.drop().dropId().toString())
                    statement.executeUpdate()
                }
            }
        }

        /** Package-private hook used by technical event recovery. */
        @JvmStatic
        @Throws(SQLException::class)
        fun voidForRecovery(
            connection: Connection,
            eventId: UUID,
            recoveryOperationId: UUID,
            voidedAt: Instant,
        ) {
            ResourceRepository.settleForRecovery(
                connection,
                eventId,
                recoveryOperationId,
                voidedAt,
            )
            for (drop in loadDrops(connection, eventId)) {
                if (drop.status() != EscrowDropStatus.HELD) {
                    continue
                }
                val voidOperationId = deterministicOperation(
                    recoveryOperationId,
                    "DROP_VOID",
                    drop.drop().dropId().toString(),
                )
                ensureResourceOperationApplied(
                    connection,
                    voidOperationId,
                    eventId,
                    "DROP_VOID",
                    drop.drop().dropId().toString(),
                    sha256(drop.drop().dropId().toString() + "|VOID"),
                    voidedAt,
                )
                connection.prepareStatement(
                    """
                    UPDATE event_drop_escrow
                    SET status = 'VOIDED', display_entity_id = NULL, updated_at = ?
                    WHERE drop_id = ? AND status = 'HELD'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, voidedAt.toString())
                    statement.setString(2, drop.drop().dropId().toString())
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                """
                UPDATE event_reward_queue SET status = 'VOIDED', updated_at = ?
                WHERE event_id = ? AND status = 'PENDING'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, voidedAt.toString())
                statement.setString(2, eventId.toString())
                statement.executeUpdate()
            }
        }

        private fun markSettled(
            connection: Connection,
            drop: StoredEscrowDrop,
            settledAt: Instant,
        ) {
            connection.prepareStatement(
                """
                UPDATE event_drop_escrow
                SET status = 'SETTLED', display_entity_id = NULL, updated_at = ?
                WHERE drop_id = ? AND status = 'HELD'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, settledAt.toString())
                statement.setString(2, drop.drop().dropId().toString())
                statement.executeUpdate()
            }
        }

        private fun issueReward(
            connection: Connection,
            issueOperationId: UUID,
            eventId: UUID,
            scope: RewardQueueScope,
            recipientId: UUID,
            drop: StoredEscrowDrop,
            quantity: Int,
            teamClaimDeadline: Optional<Instant>,
            issuedAt: Instant,
        ) {
            if (quantity <= 0) {
                return
            }
            val targetId = drop.drop().dropId().toString() + "|" + scope + "|" + recipientId
            val fingerprint = sha256(
                drop.drop().dropId().toString() + "|" + scope + "|"
                    + recipientId + "|" + drop.drop().itemId() + "|" + quantity,
            )
            ensureResourceOperationApplied(
                connection,
                issueOperationId,
                eventId,
                "REWARD_ISSUE",
                targetId,
                fingerprint,
                issuedAt,
            )
            connection.prepareStatement(
                """
                INSERT INTO event_reward_queue(
                    queue_id, event_id, scope, recipient_id, item_id, item_payload,
                    quantity, source_drop_id, status, issued_operation_id, created_at, updated_at,
                    team_claim_deadline
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                ON CONFLICT(event_id, source_drop_id, scope, recipient_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(
                    1,
                    deterministicOperation(issueOperationId, "QUEUE", targetId).toString(),
                )
                statement.setString(2, eventId.toString())
                statement.setString(3, scope.name)
                statement.setString(4, recipientId.toString())
                statement.setString(5, drop.drop().itemId())
                statement.setString(6, drop.drop().itemPayload())
                statement.setInt(7, quantity)
                statement.setString(8, drop.drop().dropId().toString())
                statement.setString(9, issueOperationId.toString())
                statement.setString(10, issuedAt.toString())
                statement.setString(11, issuedAt.toString())
                statement.setString(
                    12,
                    if (teamClaimDeadline.isPresent) {
                        teamClaimDeadline.orElseThrow().toString()
                    } else {
                        null
                    },
                )
                statement.executeUpdate()
            }
        }

        private fun loadDrops(
            connection: Connection,
            eventId: UUID,
        ): List<StoredEscrowDrop> {
            val drops = ArrayList<StoredEscrowDrop>()
            connection.prepareStatement(
                """
                SELECT drop_id, event_id, source_kind, source_id, item_id, item_payload,
                       quantity, claimed_quantity, status, display_entity_id,
                       create_operation_id, created_at, updated_at
                FROM event_drop_escrow WHERE event_id = ? ORDER BY drop_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        drops.add(dropFromRow(resultSet))
                    }
                }
            }
            return drops
        }

        private fun loadClaims(
            connection: Connection,
            eventId: UUID,
            dropId: UUID,
        ): List<EscrowClaim> {
            val claims = ArrayList<EscrowClaim>()
            connection.prepareStatement(
                """
                SELECT event_id, drop_id, recipient_id, quantity, operation_id, claimed_at
                FROM event_drop_claims WHERE event_id = ? AND drop_id = ?
                ORDER BY recipient_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, dropId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        claims.add(
                            EscrowClaim(
                                uuid(resultSet.getString("event_id")),
                                uuid(resultSet.getString("drop_id")),
                                uuid(resultSet.getString("recipient_id")),
                                resultSet.getInt("quantity"),
                                uuid(resultSet.getString("operation_id")),
                                instant(resultSet.getString("claimed_at")),
                            ),
                        )
                    }
                }
            }
            return claims
        }

        private fun loadRewardQueue(
            connection: Connection,
            queueId: UUID,
        ): Optional<RewardQueueEntry> {
            connection.prepareStatement(
                """
                SELECT queue_id, event_id, scope, recipient_id, item_id, item_payload,
                       quantity, source_drop_id, status, issued_operation_id,
                       created_at, updated_at, team_claim_deadline
                FROM event_reward_queue WHERE queue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, queueId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(rewardQueueFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun rewardQueueFromRow(resultSet: ResultSet): RewardQueueEntry =
            RewardQueueEntry(
                uuid(resultSet.getString("queue_id")),
                uuid(resultSet.getString("event_id")),
                RewardQueueScope.valueOf(resultSet.getString("scope")),
                uuid(resultSet.getString("recipient_id")),
                resultSet.getString("item_id"),
                resultSet.getString("item_payload"),
                resultSet.getInt("quantity"),
                uuid(resultSet.getString("source_drop_id")),
                RewardQueueStatus.valueOf(resultSet.getString("status")),
                uuid(resultSet.getString("issued_operation_id")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")),
                if (resultSet.getString("team_claim_deadline") == null) {
                    Optional.empty()
                } else {
                    Optional.of(instant(resultSet.getString("team_claim_deadline")))
                },
            )

        private fun requireAuthorizedRewardRecipient(
            connection: Connection,
            entry: RewardQueueEntry,
            playerId: UUID,
            at: Instant,
        ) {
            if (entry.scope == RewardQueueScope.PLAYER) {
                if (!entry.recipientId.equals(playerId)) {
                    throw PersistenceConflictException(
                        "Only the personal reward recipient may receive this queue row",
                    )
                }
                return
            }
            if (entry.teamClaimDeadline.isPresent
                && !at.isBefore(entry.teamClaimDeadline.orElseThrow())
            ) {
                connection.prepareStatement(
                    """
                    SELECT 1
                    FROM defense_events e
                    JOIN teams t ON t.team_id = e.team_id
                    WHERE e.event_id = ?
                      AND e.team_id = ?
                      AND t.owner_player_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, entry.eventId.toString())
                    statement.setString(2, entry.recipientId.toString())
                    statement.setString(3, playerId.toString())
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            return
                        }
                    }
                }
                throw PersistenceConflictException(
                    "Only the current owner of the reward team may receive this expired queue row",
                )
            }
            connection.prepareStatement(
                """
                SELECT 1
                FROM defense_events e
                JOIN team_members m ON m.team_id = e.team_id AND m.player_id = ?
                JOIN event_participants p
                  ON p.event_id = e.event_id AND p.player_id = m.player_id
                WHERE e.event_id = ?
                  AND e.team_id = ?
                  AND p.registered = 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, entry.eventId.toString())
                statement.setString(3, entry.recipientId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException(
                            "Only a registered member of the reward team may receive this queue row",
                        )
                    }
                }
            }
        }

        private fun requirePositiveDuration(duration: Duration) {
            Objects.requireNonNull(duration, "teamQueueRetention")
            if (duration.isZero || duration.isNegative) {
                throw IllegalArgumentException("teamQueueRetention must be positive")
            }
        }

        private fun rewardDeliveryFingerprint(
            entry: RewardQueueEntry,
            playerId: UUID,
        ): String = sha256(
            entry.queueId.toString() + "|" + entry.eventId + "|" + playerId + "|"
                + entry.itemId + "|" + entry.quantity,
        )

        private fun loadRewardDeliveryOperation(
            connection: Connection,
            operationId: UUID,
        ): Optional<RewardDeliveryOperation> {
            connection.prepareStatement(
                """
                SELECT operation_id, queue_id, event_id, player_id, quantity,
                       payload_fingerprint, state, prepared_at, applied_at
                FROM event_reward_delivery_operations WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(rewardDeliveryOperationFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadRewardDeliveryOperationForQueue(
            connection: Connection,
            queueId: UUID,
        ): Optional<RewardDeliveryOperation> {
            connection.prepareStatement(
                """
                SELECT operation_id, queue_id, event_id, player_id, quantity,
                       payload_fingerprint, state, prepared_at, applied_at
                FROM event_reward_delivery_operations WHERE queue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, queueId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(rewardDeliveryOperationFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun rewardDeliveryOperationFromRow(
            resultSet: ResultSet,
        ): RewardDeliveryOperation = RewardDeliveryOperation(
            uuid(resultSet.getString("operation_id")),
            uuid(resultSet.getString("queue_id")),
            uuid(resultSet.getString("event_id")),
            uuid(resultSet.getString("player_id")),
            resultSet.getInt("quantity"),
            resultSet.getString("payload_fingerprint"),
            RewardDeliveryState.valueOf(resultSet.getString("state")),
            instant(resultSet.getString("prepared_at")),
            if (resultSet.getString("applied_at") == null) {
                Optional.empty()
            } else {
                Optional.of(instant(resultSet.getString("applied_at")))
            },
        )

        private fun requireMatchingRewardDelivery(
            operation: RewardDeliveryOperation,
            operationId: UUID,
            entry: RewardQueueEntry,
            playerId: UUID,
            fingerprint: String,
        ) {
            if (!operation.operationId.equals(operationId)
                || !operation.queueId.equals(entry.queueId)
                || !operation.eventId.equals(entry.eventId)
                || !operation.playerId.equals(playerId)
                || operation.quantity != entry.quantity
                || !operation.payloadFingerprint.equals(fingerprint)
            ) {
                throw PersistenceConflictException(
                    "The reward delivery operation UUID is already assigned to another payload",
                )
            }
        }

        private fun dropFromRow(resultSet: ResultSet): StoredEscrowDrop {
            val displayEntity = resultSet.getString("display_entity_id")
            val drop = EscrowDrop(
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("drop_id")),
                DropSourceKind.valueOf(resultSet.getString("source_kind")),
                uuid(resultSet.getString("source_id")),
                resultSet.getString("item_id"),
                resultSet.getString("item_payload"),
                resultSet.getInt("quantity"),
                if (displayEntity == null) {
                    Optional.empty()
                } else {
                    Optional.of(uuid(displayEntity))
                },
            )
            return StoredEscrowDrop(
                drop,
                resultSet.getInt("claimed_quantity"),
                EscrowDropStatus.valueOf(resultSet.getString("status")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")),
            )
        }

        private fun loadCreateOperationId(connection: Connection, dropId: UUID): UUID {
            connection.prepareStatement(
                "SELECT create_operation_id FROM event_drop_escrow WHERE drop_id = ?",
            ).use { statement ->
                statement.setString(1, dropId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException("Unknown escrow drop $dropId")
                    }
                    return uuid(resultSet.getString("create_operation_id"))
                }
            }
        }

        private fun loadDrop(
            connection: Connection,
            dropId: UUID,
        ): Optional<StoredEscrowDrop> {
            connection.prepareStatement(
                """
                SELECT drop_id, event_id, source_kind, source_id, item_id, item_payload,
                       quantity, claimed_quantity, status, display_entity_id,
                       create_operation_id, created_at, updated_at
                FROM event_drop_escrow WHERE drop_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, dropId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(dropFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun requireDrop(connection: Connection, dropId: UUID): StoredEscrowDrop =
            loadDrop(connection, dropId).orElseThrow {
                PersistenceConflictException("Unknown escrow drop $dropId")
            }

        private fun requireClaimArguments(
            eventId: UUID,
            dropId: UUID,
            recipientId: UUID,
            quantity: Int,
            operationId: UUID,
            timestamp: Instant,
        ) {
            Objects.requireNonNull(eventId, "eventId")
            Objects.requireNonNull(dropId, "dropId")
            Objects.requireNonNull(recipientId, "recipientId")
            Objects.requireNonNull(operationId, "operationId")
            Objects.requireNonNull(timestamp, "timestamp")
            if (quantity <= 0) {
                throw IllegalArgumentException("quantity must be positive")
            }
        }

        private fun requireActiveEvent(connection: Connection, eventId: UUID) {
            connection.prepareStatement(
                "SELECT state FROM defense_events WHERE event_id = ?",
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException("Unknown defense event $eventId")
                    }
                    if (isTerminal(resultSet.getString("state"))) {
                        throw IllegalStateException(
                            "Cannot mutate escrow for a terminal defense event",
                        )
                    }
                }
            }
        }

        private fun requireTerminalEvent(
            connection: Connection,
            eventId: UUID,
            expectedPhase: DefensePhase,
        ) {
            connection.prepareStatement(
                "SELECT state FROM defense_events WHERE event_id = ?",
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException("Unknown defense event $eventId")
                    }
                    if (expectedPhase.name != resultSet.getString("state")) {
                        throw PersistenceConflictException(
                            "The escrow terminal phase does not match the persisted event state",
                        )
                    }
                }
            }
        }

        private fun requireRegisteredParticipant(
            connection: Connection,
            eventId: UUID,
            recipientId: UUID,
        ) {
            connection.prepareStatement(
                """
                SELECT 1 FROM event_participants
                WHERE event_id = ? AND player_id = ? AND registered = 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, recipientId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException(
                            "Only a registered participant may claim an event drop",
                        )
                    }
                }
            }
        }

        private fun loadTeamId(connection: Connection, eventId: UUID): UUID {
            connection.prepareStatement(
                "SELECT team_id FROM defense_events WHERE event_id = ?",
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException("Unknown defense event $eventId")
                    }
                    return uuid(resultSet.getString("team_id"))
                }
            }
        }

        private fun hasOnlySettledDrops(connection: Connection, eventId: UUID): Boolean =
            hasNoDropsOtherThan(connection, eventId, "SETTLED")

        private fun hasOnlyVoidedDrops(connection: Connection, eventId: UUID): Boolean =
            hasNoDropsOtherThan(connection, eventId, "VOIDED")

        private fun hasNoDropsOtherThan(
            connection: Connection,
            eventId: UUID,
            status: String,
        ): Boolean {
            connection.prepareStatement(
                """
                SELECT 1 FROM event_drop_escrow
                WHERE event_id = ? AND status <> ? LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, status)
                statement.executeQuery().use { resultSet ->
                    return !resultSet.next()
                }
            }
        }

        private fun ensureResourceOperationApplied(
            connection: Connection,
            operationId: UUID,
            eventId: UUID,
            kind: String,
            targetId: String,
            fingerprint: String,
            timestamp: Instant,
        ) {
            val existing = loadResourceOperation(connection, eventId, kind, targetId)
            if (existing.isPresent) {
                requireMatchingResourceOperation(
                    existing.orElseThrow(),
                    operationId,
                    eventId,
                    kind,
                    targetId,
                    fingerprint,
                )
                if (existing.orElseThrow().state == ResourceOperationState.PREPARED) {
                    markResourceOperationApplied(connection, operationId, timestamp)
                }
                return
            }
            val sameOperation = loadResourceOperation(connection, operationId)
            if (sameOperation.isPresent) {
                requireMatchingResourceOperation(
                    sameOperation.orElseThrow(),
                    operationId,
                    eventId,
                    kind,
                    targetId,
                    fingerprint,
                )
                if (sameOperation.orElseThrow().state == ResourceOperationState.PREPARED) {
                    markResourceOperationApplied(connection, operationId, timestamp)
                }
                return
            }
            insertResourceOperation(
                connection,
                operationId,
                eventId,
                kind,
                targetId,
                fingerprint,
                timestamp,
            )
            markResourceOperationApplied(connection, operationId, timestamp)
        }

        private fun requireResourceOperation(
            connection: Connection,
            operationId: UUID,
            eventId: UUID,
            kind: String,
            targetId: String,
            fingerprint: String,
        ): ResourceOperation {
            val operation = loadResourceOperation(connection, operationId).orElseThrow {
                PersistenceConflictException("The escrow operation was not prepared: $operationId")
            }
            requireMatchingResourceOperation(
                operation,
                operationId,
                eventId,
                kind,
                targetId,
                fingerprint,
            )
            return operation
        }

        private fun requireMatchingResourceOperation(
            operation: ResourceOperation,
            operationId: UUID,
            eventId: UUID,
            kind: String,
            targetId: String,
            fingerprint: String,
        ) {
            if (!operation.operationId.equals(operationId)
                || !operation.eventId.equals(eventId)
                || !operation.kind.equals(kind)
                || !operation.targetId.equals(targetId)
                || !operation.fingerprint.equals(fingerprint)
            ) {
                throw PersistenceConflictException(
                    "The escrow operation UUID is already assigned to another payload",
                )
            }
        }

        private fun insertResourceOperation(
            connection: Connection,
            operationId: UUID,
            eventId: UUID,
            kind: String,
            targetId: String,
            fingerprint: String,
            preparedAt: Instant,
        ) {
            connection.prepareStatement(
                """
                INSERT INTO event_mutation_operations(
                    operation_id, event_id, operation_kind, target_id,
                    payload_fingerprint, state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.setString(2, eventId.toString())
                statement.setString(3, kind)
                statement.setString(4, targetId)
                statement.setString(5, fingerprint)
                statement.setString(6, preparedAt.toString())
                statement.executeUpdate()
            }
        }

        private fun markResourceOperationApplied(
            connection: Connection,
            operationId: UUID,
            appliedAt: Instant,
        ) {
            connection.prepareStatement(
                """
                UPDATE event_mutation_operations
                SET state = 'APPLIED', applied_at = ?
                WHERE operation_id = ? AND state = 'PREPARED'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, appliedAt.toString())
                statement.setString(2, operationId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException(
                        "The escrow operation was already applied or disappeared",
                    )
                }
            }
        }

        private fun loadResourceOperation(
            connection: Connection,
            eventId: UUID,
            kind: String,
            targetId: String,
        ): Optional<ResourceOperation> {
            connection.prepareStatement(
                """
                SELECT operation_id, event_id, operation_kind, target_id,
                       payload_fingerprint, state, prepared_at, applied_at
                FROM event_mutation_operations
                WHERE event_id = ? AND operation_kind = ? AND target_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, kind)
                statement.setString(3, targetId)
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(resourceOperationFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadResourceOperation(
            connection: Connection,
            operationId: UUID,
        ): Optional<ResourceOperation> {
            connection.prepareStatement(
                """
                SELECT operation_id, event_id, operation_kind, target_id,
                       payload_fingerprint, state, prepared_at, applied_at
                FROM event_mutation_operations WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(resourceOperationFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun resourceOperationFromRow(resultSet: ResultSet): ResourceOperation {
            val appliedAt = resultSet.getString("applied_at")
            return ResourceOperation(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("event_id")),
                resultSet.getString("operation_kind"),
                resultSet.getString("target_id"),
                resultSet.getString("payload_fingerprint"),
                ResourceOperationState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                if (appliedAt == null) Optional.empty() else Optional.of(instant(appliedAt)),
            )
        }

        private fun deterministicOperation(base: UUID, namespace: String, value: String): UUID =
            UUID.nameUUIDFromBytes(
                (base.toString() + "|" + namespace + "|" + value)
                    .toByteArray(StandardCharsets.UTF_8),
            )

        private fun claimTarget(dropId: UUID, recipientId: UUID): String =
            "$dropId|$recipientId"

        private fun claimFingerprint(dropId: UUID, recipientId: UUID, quantity: Int): String =
            sha256("$dropId|$recipientId|$quantity")

        private fun sha256(value: String): String {
            return try {
                java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (exception: NoSuchAlgorithmException) {
                throw AssertionError("Every Java runtime must provide SHA-256", exception)
            }
        }

        private fun isTerminal(state: String): Boolean =
            state == "VICTORY"
                || state == "DEFEAT"
                || state == "ABORTED"
                || state == "RECOVERY"

        private fun failure(action: String, exception: SQLException): PersistenceException =
            PersistenceException("Could not $action", exception)

        private fun isConstraintViolation(exception: SQLException): Boolean {
            var current: SQLException? = exception
            while (current != null) {
                if (current.errorCode == 19
                    || current.message?.lowercase(java.util.Locale.ROOT)
                        ?.contains("constraint") == true
                ) {
                    return true
                }
                current = current.nextException
            }
            return false
        }

        private fun uuid(value: String): UUID = UUID.fromString(value)

        private fun instant(value: String): Instant = Instant.parse(value)
    }

    private data class ResourceOperation(
        val operationId: UUID,
        val eventId: UUID,
        val kind: String,
        val targetId: String,
        val fingerprint: String,
        val state: ResourceOperationState,
        val preparedAt: Instant,
        val appliedAt: Optional<Instant>,
    )

    private enum class ResourceOperationState {
        PREPARED,
        APPLIED,
    }

    private data class RewardDeliveryOperation(
        val operationId: UUID,
        val queueId: UUID,
        val eventId: UUID,
        val playerId: UUID,
        val quantity: Int,
        val payloadFingerprint: String,
        val state: RewardDeliveryState,
        val preparedAt: Instant,
        val appliedAt: Optional<Instant>,
    )

    private enum class RewardDeliveryState {
        PREPARED,
        APPLIED,
    }
}
