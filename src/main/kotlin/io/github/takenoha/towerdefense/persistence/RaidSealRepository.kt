package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.domain.DefensePhase
import io.github.takenoha.towerdefense.domain.StageWaveSchedule
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import java.util.Objects
import java.util.Optional
import java.util.UUID

/**
 * Authoritative persistence for non-stackable challenge seals.
 *
 * The normal player-facing path reserves a seal before touching an inventory and consumes it
 * only after the start transaction has passed all validation. Technical refunds always create a
 * new seal UUID; the original UUID is never made usable again.
 */
class RaidSealRepository(database: Database) {
    private val database: Database = Objects.requireNonNull(database, "database")

    /** Registers a newly crafted seal in the available state. */
    fun register(
        sealId: UUID,
        ownerPlayerId: UUID,
        stageLevel: Long,
        createdAt: Instant,
    ): RaidSeal {
        Objects.requireNonNull(sealId, "sealId")
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId")
        Objects.requireNonNull(createdAt, "createdAt")
        StageWaveSchedule.requireValidStageLevel(stageLevel)
        val seal = RaidSeal(
            sealId,
            ownerPlayerId,
            stageLevel,
            RaidSealStatus.AVAILABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            createdAt,
            createdAt,
        )
        try {
            database.inImmediateTransaction<Any?> { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO raid_seals(
                        seal_id, owner_player_id, stage_level, state, created_at, updated_at
                    ) VALUES (?, ?, ?, 'AVAILABLE', ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, sealId.toString())
                    statement.setString(2, ownerPlayerId.toString())
                    statement.setLong(3, stageLevel)
                    statement.setString(4, createdAt.toString())
                    statement.setString(5, createdAt.toString())
                    statement.executeUpdate()
                }
                null
            }
            return seal
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The raid seal UUID is already registered",
                    exception,
                )
            }
            throw failure("register a raid seal", exception)
        }
    }

    /** Reserves a seal before the corresponding physical inventory item is removed. */
    fun reserve(
        sealId: UUID,
        eventId: UUID,
        ownerPlayerId: UUID,
        stageLevel: Long,
        operationId: UUID,
        reservedAt: Instant,
    ): OperationOutcome {
        requireSealMutationArguments(
            sealId,
            eventId,
            ownerPlayerId,
            stageLevel,
            operationId,
            reservedAt,
        )
        try {
            return database.inImmediateTransaction { connection ->
                reserve(
                    connection,
                    sealId,
                    eventId,
                    ownerPlayerId,
                    stageLevel,
                    operationId,
                    reservedAt,
                )
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The raid seal reservation conflicts with another event",
                    exception,
                )
            }
            throw failure("reserve a raid seal", exception)
        }
    }

    /** Commits a reservation after the caller has removed the physical token. */
    fun consume(
        sealId: UUID,
        eventId: UUID,
        operationId: UUID,
        consumedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(sealId, "sealId")
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(consumedAt, "consumedAt")
        try {
            return database.inImmediateTransaction { connection ->
                consume(connection, sealId, eventId, operationId, consumedAt)
            }
        } catch (exception: SQLException) {
            throw failure("consume a reserved raid seal", exception)
        }
    }

    /** Returns a fresh usable seal for a technical recovery. */
    fun refund(
        eventId: UUID,
        recoveryOperationId: UUID,
        refundedAt: Instant,
    ): RaidSealRefundResult {
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(recoveryOperationId, "recoveryOperationId")
        Objects.requireNonNull(refundedAt, "refundedAt")
        try {
            return database.inImmediateTransaction { connection ->
                refund(connection, eventId, recoveryOperationId, refundedAt)
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The raid seal refund conflicts with persisted data",
                    exception,
                )
            }
            throw failure("refund a raid seal", exception)
        }
    }

    fun find(sealId: UUID): Optional<RaidSeal> {
        Objects.requireNonNull(sealId, "sealId")
        return read("load a raid seal") { connection -> load(connection, sealId) }
    }

    fun loadForOwner(ownerPlayerId: UUID): List<RaidSeal> {
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId")
        return read("load a player's raid seals") { connection ->
            val seals = ArrayList<RaidSeal>()
            connection.prepareStatement(
                """
                SELECT seal_id, owner_player_id, stage_level, state, event_id,
                       reservation_operation_id, consumption_operation_id,
                       refund_operation_id, created_at, updated_at
                FROM raid_seals WHERE owner_player_id = ? ORDER BY seal_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ownerPlayerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        seals.add(sealFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(seals)
        }
    }

    /**
     * Loads technical-refund seals which are ready to be materialized as a new physical item.
     * The returned UUID is never the UUID that was consumed for the failed event.
     */
    fun loadAvailableRefunds(ownerPlayerId: UUID): List<RaidSeal> {
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId")
        return read("load a player's refundable raid seals") { connection ->
            val seals = ArrayList<RaidSeal>()
            connection.prepareStatement(
                """
                SELECT s.seal_id, s.owner_player_id, s.stage_level, s.state, s.event_id,
                       s.reservation_operation_id, s.consumption_operation_id,
                       s.refund_operation_id, s.created_at, s.updated_at
                FROM raid_seals s
                INNER JOIN raid_seal_returns r ON r.returned_seal_id = s.seal_id
                WHERE s.owner_player_id = ? AND s.state = 'AVAILABLE'
                ORDER BY s.created_at, s.seal_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ownerPlayerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        seals.add(sealFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(seals)
        }
    }

    companion object {
        /** Package-private hook used by the atomic start transaction when a seal is supplied. */
        @JvmStatic
        @Throws(SQLException::class)
        fun consumeForStart(connection: Connection, request: StartRequest) {
            if (request.raidSealId().isEmpty) {
                return
            }
            reserveForStart(connection, request)
            consumeReservedForStart(
                connection,
                request.session().eventId(),
                request.raidSealId().orElseThrow(),
                request.startedAt(),
            )
        }

        /** Package-private hook for a Paper start which must remove the physical item between reserve and consume. */
        @JvmStatic
        @Throws(SQLException::class)
        fun reserveForStart(connection: Connection, request: StartRequest) {
            if (request.raidSealId().isEmpty) {
                return
            }
            val sealId = request.raidSealId().orElseThrow()
            val eventId = request.session().eventId()
            val teamId = request.session().teamId()
            val ownerPlayerId = loadTeamOwner(connection, teamId)
            val reservationOperationId = deterministicOperation(eventId, "SEAL_RESERVE")
            val stageLevel = request.session().stageLevel()
            val reserved = reserve(
                connection,
                sealId,
                eventId,
                ownerPlayerId,
                stageLevel,
                reservationOperationId,
                request.startedAt(),
            )
            if (reserved == OperationOutcome.ALREADY_APPLIED) {
                val current = load(connection, sealId).orElseThrow()
                if (current.status() == RaidSealStatus.CONSUMED
                    && current.eventId().orElseThrow() == eventId
                ) {
                    return
                }
            }
        }

        /** Package-private hook for a Paper start after its physical item has been removed. */
        @JvmStatic
        @Throws(SQLException::class)
        fun consumeReservedForStart(
            connection: Connection,
            eventId: UUID,
            sealId: UUID,
            consumedAt: Instant,
        ): OperationOutcome {
            requireActiveEvent(connection, eventId)
            return consume(
                connection,
                sealId,
                eventId,
                deterministicOperation(eventId, "SEAL_CONSUME"),
                consumedAt,
            )
        }

        /** Package-private hook used by technical event recovery. */
        @JvmStatic
        @Throws(SQLException::class)
        fun refund(
            connection: Connection,
            eventId: UUID,
            recoveryOperationId: UUID,
            refundedAt: Instant,
        ): RaidSealRefundResult {
            requireRecoveryEvent(connection, eventId)
            val existingReturn = loadReturn(connection, recoveryOperationId)
            if (existingReturn.isPresent) {
                val returned = load(connection, existingReturn.orElseThrow().returnedSealId)
                    .orElseThrow()
                return RaidSealRefundResult(OperationOutcome.ALREADY_APPLIED, returned)
            }
            val original = loadByEvent(connection, eventId)
            if (original.isEmpty) {
                throw PersistenceConflictException(
                    "No consumed or reserved raid seal belongs to event $eventId",
                )
            }
            val source = original.orElseThrow()
            if (source.status() != RaidSealStatus.RESERVED
                && source.status() != RaidSealStatus.CONSUMED
            ) {
                throw PersistenceConflictException(
                    "The raid seal is not eligible for a technical refund",
                )
            }
            val returnedSealId = deterministicOperation(recoveryOperationId, "SEAL_RETURN")
            connection.prepareStatement(
                """
                UPDATE raid_seals
                SET state = 'REFUNDED', refund_operation_id = ?, updated_at = ?
                WHERE seal_id = ? AND state IN ('RESERVED', 'CONSUMED')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, recoveryOperationId.toString())
                statement.setString(2, refundedAt.toString())
                statement.setString(3, source.sealId().toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException(
                        "The raid seal was concurrently refunded or consumed",
                    )
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO raid_seals(
                    seal_id, owner_player_id, stage_level, state, created_at, updated_at
                ) VALUES (?, ?, ?, 'AVAILABLE', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, returnedSealId.toString())
                statement.setString(2, source.ownerPlayerId().toString())
                statement.setLong(3, source.stageLevel())
                statement.setString(4, refundedAt.toString())
                statement.setString(5, refundedAt.toString())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO raid_seal_returns(
                    return_operation_id, event_id, original_seal_id, returned_seal_id,
                    owner_player_id, stage_level, issued_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, recoveryOperationId.toString())
                statement.setString(2, eventId.toString())
                statement.setString(3, source.sealId().toString())
                statement.setString(4, returnedSealId.toString())
                statement.setString(5, source.ownerPlayerId().toString())
                statement.setLong(6, source.stageLevel())
                statement.setString(7, refundedAt.toString())
                statement.executeUpdate()
            }
            val returned = load(connection, returnedSealId).orElseThrow()
            return RaidSealRefundResult(OperationOutcome.APPLIED, returned)
        }

        /** Package-private hook for administrator-only starts which did not use a seal. */
        @JvmStatic
        @Throws(SQLException::class)
        fun refundIfPresent(
            connection: Connection,
            eventId: UUID,
            recoveryOperationId: UUID,
            refundedAt: Instant,
        ): Optional<RaidSealRefundResult> {
            if (loadByEvent(connection, eventId).isEmpty) {
                return Optional.empty()
            }
            return Optional.of(refund(connection, eventId, recoveryOperationId, refundedAt))
        }

        private fun reserve(
            connection: Connection,
            sealId: UUID,
            eventId: UUID,
            ownerPlayerId: UUID,
            stageLevel: Long,
            operationId: UUID,
            reservedAt: Instant,
        ): OperationOutcome {
            requireActiveEvent(connection, eventId)
            val current = load(connection, sealId)
            if (current.isEmpty) {
                throw PersistenceConflictException("Unknown raid seal $sealId")
            }
            val value = current.orElseThrow()
            if (value.status() == RaidSealStatus.RESERVED
                && value.eventId().orElseThrow() == eventId
                && value.reservationOperationId().orElseThrow() == operationId
            ) {
                return OperationOutcome.ALREADY_APPLIED
            }
            if (value.status() == RaidSealStatus.CONSUMED
                && value.eventId().orElseThrow() == eventId
                && value.reservationOperationId().orElseThrow() == operationId
            ) {
                return OperationOutcome.ALREADY_APPLIED
            }
            if (value.status() != RaidSealStatus.AVAILABLE
                || value.ownerPlayerId() != ownerPlayerId
                || value.stageLevel() != stageLevel
            ) {
                throw PersistenceConflictException(
                    "The raid seal is unavailable for this owner, stage, or event",
                )
            }
            connection.prepareStatement(
                """
                UPDATE raid_seals
                SET state = 'RESERVED', event_id = ?, reservation_operation_id = ?, updated_at = ?
                WHERE seal_id = ? AND state = 'AVAILABLE'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.setString(2, operationId.toString())
                statement.setString(3, reservedAt.toString())
                statement.setString(4, sealId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The raid seal was reserved concurrently")
                }
            }
            return OperationOutcome.APPLIED
        }

        private fun consume(
            connection: Connection,
            sealId: UUID,
            eventId: UUID,
            operationId: UUID,
            consumedAt: Instant,
        ): OperationOutcome {
            val current = load(connection, sealId)
            if (current.isEmpty) {
                throw PersistenceConflictException("Unknown raid seal $sealId")
            }
            val value = current.orElseThrow()
            if (value.status() == RaidSealStatus.CONSUMED
                && value.eventId().orElseThrow() == eventId
                && value.consumptionOperationId().orElseThrow() == operationId
            ) {
                return OperationOutcome.ALREADY_APPLIED
            }
            if (value.status() != RaidSealStatus.RESERVED
                || value.eventId().orElseThrow() != eventId
            ) {
                throw PersistenceConflictException(
                    "Only a reservation belonging to this event can be consumed",
                )
            }
            connection.prepareStatement(
                """
                UPDATE raid_seals
                SET state = 'CONSUMED', consumption_operation_id = ?, updated_at = ?
                WHERE seal_id = ? AND event_id = ? AND state = 'RESERVED'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.setString(2, consumedAt.toString())
                statement.setString(3, sealId.toString())
                statement.setString(4, eventId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The raid seal was consumed concurrently")
                }
            }
            return OperationOutcome.APPLIED
        }

        private fun load(connection: Connection, sealId: UUID): Optional<RaidSeal> {
            connection.prepareStatement(
                """
                SELECT seal_id, owner_player_id, stage_level, state, event_id,
                       reservation_operation_id, consumption_operation_id,
                       refund_operation_id, created_at, updated_at
                FROM raid_seals WHERE seal_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sealId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(sealFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadByEvent(connection: Connection, eventId: UUID): Optional<RaidSeal> {
            connection.prepareStatement(
                """
                SELECT seal_id, owner_player_id, stage_level, state, event_id,
                       reservation_operation_id, consumption_operation_id,
                       refund_operation_id, created_at, updated_at
                FROM raid_seals WHERE event_id = ? AND state IN ('RESERVED', 'CONSUMED')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(sealFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadReturn(
            connection: Connection,
            operationId: UUID,
        ): Optional<RaidSealReturn> {
            connection.prepareStatement(
                """
                SELECT returned_seal_id FROM raid_seal_returns WHERE return_operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(
                            RaidSealReturn(uuid(resultSet.getString("returned_seal_id"))),
                        )
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun sealFromRow(resultSet: ResultSet): RaidSeal = RaidSeal(
            uuid(resultSet.getString("seal_id")),
            uuid(resultSet.getString("owner_player_id")),
            resultSet.getLong("stage_level"),
            RaidSealStatus.valueOf(resultSet.getString("state")),
            optionalUuid(resultSet.getString("event_id")),
            optionalUuid(resultSet.getString("reservation_operation_id")),
            optionalUuid(resultSet.getString("consumption_operation_id")),
            optionalUuid(resultSet.getString("refund_operation_id")),
            instant(resultSet.getString("created_at")),
            instant(resultSet.getString("updated_at")),
        )

        private fun loadTeamOwner(connection: Connection, teamId: UUID): UUID {
            connection.prepareStatement(
                "SELECT owner_player_id FROM teams WHERE team_id = ?",
            ).use { statement ->
                statement.setString(1, teamId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException("Unknown defense team $teamId")
                    }
                    return uuid(resultSet.getString("owner_player_id"))
                }
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
                    val state = resultSet.getString("state")
                    if (state == DefensePhase.VICTORY.name
                        || state == DefensePhase.DEFEAT.name
                        || state == DefensePhase.ABORTED.name
                        || state == DefensePhase.RECOVERY.name
                    ) {
                        throw IllegalStateException(
                            "Cannot mutate a raid seal for a terminal defense event",
                        )
                    }
                }
            }
        }

        private fun requireRecoveryEvent(connection: Connection, eventId: UUID) {
            connection.prepareStatement(
                "SELECT state FROM defense_events WHERE event_id = ?",
            ).use { statement ->
                statement.setString(1, eventId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException("Unknown defense event $eventId")
                    }
                    if (DefensePhase.RECOVERY.name != resultSet.getString("state")) {
                        throw PersistenceConflictException(
                            "A raid seal may only be refunded during technical recovery",
                        )
                    }
                }
            }
        }

        private fun requireSealMutationArguments(
            sealId: UUID,
            eventId: UUID,
            ownerPlayerId: UUID,
            stageLevel: Long,
            operationId: UUID,
            timestamp: Instant,
        ) {
            Objects.requireNonNull(sealId, "sealId")
            Objects.requireNonNull(eventId, "eventId")
            Objects.requireNonNull(ownerPlayerId, "ownerPlayerId")
            Objects.requireNonNull(operationId, "operationId")
            Objects.requireNonNull(timestamp, "timestamp")
            StageWaveSchedule.requireValidStageLevel(stageLevel)
        }

        private fun deterministicOperation(eventId: UUID, kind: String): UUID =
            UUID.nameUUIDFromBytes((eventId.toString() + "|" + kind)
                .toByteArray(StandardCharsets.UTF_8))

        private fun optionalUuid(value: String?): Optional<UUID> =
            if (value == null) Optional.empty() else Optional.of(uuid(value))

        private fun uuid(value: String): UUID = UUID.fromString(value)

        private fun instant(value: String): Instant = Instant.parse(value)

        private fun failure(action: String, exception: SQLException): PersistenceException =
            PersistenceException("Could not $action", exception)

        private fun isConstraintViolation(exception: SQLException): Boolean {
            var current: SQLException? = exception
            while (current != null) {
                val message: String? = current.message
                if (current.getErrorCode() == 19
                    || (message != null
                        && message.lowercase(Locale.ROOT).contains("constraint"))
                ) {
                    return true
                }
                current = current.getNextException()
            }
            return false
        }

        private data class RaidSealReturn(val returnedSealId: UUID)
    }

    private fun <T> read(action: String, work: (Connection) -> T): T {
        try {
            database.openConnection().use { connection ->
                return work(connection)
            }
        } catch (exception: SQLException) {
            throw Companion.failure(action, exception)
        }
    }
}
