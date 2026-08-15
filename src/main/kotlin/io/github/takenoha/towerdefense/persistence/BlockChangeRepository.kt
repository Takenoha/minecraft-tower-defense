package io.github.takenoha.towerdefense.persistence

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.ArrayList
import java.util.HexFormat
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmStatic

/**
 * SQLite-backed write-ahead ledger for event-owned world changes.
 *
 * The Paper adapter must persist a change before touching a block. It then prepares and applies
 * the physical operation separately. On recovery, [BlockRollbackPlanner] compares the durable
 * expected state with the live block before the adapter calls [prepareRollback] and [applyRollback].
 */
class BlockChangeRepository(database: Database) {
    private val database: Database = Objects.requireNonNull(database, "database")

    /** Writes the before/after snapshot before the world mutation is attempted. */
    fun prepare(
        change: BlockChange,
        prepareOperationId: UUID,
        preparedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(change, "change")
        Objects.requireNonNull(prepareOperationId, "prepareOperationId")
        Objects.requireNonNull(preparedAt, "preparedAt")
        val fingerprint = fingerprint(change)
        return try {
            database.inImmediateTransaction { connection ->
                val existing = loadChange(connection, change.changeId())
                if (existing.isPresent) {
                    val value = existing.orElseThrow()
                    if (!value.change.equals(change)
                        || !value.prepareOperationId.equals(prepareOperationId)
                    ) {
                        throw PersistenceConflictException(
                            "The block change UUID is already assigned to another payload",
                        )
                    }
                    OperationOutcome.ALREADY_APPLIED
                } else {
                    requireActiveEvent(connection, change.eventId())
                    connection.prepareStatement(
                        """
                        INSERT INTO event_block_changes(
                            change_id, event_id, world_id, block_x, block_y, block_z,
                            change_kind, generation, before_block_data, before_block_state,
                            before_tile_nbt, expected_after_block_data, expected_after_block_state,
                            expected_after_tile_nbt, status,
                            prepare_operation_id, prepared_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, change.changeId().toString())
                        statement.setString(2, change.eventId().toString())
                        statement.setString(3, change.worldId().toString())
                        statement.setInt(4, change.blockX())
                        statement.setInt(5, change.blockY())
                        statement.setInt(6, change.blockZ())
                        statement.setString(7, change.kind().name)
                        statement.setLong(8, change.generation())
                        statement.setString(9, change.beforeBlockData())
                        statement.setString(10, change.beforeBlockState())
                        statement.setString(11, change.beforeTileNbt())
                        statement.setString(12, change.expectedAfterBlockData())
                        statement.setString(13, change.expectedAfterBlockState())
                        statement.setString(14, change.expectedAfterTileNbt())
                        statement.setString(15, prepareOperationId.toString())
                        statement.setString(16, preparedAt.toString())
                        statement.executeUpdate()
                    }
                    OperationOutcome.APPLIED
                }
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The block change conflicts with an existing ledger row",
                    exception,
                )
            }
            throw failure("prepare a block change", exception)
        }
    }

    /** Records that the physical block operation is about to run. */
    fun prepareApply(
        eventId: UUID,
        changeId: UUID,
        operationId: UUID,
        preparedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(changeId, "changeId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(preparedAt, "preparedAt")
        return prepareResourceOperation(
            eventId,
            changeId,
            operationId,
            "BLOCK_APPLY",
            loadFingerprint(eventId, changeId),
            null,
            preparedAt,
        )
    }

    /** Calculates the next per-coordinate generation for a main-thread block action. */
    fun nextGeneration(
        eventId: UUID,
        worldId: UUID,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
    ): Long {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(worldId, "worldId")
        return try {
            database.inImmediateTransaction { connection ->
                requireActiveEvent(connection, eventId)
                connection.prepareStatement(
                    """
                    SELECT COALESCE(MAX(generation), 0)
                    FROM event_block_changes
                    WHERE event_id = ? AND world_id = ?
                      AND block_x = ? AND block_y = ? AND block_z = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, eventId.toString())
                    statement.setString(2, worldId.toString())
                    statement.setInt(3, blockX)
                    statement.setInt(4, blockY)
                    statement.setInt(5, blockZ)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        val current = resultSet.getLong(1)
                        if (current == Long.MAX_VALUE) {
                            throw IllegalStateException(
                                "The block mutation generation reached Long.MAX_VALUE",
                            )
                        }
                        current + 1L
                    }
                }
            }
        } catch (exception: SQLException) {
            throw failure("allocate a block mutation generation", exception)
        }
    }

    /** Marks a previously prepared physical block operation as applied. */
    fun apply(
        eventId: UUID,
        changeId: UUID,
        operationId: UUID,
        appliedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(changeId, "changeId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        val fingerprint = loadFingerprint(eventId, changeId)
        return try {
            database.inImmediateTransaction { connection ->
                val operation = requireResourceOperation(
                    connection,
                    eventId,
                    changeId,
                    operationId,
                    "BLOCK_APPLY",
                    fingerprint,
                )
                if (operation.state == ResourceOperationState.APPLIED) {
                    OperationOutcome.ALREADY_APPLIED
                } else {
                    requireActiveEvent(connection, eventId)
                    val change = requireChange(connection, changeId)
                    if (change.status != BlockChangeStatus.PREPARED) {
                        throw PersistenceConflictException(
                            "A block change is no longer prepared for application",
                        )
                    }
                    connection.prepareStatement(
                        """
                        UPDATE event_block_changes
                        SET status = 'APPLIED', apply_operation_id = ?, applied_at = ?
                        WHERE change_id = ? AND status = 'PREPARED'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, operationId.toString())
                        statement.setString(2, appliedAt.toString())
                        statement.setString(3, changeId.toString())
                        if (statement.executeUpdate() != 1) {
                            throw PersistenceConflictException(
                                "The block change was concurrently applied or resolved",
                            )
                        }
                    }
                    markResourceOperationApplied(connection, operationId, appliedAt)
                    OperationOutcome.APPLIED
                }
            }
        } catch (exception: SQLException) {
            throw failure("apply a prepared block change", exception)
        }
    }

    /** Records that a recovery rollback decision is about to be executed. */
    fun prepareRollback(
        eventId: UUID,
        changeId: UUID,
        operationId: UUID,
        decision: BlockRollbackDecision,
        preparedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(decision, "decision")
        return prepareResourceOperation(
            eventId,
            changeId,
            operationId,
            "BLOCK_ROLLBACK",
            rollbackFingerprint(eventId, changeId, decision),
            decision.name,
            preparedAt,
        )
    }

    /**
     * Marks a rollback as complete. A conflict is durable and intentionally does not overwrite the
     * live block; an administrator can inspect it before deciding on a manual repair.
     */
    fun applyRollback(
        eventId: UUID,
        changeId: UUID,
        operationId: UUID,
        decision: BlockRollbackDecision,
        resolvedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(changeId, "changeId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(decision, "decision")
        Objects.requireNonNull(resolvedAt, "resolvedAt")
        val fingerprint = rollbackFingerprint(eventId, changeId, decision)
        return try {
            database.inImmediateTransaction { connection ->
                val operation = requireResourceOperation(
                    connection,
                    eventId,
                    changeId,
                    operationId,
                    "BLOCK_ROLLBACK",
                    fingerprint,
                )
                if (operation.state == ResourceOperationState.APPLIED) {
                    OperationOutcome.ALREADY_APPLIED
                } else {
                    requireActiveEvent(connection, eventId)
                    val change = requireChange(connection, changeId)
                    if (change.status != BlockChangeStatus.PREPARED
                        && change.status != BlockChangeStatus.APPLIED
                    ) {
                        throw PersistenceConflictException(
                            "A block change has already reached a terminal recovery status",
                        )
                    }
                    val nextStatus = if (decision == BlockRollbackDecision.CONFLICT) {
                        BlockChangeStatus.CONFLICT
                    } else {
                        BlockChangeStatus.ROLLED_BACK
                    }
                    connection.prepareStatement(
                        """
                        UPDATE event_block_changes
                        SET status = ?, rollback_operation_id = ?,
                            applied_at = COALESCE(applied_at, ?), resolved_at = ?
                        WHERE change_id = ? AND status IN ('PREPARED', 'APPLIED')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, nextStatus.name)
                        statement.setString(2, operationId.toString())
                        statement.setString(3, resolvedAt.toString())
                        statement.setString(4, resolvedAt.toString())
                        statement.setString(5, changeId.toString())
                        if (statement.executeUpdate() != 1) {
                            throw PersistenceConflictException(
                                "The block change was concurrently resolved",
                            )
                        }
                    }
                    markResourceOperationApplied(connection, operationId, resolvedAt)
                    OperationOutcome.APPLIED
                }
            }
        } catch (exception: SQLException) {
            throw failure("apply a block rollback", exception)
        }
    }

    /** Loads all ledger rows in reverse generation order for safe rollback. */
    fun loadChanges(eventId: UUID): List<StoredBlockChange> {
        Objects.requireNonNull(eventId, "eventId")
        return read("load block changes") { connection -> loadChanges(connection, eventId) }
    }

    /** Loads only rows which still need a recovery decision. */
    fun loadUnresolvedChanges(eventId: UUID): List<StoredBlockChange> {
        return loadChanges(eventId).stream()
            .filter {
                it.status == BlockChangeStatus.PREPARED
                    || it.status == BlockChangeStatus.APPLIED
            }
            .toList()
    }

    /** Counts unresolved temporary blocks so a builder cap survives a process restart. */
    fun countUnresolvedTemporaryBlocks(eventId: UUID): Long {
        Objects.requireNonNull(eventId, "eventId")
        return read("count unresolved temporary blocks") { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM event_block_changes
                WHERE event_id = ?
                  AND change_kind = 'TEMPORARY_BLOCK'
                  AND status IN ('PREPARED', 'APPLIED')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
        }
    }

    /** Loads a rollback operation which was prepared but not committed before a stop. */
    fun loadPreparedRollback(eventId: UUID, changeId: UUID): Optional<PreparedRollback> {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(changeId, "changeId")
        return read("load a prepared block rollback") { connection ->
            val operation = loadResourceOperation(
                connection,
                eventId,
                "BLOCK_ROLLBACK",
                changeId,
            )
            if (operation.isEmpty
                || operation.orElseThrow().state != ResourceOperationState.PREPARED
            ) {
                Optional.empty()
            } else {
                val value = operation.orElseThrow()
                val decision = value.rollbackDecision.orElseThrow {
                    PersistenceConflictException(
                        "A prepared rollback has no persisted decision",
                    )
                }
                Optional.of(PreparedRollback(value.operationId, decision))
            }
        }
    }

    companion object {
        /** Package-private guard used by event recovery to avoid releasing a dirty world mutation. */
        @JvmStatic
        @Throws(SQLException::class)
        fun hasUnresolved(connection: Connection, eventId: UUID): Boolean {
            return connection.prepareStatement(
                """
                SELECT 1 FROM event_block_changes
                WHERE event_id = ? AND status IN ('PREPARED', 'APPLIED')
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                }
            }
        }

        /**
         * Settles applied event-destruction rows in the same transaction as the normal terminal
         * event. Temporary rows must already have been physically removed by the Paper adapter.
         * Keeping this operation inside the terminal transaction means a crash before lock release
         * still exposes the destruction rows to technical recovery.
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun settleAppliedEventBlocks(
            connection: Connection,
            eventId: UUID,
            terminalOperationId: UUID,
            settledAt: Instant,
        ) {
            Objects.requireNonNull(connection, "connection")
            Objects.requireNonNull(eventId, "eventId")
            Objects.requireNonNull(terminalOperationId, "terminalOperationId")
            Objects.requireNonNull(settledAt, "settledAt")
            requireActiveEvent(connection, eventId)
            for (change in loadChanges(connection, eventId)) {
                if (change.status == BlockChangeStatus.PREPARED) {
                    throw PersistenceConflictException(
                        "A block change is still prepared during normal terrain settlement",
                    )
                }
                if (change.status != BlockChangeStatus.APPLIED) {
                    continue
                }
                if (change.change.kind() != BlockChangeKind.EVENT_BLOCK) {
                    throw PersistenceConflictException(
                        "A temporary block remained unresolved during normal terrain settlement",
                    )
                }
                val operationId = deterministicOperation(
                    terminalOperationId,
                    "BLOCK_SETTLE",
                    change.change.changeId(),
                )
                ensureResourceOperationApplied(
                    connection,
                    operationId,
                    eventId,
                    "BLOCK_SETTLE",
                    change.change.changeId(),
                    settlementFingerprint(change.change),
                    settledAt,
                )
                connection.prepareStatement(
                    """
                    UPDATE event_block_changes
                    SET status = 'SETTLED', rollback_operation_id = ?, resolved_at = ?
                    WHERE change_id = ? AND status = 'APPLIED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, operationId.toString())
                    statement.setString(2, settledAt.toString())
                    statement.setString(3, change.change.changeId().toString())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The event block changed while terminal settlement was running",
                        )
                    }
                }
            }
            if (hasUnresolved(connection, eventId)) {
                throw PersistenceConflictException(
                    "Unresolved block changes remain after normal terrain settlement",
                )
            }
        }

        private fun loadChanges(
            connection: Connection,
            eventId: UUID,
        ): List<StoredBlockChange> {
            val changes = ArrayList<StoredBlockChange>()
            connection.prepareStatement(
                """
                SELECT change_id, event_id, world_id, block_x, block_y, block_z,
                       change_kind, generation, before_block_data, before_block_state,
                       before_tile_nbt, expected_after_block_data, expected_after_block_state,
                       expected_after_tile_nbt, status,
                       prepare_operation_id, apply_operation_id, rollback_operation_id,
                       prepared_at, applied_at, resolved_at
                FROM event_block_changes
                WHERE event_id = ?
                ORDER BY generation DESC, change_id DESC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        changes.add(blockChangeFromRow(resultSet))
                    }
                }
            }
            return java.util.List.copyOf(changes)
        }

        private fun ensureResourceOperationApplied(
            connection: Connection,
            operationId: UUID,
            eventId: UUID,
            kind: String,
            targetId: UUID,
            fingerprint: String,
            timestamp: Instant,
        ) {
            val existing = loadResourceOperation(connection, eventId, kind, targetId)
            if (existing.isPresent) {
                val value = existing.orElseThrow()
                requireMatchingResourceOperation(
                    value,
                    operationId,
                    eventId,
                    kind,
                    targetId,
                    fingerprint,
                )
                if (value.state == ResourceOperationState.PREPARED) {
                    markResourceOperationApplied(connection, operationId, timestamp)
                }
                return
            }
            val sameOperation = loadResourceOperation(connection, operationId)
            if (sameOperation.isPresent) {
                val value = sameOperation.orElseThrow()
                requireMatchingResourceOperation(
                    value,
                    operationId,
                    eventId,
                    kind,
                    targetId,
                    fingerprint,
                )
                if (value.state == ResourceOperationState.PREPARED) {
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
                null,
                timestamp,
            )
            markResourceOperationApplied(connection, operationId, timestamp)
        }

        private fun requireResourceOperation(
            connection: Connection,
            eventId: UUID,
            targetId: UUID,
            operationId: UUID,
            kind: String,
            fingerprint: String,
        ): ResourceOperation {
            val operation = loadResourceOperation(connection, operationId).orElseThrow {
                PersistenceConflictException("The resource operation was not prepared: $operationId")
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
            targetId: UUID,
            fingerprint: String,
        ) {
            if (operation.operationId != operationId
                || operation.eventId != eventId
                || operation.kind != kind
                || operation.targetId != targetId.toString()
                || operation.fingerprint != fingerprint
            ) {
                throw PersistenceConflictException(
                    "The resource operation UUID is already assigned to another payload",
                )
            }
        }

        private fun insertResourceOperation(
            connection: Connection,
            operationId: UUID,
            eventId: UUID,
            kind: String,
            targetId: UUID,
            fingerprint: String,
            rollbackDecision: String?,
            preparedAt: Instant,
        ) {
            connection.prepareStatement(
                """
                INSERT INTO event_mutation_operations(
                    operation_id, event_id, operation_kind, target_id,
                    payload_fingerprint, state, prepared_at, rollback_decision
                ) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.setString(2, eventId.toString())
                statement.setString(3, kind)
                statement.setString(4, targetId.toString())
                statement.setString(5, fingerprint)
                statement.setString(6, preparedAt.toString())
                statement.setString(7, rollbackDecision)
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
                        "The resource operation was already applied or disappeared",
                    )
                }
            }
        }

        private fun loadResourceOperation(
            connection: Connection,
            eventId: UUID,
            kind: String,
            targetId: UUID,
        ): Optional<ResourceOperation> {
            connection.prepareStatement(
                """
                SELECT operation_id, event_id, operation_kind, target_id,
                       payload_fingerprint, state, prepared_at, applied_at,
                       rollback_decision
                FROM event_mutation_operations
                WHERE event_id = ? AND operation_kind = ? AND target_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, kind)
                statement.setString(3, targetId.toString())
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
                       payload_fingerprint, state, prepared_at, applied_at,
                       rollback_decision
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
            val rollbackDecision = resultSet.getString("rollback_decision")
            return ResourceOperation(
                UUID.fromString(resultSet.getString("operation_id")),
                UUID.fromString(resultSet.getString("event_id")),
                resultSet.getString("operation_kind"),
                resultSet.getString("target_id"),
                resultSet.getString("payload_fingerprint"),
                ResourceOperationState.valueOf(resultSet.getString("state")),
                Instant.parse(resultSet.getString("prepared_at")),
                if (appliedAt == null) {
                    Optional.empty()
                } else {
                    Optional.of(Instant.parse(appliedAt))
                },
                if (rollbackDecision == null) {
                    Optional.empty()
                } else {
                    Optional.of(BlockRollbackDecision.valueOf(rollbackDecision))
                },
            )
        }

        private fun loadChange(
            connection: Connection,
            changeId: UUID,
        ): Optional<StoredBlockChange> {
            connection.prepareStatement(
                """
                SELECT change_id, event_id, world_id, block_x, block_y, block_z,
                       change_kind, generation, before_block_data, before_block_state,
                       before_tile_nbt, expected_after_block_data, expected_after_block_state,
                       expected_after_tile_nbt, status,
                       prepare_operation_id, apply_operation_id, rollback_operation_id,
                       prepared_at, applied_at, resolved_at
                FROM event_block_changes WHERE change_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, changeId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(blockChangeFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun requireChange(
            connection: Connection,
            changeId: UUID,
        ): StoredBlockChange =
            loadChange(connection, changeId).orElseThrow {
                PersistenceConflictException("Unknown block change $changeId")
            }

        private fun blockChangeFromRow(resultSet: ResultSet): StoredBlockChange {
            val applyOperation = resultSet.getString("apply_operation_id")
            val rollbackOperation = resultSet.getString("rollback_operation_id")
            val appliedAt = resultSet.getString("applied_at")
            val resolvedAt = resultSet.getString("resolved_at")
            val change = BlockChange(
                UUID.fromString(resultSet.getString("event_id")),
                UUID.fromString(resultSet.getString("change_id")),
                UUID.fromString(resultSet.getString("world_id")),
                resultSet.getInt("block_x"),
                resultSet.getInt("block_y"),
                resultSet.getInt("block_z"),
                BlockChangeKind.valueOf(resultSet.getString("change_kind")),
                resultSet.getLong("generation"),
                resultSet.getString("before_block_data"),
                resultSet.getString("before_block_state"),
                resultSet.getString("before_tile_nbt"),
                resultSet.getString("expected_after_block_data"),
                resultSet.getString("expected_after_block_state"),
                resultSet.getString("expected_after_tile_nbt"),
            )
            return StoredBlockChange(
                change,
                BlockChangeStatus.valueOf(resultSet.getString("status")),
                UUID.fromString(resultSet.getString("prepare_operation_id")),
                if (applyOperation == null) {
                    Optional.empty()
                } else {
                    Optional.of(UUID.fromString(applyOperation))
                },
                if (rollbackOperation == null) {
                    Optional.empty()
                } else {
                    Optional.of(UUID.fromString(rollbackOperation))
                },
                Instant.parse(resultSet.getString("prepared_at")),
                if (appliedAt == null) {
                    Optional.empty()
                } else {
                    Optional.of(Instant.parse(appliedAt))
                },
                if (resolvedAt == null) {
                    Optional.empty()
                } else {
                    Optional.of(Instant.parse(resolvedAt))
                },
            )
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
                    if (isTerminalPhase(resultSet.getString("state"))) {
                        throw IllegalStateException(
                            "Cannot mutate the block ledger of a terminal event",
                        )
                    }
                }
            }
        }

        private fun isTerminalPhase(phase: String?): Boolean =
            phase == "VICTORY"
                || phase == "DEFEAT"
                || phase == "ABORTED"
                || phase == "RECOVERY"

        private fun fingerprint(change: BlockChange): String {
            val canonical = change.eventId().toString() + "|" + change.changeId() + "|" +
                change.worldId() + "|" + change.blockX() + "|" + change.blockY() + "|" +
                change.blockZ() + "|" + change.kind() + "|" + change.generation() + "|" +
                change.beforeBlockData() + "|" + change.beforeBlockState() + "|" +
                change.beforeTileNbt() + "|" + change.expectedAfterBlockData() + "|" +
                change.expectedAfterBlockState() + "|" + change.expectedAfterTileNbt()
            return sha256(canonical)
        }

        private fun settlementFingerprint(change: BlockChange): String =
            sha256(fingerprint(change) + "|BLOCK_SETTLE")

        private fun deterministicOperation(base: UUID, namespace: String, value: UUID): UUID =
            UUID.nameUUIDFromBytes(
                (base.toString() + "|" + namespace + "|" + value)
                    .toByteArray(StandardCharsets.UTF_8),
            )

        private fun sha256(value: String): String {
            return try {
                HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (exception: NoSuchAlgorithmException) {
                throw AssertionError("Every Java runtime must provide SHA-256", exception)
            }
        }

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

        private data class ResourceOperation(
            val operationId: UUID,
            val eventId: UUID,
            val kind: String,
            val targetId: String,
            val fingerprint: String,
            val state: ResourceOperationState,
            val preparedAt: Instant,
            val appliedAt: Optional<Instant>,
            val rollbackDecision: Optional<BlockRollbackDecision>,
        )

        private enum class ResourceOperationState {
            PREPARED,
            APPLIED,
        }
    }

    private fun loadFingerprint(eventId: UUID, changeId: UUID): String =
        read("load a block change fingerprint") { connection ->
            val change = requireChange(connection, changeId)
            if (change.change.eventId() != eventId) {
                throw PersistenceConflictException(
                    "The block change belongs to another defense event",
                )
            }
            fingerprint(change.change)
        }

    private fun rollbackFingerprint(
        eventId: UUID,
        changeId: UUID,
        decision: BlockRollbackDecision,
    ): String = sha256(loadFingerprint(eventId, changeId) + "|" + decision.name)

    private fun prepareResourceOperation(
        eventId: UUID,
        targetId: UUID,
        operationId: UUID,
        kind: String,
        fingerprint: String,
        rollbackDecision: String?,
        preparedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(targetId, "targetId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(kind, "kind")
        Objects.requireNonNull(fingerprint, "fingerprint")
        Objects.requireNonNull(preparedAt, "preparedAt")
        return try {
            database.inImmediateTransaction { connection ->
                requireActiveEvent(connection, eventId)
                val existing = loadResourceOperation(connection, eventId, kind, targetId)
                if (existing.isPresent) {
                    val value = existing.orElseThrow()
                    requireMatchingResourceOperation(
                        value,
                        operationId,
                        eventId,
                        kind,
                        targetId,
                        fingerprint,
                    )
                    OperationOutcome.ALREADY_APPLIED
                } else {
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
                        OperationOutcome.ALREADY_APPLIED
                    } else {
                        insertResourceOperation(
                            connection,
                            operationId,
                            eventId,
                            kind,
                            targetId,
                            fingerprint,
                            rollbackDecision,
                            preparedAt,
                        )
                        OperationOutcome.APPLIED
                    }
                }
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The block resource operation conflicts with persisted data",
                    exception,
                )
            }
            throw failure("prepare a block resource operation", exception)
        }
    }

    private fun <T> read(action: String, work: (Connection) -> T): T =
        try {
            database.openConnection().use { connection -> work(connection) }
        } catch (exception: SQLException) {
            throw failure(action, exception)
        }
}
