package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.DefensePhase
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmStatic

/**
 * Team point-wallet boundary for event materials.
 *
 * All writes are operation-UUID protected. Terminal settlement is also exposed as a
 * package boundary connection method so the escrow rows, event terminal state, and wallet
 * credit commit in the same SQLite transaction.
 */
class ResourceRepository(database: Database) {
    private val database: Database = Objects.requireNonNull(database, "database")

    init {
        ensureWalletRows()
        migrateLegacyResourceQueues()
    }

    /** Repairs wallet rows for teams created before the wallet migration or partial restores. */
    private fun ensureWalletRows() {
        try {
            database.inImmediateTransaction { connection ->
                val repairedAt = Instant.now()
                connection.prepareStatement(
                    """
                    INSERT OR IGNORE INTO team_resource_balances(
                        team_id, resource_type, balance, updated_at)
                    SELECT t.team_id, r.resource_type, 0, ?
                    FROM teams t
                    CROSS JOIN (
                        SELECT 'DEFENSE_POINTS' AS resource_type
                        UNION ALL SELECT 'ENHANCEMENT_POINTS'
                    ) r
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM team_resource_balances b
                        WHERE b.team_id = t.team_id
                          AND b.resource_type = r.resource_type
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, repairedAt.toString())
                    statement.executeUpdate()
                }
                null
            }
        } catch (exception: SQLException) {
            throw resourceFailure("ensure team resource wallet rows", exception)
        }
    }

    fun load(teamId: UUID, viewerId: UUID): TeamResourceSnapshot {
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(viewerId, "viewerId")
        return try {
            database.openConnection().use { connection ->
                loadSnapshot(connection, teamId, viewerId)
            }
        } catch (exception: SQLException) {
            throw PersistenceException("Could not load team resource balances", exception)
        }
    }

    /** Loads the durable amount that the terminal transaction assigned to each wallet. */
    fun loadTerminalSettlement(
        eventId: UUID,
        phase: DefensePhase,
    ): TeamResourceSettlement {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(phase, "phase")
        if (phase == DefensePhase.RECOVERY) {
            return try {
                database.openConnection().use { connection ->
                    TeamResourceSettlement(
                        eventId,
                        loadEventTeam(connection, eventId),
                        phase,
                        0L,
                        0L,
                    )
                }
            } catch (exception: SQLException) {
                throw PersistenceException(
                    "Could not load the recovered resource settlement",
                    exception,
                )
            }
        }
        return try {
            database.openConnection().use { connection ->
                val teamId = loadEventTeam(connection, eventId)
                TeamResourceSettlement(
                    eventId,
                    teamId,
                    phase,
                    terminalAmount(connection, eventId, ResourceType.DEFENSE_POINTS, phase),
                    terminalAmount(connection, eventId, ResourceType.ENHANCEMENT_POINTS, phase),
                )
            }
        } catch (exception: SQLException) {
            throw PersistenceException("Could not load the resource settlement", exception)
        }
    }

    fun loadPickupFeedback(
        eventId: UUID,
        playerId: UUID,
        resourceType: ResourceType,
        claimedQuantity: Int,
    ): ResourcePickupFeedback {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(playerId, "playerId")
        ResourceType.require(resourceType)
        if (claimedQuantity <= 0) {
            throw IllegalArgumentException("claimedQuantity must be positive")
        }
        return try {
            database.openConnection().use { connection ->
                loadPickupFeedback(connection, eventId, playerId, resourceType, claimedQuantity)
            }
        } catch (exception: SQLException) {
            throw PersistenceException("Could not load resource pickup feedback", exception)
        }
    }

    /** Credits a wallet through a standalone idempotent operation. */
    fun credit(
        teamId: UUID,
        resourceType: ResourceType,
        amount: Long,
        operationId: UUID,
        sourceId: String,
        appliedAt: Instant,
    ): ResourceMutationResult {
        Objects.requireNonNull(teamId, "teamId")
        ResourceType.require(resourceType)
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(sourceId, "sourceId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        if (amount <= 0L) {
            throw IllegalArgumentException("credit amount must be positive")
        }
        return try {
            database.inImmediateTransaction { connection ->
                val outcome = applyDelta(
                    connection,
                    teamId,
                    null,
                    resourceType,
                    amount,
                    operationId,
                    "CREDIT",
                    sourceId,
                    sourceId + "|" + resourceType.name,
                    appliedAt,
                )
                ResourceMutationResult(
                    outcome,
                    loadSnapshot(connection, teamId, null),
                )
            }
        } catch (exception: SQLException) {
            throw resourceFailure("credit team resources", exception)
        }
    }

    /** Debits a wallet after checking team membership and the persisted balance. */
    fun debit(
        teamId: UUID,
        actorId: UUID,
        resourceType: ResourceType,
        amount: Long,
        operationId: UUID,
        sourceId: String,
        appliedAt: Instant,
    ): ResourceMutationResult {
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(actorId, "actorId")
        ResourceType.require(resourceType)
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(sourceId, "sourceId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        if (amount <= 0L) {
            throw IllegalArgumentException("debit amount must be positive")
        }
        return try {
            database.inImmediateTransaction { connection ->
                requireTeamMember(connection, teamId, actorId)
                val outcome = applyDelta(
                    connection,
                    teamId,
                    actorId,
                    resourceType,
                    -amount,
                    operationId,
                    "DEBIT",
                    sourceId,
                    teamId.toString() + "|" + actorId + "|" + resourceType.name + "|" + amount,
                    appliedAt,
                )
                ResourceMutationResult(
                    outcome,
                    loadSnapshot(connection, teamId, actorId),
                )
            }
        } catch (exception: SQLException) {
            throw resourceFailure("debit team resources", exception)
        }
    }

    /** Converts old undelivered physical-material queues exactly once at startup. */
    private fun migrateLegacyResourceQueues() {
        try {
            database.inImmediateTransaction { connection ->
                val migratedAt = Instant.now()
                connection.prepareStatement(
                    """
                    SELECT q.queue_id, q.event_id, q.item_id, q.quantity, e.team_id
                    FROM event_reward_queue q
                    JOIN defense_events e ON e.event_id = q.event_id
                    WHERE q.status = 'PENDING'
                      AND q.item_id IN ('defense_shard', 'enhancement_core')
                      AND NOT EXISTS (
                          SELECT 1
                          FROM event_reward_delivery_operations d
                          WHERE d.queue_id = q.queue_id
                      )
                    ORDER BY q.queue_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            val queueId = UUID.fromString(resultSet.getString("queue_id"))
                            val eventId = UUID.fromString(resultSet.getString("event_id"))
                            val teamId = UUID.fromString(resultSet.getString("team_id"))
                            val resourceType = ResourceType.fromItemId(
                                resultSet.getString("item_id"),
                            ).orElseThrow()
                            val quantity = resultSet.getLong("quantity")
                            val operationId = deterministic(
                                queueId,
                                "LEGACY_REWARD_QUEUE",
                                resourceType.name,
                            )
                            applyDelta(
                                connection,
                                teamId,
                                null,
                                resourceType,
                                quantity,
                                operationId,
                                "EVENT_SETTLEMENT",
                                queueId.toString(),
                                eventId.toString() + "|LEGACY_QUEUE|" + queueId + "|" + resourceType.name,
                                migratedAt,
                            )
                            connection.prepareStatement(
                                """
                                UPDATE event_reward_queue
                                SET status = 'DELIVERED', updated_at = ?
                                WHERE queue_id = ? AND status = 'PENDING'
                                """.trimIndent(),
                            ).use { update ->
                                update.setString(1, migratedAt.toString())
                                update.setString(2, queueId.toString())
                                update.executeUpdate()
                            }
                        }
                    }
                }
                null
            }
        } catch (exception: SQLException) {
            throw resourceFailure("migrate legacy resource queues", exception)
        }
    }

    companion object {
        private const val ACTIVE_EVENT_STATES =
            "('COUNTDOWN', 'PREPARATION', 'WAVE_ACTIVE', 'INTERMISSION')"

        /** Reads post-claim feedback on the same SQLite connection as the claim transaction. */
        @JvmStatic
        @Throws(SQLException::class)
        fun loadPickupFeedback(
            connection: Connection,
            eventId: UUID,
            playerId: UUID,
            resourceType: ResourceType,
            claimedQuantity: Int,
        ): ResourcePickupFeedback {
            val teamId = loadEventTeam(connection, eventId)
            val eventPlayerTotal: Long
            connection.prepareStatement(
                """
                SELECT COALESCE(SUM(c.quantity), 0)
                FROM event_drop_claims c
                JOIN event_drop_escrow d ON d.drop_id = c.drop_id
                WHERE c.event_id = ? AND c.recipient_id = ? AND d.item_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, playerId.toString())
                statement.setString(3, resourceType.itemId())
                statement.executeQuery().use { resultSet ->
                    eventPlayerTotal = if (resultSet.next()) resultSet.getLong(1) else 0L
                }
            }
            return ResourcePickupFeedback(
                eventId,
                playerId,
                resourceType,
                claimedQuantity,
                eventPlayerTotal,
                balance(connection, teamId, resourceType),
            )
        }

        /** Applies all resource settlement rows inside the event terminal transaction. */
        @JvmStatic
        @Throws(SQLException::class)
        fun settleForTerminal(
            connection: Connection,
            eventId: UUID,
            terminalOperationId: UUID,
            terminalPhase: DefensePhase,
            settledAt: Instant,
        ) {
            val teamId = loadEventTeam(connection, eventId)
            for (resourceType in ResourceType.values()) {
                val sourceId = eventId.toString() + "|" + resourceType.name
                val operationId = deterministic(
                    terminalOperationId,
                    "RESOURCE_SETTLE",
                    resourceType.name,
                )
                val amount = terminalAmount(connection, eventId, resourceType, terminalPhase)
                applyDelta(
                    connection,
                    teamId,
                    null,
                    resourceType,
                    amount,
                    operationId,
                    "EVENT_SETTLEMENT",
                    sourceId,
                    eventId.toString() + "|" + terminalPhase + "|" + resourceType.name + "|" + amount,
                    settledAt,
                )
            }
        }

        private fun terminalAmount(
            connection: Connection,
            eventId: UUID,
            resourceType: ResourceType,
            terminalPhase: DefensePhase,
        ): Long {
            if (terminalPhase == DefensePhase.RECOVERY) {
                return 0L
            }
            val expression = if (terminalPhase == DefensePhase.VICTORY) {
                "d.quantity"
            } else {
                "COALESCE(c.claimed_quantity, 0)"
            }
            val sql = """
                SELECT COALESCE(SUM(%s), 0)
                FROM event_drop_escrow d
                LEFT JOIN (
                    SELECT drop_id, SUM(quantity) AS claimed_quantity
                    FROM event_drop_claims
                    WHERE event_id = ?
                    GROUP BY drop_id
                ) c ON c.drop_id = d.drop_id
                WHERE d.event_id = ?
                  AND d.item_id = ?
                  AND d.status IN ('HELD', 'SETTLED')
                """.trimIndent().format(expression)
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, eventId.toString())
                statement.setString(3, resourceType.itemId())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) resultSet.getLong(1) else 0L
                }
            }
        }

        /** Marks a technical recovery as a zero-credit resource terminal. */
        @JvmStatic
        @Throws(SQLException::class)
        fun settleForRecovery(
            connection: Connection,
            eventId: UUID,
            recoveryOperationId: UUID,
            recoveredAt: Instant,
        ) {
            val teamId = loadEventTeam(connection, eventId)
            for (resourceType in ResourceType.values()) {
                val operationId = deterministic(
                    recoveryOperationId,
                    "RESOURCE_RECOVERY",
                    resourceType.name,
                )
                applyDelta(
                    connection,
                    teamId,
                    null,
                    resourceType,
                    0L,
                    operationId,
                    "EVENT_SETTLEMENT",
                    eventId.toString() + "|" + resourceType.name,
                    eventId.toString() + "|RECOVERY|" + resourceType.name,
                    recoveredAt,
                )
            }
        }

        /** Shared connection hook for core repair and tower upgrade wallet payments. */
        @JvmStatic
        @Throws(SQLException::class)
        fun debitInTransaction(
            connection: Connection,
            teamId: UUID,
            actorId: UUID,
            resourceType: ResourceType,
            amount: Long,
            operationId: UUID,
            sourceId: String,
            payloadFingerprint: String,
            appliedAt: Instant,
        ): OperationOutcome {
            requireTeamMember(connection, teamId, actorId)
            return applyDelta(
                connection,
                teamId,
                actorId,
                resourceType,
                -amount,
                operationId,
                "DEBIT",
                sourceId,
                payloadFingerprint,
                appliedAt,
            )
        }

        @JvmStatic
        @Throws(SQLException::class)
        fun creditInTransaction(
            connection: Connection,
            teamId: UUID,
            resourceType: ResourceType,
            amount: Long,
            operationId: UUID,
            sourceId: String,
            payloadFingerprint: String,
            appliedAt: Instant,
        ): OperationOutcome = applyDelta(
            connection,
            teamId,
            null,
            resourceType,
            amount,
            operationId,
            "CREDIT",
            sourceId,
            payloadFingerprint,
            appliedAt,
        )

        @JvmStatic
        fun isWalletResource(itemId: String?): Boolean = ResourceType.fromItemId(itemId).isPresent

        private fun applyDelta(
            connection: Connection,
            teamId: UUID,
            actorId: UUID?,
            resourceType: ResourceType,
            delta: Long,
            operationId: UUID,
            operationKind: String,
            sourceId: String,
            payloadFingerprint: String,
            appliedAt: Instant,
        ): OperationOutcome {
            ResourceType.require(resourceType)
            Objects.requireNonNull(teamId, "teamId")
            Objects.requireNonNull(operationId, "operationId")
            Objects.requireNonNull(operationKind, "operationKind")
            Objects.requireNonNull(sourceId, "sourceId")
            Objects.requireNonNull(payloadFingerprint, "payloadFingerprint")
            Objects.requireNonNull(appliedAt, "appliedAt")
            val existing = loadOperation(connection, operationId)
            if (existing.isPresent) {
                val row = existing.orElseThrow()
                if (row.teamId != teamId
                    || row.resourceType != resourceType
                    || row.operationKind != operationKind
                    || row.sourceId != sourceId
                    || row.delta != delta
                    || row.payloadFingerprint != payloadFingerprint
                ) {
                    throw PersistenceConflictException(
                        "The resource operation UUID is already assigned to another payload",
                    )
                }
                return OperationOutcome.ALREADY_APPLIED
            }
            if (delta < 0L && balance(connection, teamId, resourceType) < -delta) {
                throw PersistenceConflictException(
                    "The team does not have enough ${resourceType.displayName()}",
                )
            }
            val current = balance(connection, teamId, resourceType)
            val next = try {
                Math.addExact(current, delta)
            } catch (overflow: ArithmeticException) {
                throw PersistenceConflictException("The resource balance overflowed", overflow)
            }
            if (next < 0L) {
                throw PersistenceConflictException("The resource balance cannot be negative")
            }
            connection.prepareStatement(
                """
                INSERT INTO team_resource_operations(
                    operation_id, team_id, resource_type, operation_kind, source_id,
                    delta, payload_fingerprint, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.setString(2, teamId.toString())
                statement.setString(3, resourceType.name)
                statement.setString(4, operationKind)
                statement.setString(5, sourceId)
                statement.setLong(6, delta)
                statement.setString(7, payloadFingerprint)
                statement.setString(8, appliedAt.toString())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                UPDATE team_resource_balances
                SET balance = ?, updated_at = ?
                WHERE team_id = ? AND resource_type = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, next)
                statement.setString(2, appliedAt.toString())
                statement.setString(3, teamId.toString())
                statement.setString(4, resourceType.name)
                if (statement.executeUpdate() != 1) {
                    throw SQLException("The team resource balance row does not exist")
                }
            }
            return OperationOutcome.APPLIED
        }

        private fun loadSnapshot(
            connection: Connection,
            teamId: UUID,
            viewerId: UUID?,
        ): TeamResourceSnapshot {
            val defense = balance(connection, teamId, ResourceType.DEFENSE_POINTS)
            val enhancement = balance(connection, teamId, ResourceType.ENHANCEMENT_POINTS)
            val teamProvisionalDefense = provisional(
                connection,
                teamId,
                null,
                ResourceType.DEFENSE_POINTS,
            )
            val teamProvisionalEnhancement = provisional(
                connection,
                teamId,
                null,
                ResourceType.ENHANCEMENT_POINTS,
            )
            val viewerProvisionalDefense = provisional(
                connection,
                teamId,
                viewerId,
                ResourceType.DEFENSE_POINTS,
            )
            val viewerProvisionalEnhancement = provisional(
                connection,
                teamId,
                viewerId,
                ResourceType.ENHANCEMENT_POINTS,
            )
            return TeamResourceSnapshot(
                teamId,
                defense,
                enhancement,
                teamProvisionalDefense,
                teamProvisionalEnhancement,
                viewerProvisionalDefense,
                viewerProvisionalEnhancement,
            )
        }

        private fun provisional(
            connection: Connection,
            teamId: UUID,
            viewerId: UUID?,
            resourceType: ResourceType,
        ): Long {
            val viewerOnly = viewerId != null
            val sql = "SELECT COALESCE(SUM(c.quantity), 0) " +
                "FROM event_drop_claims c " +
                "JOIN event_drop_escrow d ON d.drop_id = c.drop_id " +
                "JOIN defense_events e ON e.event_id = d.event_id " +
                "WHERE e.team_id = ? " +
                (if (viewerOnly) "AND c.recipient_id = ? " else "") +
                "AND d.item_id = ? " +
                "AND e.state IN " + ACTIVE_EVENT_STATES
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, teamId.toString())
                var itemIndex = 2
                if (viewerOnly) {
                    statement.setString(itemIndex++, viewerId.toString())
                }
                statement.setString(itemIndex, resourceType.itemId())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) resultSet.getLong(1) else 0L
                }
            }
        }

        @JvmStatic
        @Throws(SQLException::class)
        fun balance(
            connection: Connection,
            teamId: UUID,
            resourceType: ResourceType,
        ): Long {
            connection.prepareStatement(
                """
                SELECT balance FROM team_resource_balances
                WHERE team_id = ? AND resource_type = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, teamId.toString())
                statement.setString(2, resourceType.name)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw SQLException("The team resource balance row does not exist")
                    }
                    return resultSet.getLong(1)
                }
            }
        }

        @JvmStatic
        @Throws(SQLException::class)
        fun loadEventTeam(connection: Connection, eventId: UUID): UUID {
            connection.prepareStatement(
                "SELECT team_id FROM defense_events WHERE event_id = ?",
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw SQLException("The defense event does not exist")
                    }
                    return UUID.fromString(resultSet.getString(1))
                }
            }
        }

        private fun loadOperation(
            connection: Connection,
            operationId: UUID,
        ): Optional<ResourceOperationRow> {
            connection.prepareStatement(
                """
                SELECT team_id, resource_type, operation_kind, source_id, delta, payload_fingerprint
                FROM team_resource_operations WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return Optional.empty()
                    }
                    return Optional.of(
                        ResourceOperationRow(
                            UUID.fromString(resultSet.getString("team_id")),
                            ResourceType.valueOf(resultSet.getString("resource_type")),
                            resultSet.getString("operation_kind"),
                            resultSet.getString("source_id"),
                            resultSet.getLong("delta"),
                            resultSet.getString("payload_fingerprint"),
                        ),
                    )
                }
            }
        }

        @JvmStatic
        @Throws(SQLException::class)
        fun requireTeamMember(connection: Connection, teamId: UUID, playerId: UUID) {
            connection.prepareStatement(
                """
                SELECT 1 FROM team_members WHERE team_id = ? AND player_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, teamId.toString())
                statement.setString(2, playerId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException(
                            "The player is not a member of the team",
                        )
                    }
                }
            }
        }

        private fun deterministic(base: UUID, namespace: String, value: String): UUID =
            UUID.nameUUIDFromBytes(
                (base.toString() + "|" + namespace + "|" + value)
                    .toByteArray(StandardCharsets.UTF_8),
            )

        private fun resourceFailure(action: String, exception: SQLException): PersistenceException =
            PersistenceException("Could not $action", exception)

        private data class ResourceOperationRow(
            val teamId: UUID,
            val resourceType: ResourceType,
            val operationKind: String,
            val sourceId: String,
            val delta: Long,
            val payloadFingerprint: String,
        )
    }
}
