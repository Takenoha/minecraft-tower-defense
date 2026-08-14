package io.github.takenoha.towerdefense.persistence

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.ArrayList
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmStatic

/**
 * Persistence boundary for optional, team-bound point vouchers.
 *
 * Wallet mutations and voucher lifecycle changes are committed in one SQLite transaction. The
 * Paper bridge deliberately performs inventory mutations between the PREPARED and APPLIED rows,
 * so a process stop leaves a receipt that can be reconciled without trusting the item lore.
 */
class ResourceVoucherRepository(database: Database) {
    private val database: Database = Objects.requireNonNull(database, "database")

    /** Debits a wallet and creates one recipient-fixed PENDING_DELIVERY voucher atomically. */
    fun withdraw(
        teamId: UUID,
        actorId: UUID,
        resourceType: ResourceType,
        quantity: Long,
        withdrawalOperationId: UUID,
        issuedAt: Instant,
    ): VoucherWithdrawalResult {
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(actorId, "actorId")
        ResourceType.require(resourceType)
        Objects.requireNonNull(withdrawalOperationId, "withdrawalOperationId")
        Objects.requireNonNull(issuedAt, "issuedAt")
        require(quantity > 0L) { "voucher quantity must be positive" }
        val fingerprint = withdrawalFingerprint(teamId, actorId, resourceType, quantity)
        return try {
            database.inImmediateTransaction { connection ->
                val existing = loadVoucherByWithdrawal(connection, withdrawalOperationId)
                if (existing.isPresent) {
                    val voucher = existing.orElseThrow()
                    requireMatchingWithdrawal(
                        voucher,
                        teamId,
                        actorId,
                        resourceType,
                        quantity,
                        fingerprint,
                    )
                    return@inImmediateTransaction VoucherWithdrawalResult(
                        OperationOutcome.ALREADY_APPLIED,
                        voucher,
                    )
                }
                requireTeamOwner(connection, teamId, actorId)
                requireNoActiveEvent(connection, "withdraw a resource voucher")
                requireNoPreparedCorePlacement(connection, teamId)
                val voucherId = deterministic(
                    withdrawalOperationId,
                    "VOUCHER",
                    resourceType.name,
                )
                ResourceRepository.debitInTransaction(
                    connection,
                    teamId,
                    actorId,
                    resourceType,
                    quantity,
                    deterministic(
                        withdrawalOperationId,
                        "WALLET_DEBIT",
                        resourceType.name,
                    ),
                    withdrawalOperationId.toString(),
                    fingerprint,
                    issuedAt,
                )
                val voucher = ResourceVoucher(
                    voucherId,
                    withdrawalOperationId,
                    teamId,
                    resourceType,
                    quantity,
                    ResourceVoucherState.PENDING_DELIVERY,
                    actorId,
                    fingerprint,
                    issuedAt,
                    null,
                    null,
                    null,
                    null,
                )
                insertVoucher(connection, voucher)
                VoucherWithdrawalResult(OperationOutcome.APPLIED, voucher)
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The voucher withdrawal conflicts with persisted data",
                    exception,
                )
            }
            throw failure("withdraw a resource voucher", exception)
        }
    }

    fun findVoucher(voucherId: UUID): Optional<ResourceVoucher> {
        Objects.requireNonNull(voucherId, "voucherId")
        return read("load a resource voucher") { connection ->
            loadVoucher(connection, voucherId)
        }
    }

    fun loadPendingDeliveries(recipientPlayerId: UUID): List<ResourceVoucher> {
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId")
        return read("load pending voucher deliveries") { connection ->
            val vouchers = ArrayList<ResourceVoucher>()
            connection.prepareStatement(
                """
                SELECT voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                       state, delivery_recipient_player_id, payload_fingerprint, issued_at,
                       available_at, reserved_at, redeemed_at, voided_at
                FROM resource_vouchers
                WHERE delivery_recipient_player_id = ? AND state = 'PENDING_DELIVERY'
                ORDER BY issued_at, voucher_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, recipientPlayerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        vouchers.add(voucherFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(vouchers)
        }
    }

    fun loadOpenDeliveryOperations(recipientPlayerId: UUID): List<VoucherDeliveryOperation> {
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId")
        return read("load open voucher deliveries") { connection ->
            val operations = ArrayList<VoucherDeliveryOperation>()
            connection.prepareStatement(
                """
                SELECT delivery_operation_id, voucher_id, recipient_player_id,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_delivery_operations
                WHERE recipient_player_id = ? AND state IN ('PREPARED', 'APPLIED')
                ORDER BY prepared_at, delivery_operation_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, recipientPlayerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        operations.add(deliveryFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(operations)
        }
    }

    fun findDeliveryOperation(operationId: UUID): Optional<VoucherDeliveryOperation> {
        Objects.requireNonNull(operationId, "operationId")
        return read("load a voucher delivery operation") { connection ->
            loadDeliveryOperation(connection, operationId)
        }
    }

    /** Creates or reuses a delivery PREPARED row; the recipient is fixed by the voucher row. */
    fun prepareDelivery(
        voucherId: UUID,
        recipientPlayerId: UUID,
        deliveryOperationId: UUID,
        preparedAt: Instant,
    ): VoucherDeliveryResult {
        Objects.requireNonNull(voucherId, "voucherId")
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId")
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId")
        Objects.requireNonNull(preparedAt, "preparedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val voucher = loadVoucher(connection, voucherId).orElseThrow {
                    PersistenceConflictException("The voucher does not exist")
                }
                requireDeliveryRecipient(voucher, recipientPlayerId)
                val fingerprint = deliveryFingerprint(voucher, recipientPlayerId)
                val existing = loadDeliveryOperation(connection, deliveryOperationId)
                if (existing.isPresent) {
                    val operation = existing.orElseThrow()
                    requireMatchingDelivery(
                        operation,
                        voucherId,
                        recipientPlayerId,
                        fingerprint,
                    )
                    return@inImmediateTransaction when (operation.state()) {
                        VoucherDeliveryState.PREPARED -> VoucherDeliveryResult(
                            VoucherDeliveryOutcome.ALREADY_PREPARED,
                            voucher,
                            operation,
                        )
                        VoucherDeliveryState.APPLIED -> VoucherDeliveryResult(
                            VoucherDeliveryOutcome.ALREADY_AVAILABLE,
                            voucher,
                            operation,
                        )
                        VoucherDeliveryState.ROLLED_BACK -> {
                            if (voucher.state() != ResourceVoucherState.PENDING_DELIVERY) {
                                throw PersistenceConflictException(
                                    "The rolled-back delivery no longer targets a pending voucher",
                                )
                            }
                            resetDeliveryState(connection, deliveryOperationId, preparedAt)
                            VoucherDeliveryResult(
                                VoucherDeliveryOutcome.PREPARED,
                                voucher,
                                loadDeliveryOperation(
                                    connection,
                                    deliveryOperationId,
                                ).orElseThrow(),
                            )
                        }
                    }
                }
                if (voucher.state() == ResourceVoucherState.AVAILABLE) {
                    return@inImmediateTransaction VoucherDeliveryResult(
                        VoucherDeliveryOutcome.ALREADY_AVAILABLE,
                        voucher,
                        null,
                    )
                }
                if (voucher.state() == ResourceVoucherState.VOIDED) {
                    return@inImmediateTransaction VoucherDeliveryResult(
                        VoucherDeliveryOutcome.VOIDED,
                        voucher,
                        null,
                    )
                }
                if (voucher.state() != ResourceVoucherState.PENDING_DELIVERY) {
                    throw PersistenceConflictException(
                        "The voucher is not available for delivery",
                    )
                }
                val prepared = loadPreparedDeliveryForVoucher(connection, voucherId)
                if (prepared.isPresent) {
                    return@inImmediateTransaction VoucherDeliveryResult(
                        VoucherDeliveryOutcome.ALREADY_PREPARED,
                        voucher,
                        prepared.orElseThrow(),
                    )
                }
                val operation = VoucherDeliveryOperation(
                    deliveryOperationId,
                    voucherId,
                    recipientPlayerId,
                    fingerprint,
                    VoucherDeliveryState.PREPARED,
                    preparedAt,
                    null,
                    null,
                )
                insertDeliveryOperation(connection, operation)
                VoucherDeliveryResult(VoucherDeliveryOutcome.PREPARED, voucher, operation)
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The voucher delivery conflicts with persisted data",
                    exception,
                )
            }
            throw failure("prepare voucher delivery", exception)
        }
    }

    /** Commits the PENDING_DELIVERY -> AVAILABLE transition after the tagged item is inserted. */
    fun applyDelivery(
        voucherId: UUID,
        deliveryOperationId: UUID,
        appliedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(voucherId, "voucherId")
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val operation = loadDeliveryOperation(connection, deliveryOperationId).orElseThrow {
                    PersistenceConflictException("The voucher delivery operation does not exist")
                }
                if (!operation.voucherId().equals(voucherId)) {
                    throw PersistenceConflictException(
                        "The voucher delivery operation targets another voucher",
                    )
                }
                if (operation.state() == VoucherDeliveryState.APPLIED) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (operation.state() == VoucherDeliveryState.ROLLED_BACK) {
                    throw PersistenceConflictException(
                        "The voucher delivery operation was rolled back",
                    )
                }
                val voucher = loadVoucher(connection, voucherId).orElseThrow {
                    PersistenceConflictException("The voucher does not exist")
                }
                if (voucher.state() == ResourceVoucherState.VOIDED) {
                    throw PersistenceConflictException("The voucher was voided")
                }
                if (voucher.state() == ResourceVoucherState.PENDING_DELIVERY) {
                    updateVoucherAvailable(connection, voucherId, appliedAt)
                } else if (voucher.state() != ResourceVoucherState.AVAILABLE) {
                    throw PersistenceConflictException(
                        "The voucher cannot become available from state " + voucher.state(),
                    )
                }
                updateDeliveryState(
                    connection,
                    deliveryOperationId,
                    VoucherDeliveryState.APPLIED,
                    appliedAt,
                )
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("apply voucher delivery", exception)
        }
    }

    /** Rolls back only a PREPARED delivery receipt; it never credits the wallet automatically. */
    fun rollbackDelivery(
        deliveryOperationId: UUID,
        rolledBackAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId")
        Objects.requireNonNull(rolledBackAt, "rolledBackAt")
        return try {
            database.inImmediateTransaction { connection ->
                val operation = loadDeliveryOperation(connection, deliveryOperationId).orElseThrow {
                    PersistenceConflictException("The voucher delivery operation does not exist")
                }
                if (operation.state() == VoucherDeliveryState.ROLLED_BACK) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (operation.state() == VoucherDeliveryState.APPLIED) {
                    throw PersistenceConflictException(
                        "An applied voucher delivery cannot be rolled back",
                    )
                }
                updateDeliveryState(
                    connection,
                    deliveryOperationId,
                    VoucherDeliveryState.ROLLED_BACK,
                    rolledBackAt,
                )
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("roll back voucher delivery", exception)
        }
    }

    fun loadOpenRedeems(actorId: UUID): List<VoucherRedeemOperation> {
        Objects.requireNonNull(actorId, "actorId")
        return loadRedeems(actorId, false)
    }

    /** Loads open and rolled-back operations whose physical receipt may need reconciliation. */
    fun loadRedeemsForRecovery(actorId: UUID): List<VoucherRedeemOperation> {
        Objects.requireNonNull(actorId, "actorId")
        return loadRedeems(actorId, true)
    }

    private fun loadRedeems(
        actorId: UUID,
        includeRolledBack: Boolean,
    ): List<VoucherRedeemOperation> {
        val states = if (includeRolledBack) {
            "'PREPARED', 'APPLIED', 'ROLLED_BACK'"
        } else {
            "'PREPARED', 'APPLIED'"
        }
        return read("load voucher redeems for recovery") { connection ->
            val operations = ArrayList<VoucherRedeemOperation>()
            connection.prepareStatement(
                """
                SELECT operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_redeem_operations
                WHERE actor_id = ? AND state IN (%s)
                ORDER BY prepared_at, operation_id
                """.trimIndent().format(states),
            ).use { statement ->
                statement.setString(1, actorId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        operations.add(redeemFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(operations)
        }
    }

    fun findRedeemOperation(operationId: UUID): Optional<VoucherRedeemOperation> {
        Objects.requireNonNull(operationId, "operationId")
        return read("load a voucher redeem operation") { connection ->
            loadRedeemOperation(connection, operationId)
        }
    }

    /** Reserves an AVAILABLE voucher before its physical item receives a redeem receipt. */
    fun prepareRedeem(
        voucherId: UUID,
        actorId: UUID,
        operationId: UUID,
        preparedAt: Instant,
    ): VoucherRedeemResult {
        Objects.requireNonNull(voucherId, "voucherId")
        Objects.requireNonNull(actorId, "actorId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(preparedAt, "preparedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val voucher = loadVoucher(connection, voucherId).orElseThrow {
                    PersistenceConflictException("The voucher does not exist")
                }
                val fingerprint = redeemFingerprint(voucher, actorId)
                val existing = loadRedeemOperation(connection, operationId)
                if (existing.isPresent) {
                    val operation = existing.orElseThrow()
                    requireMatchingRedeem(operation, voucher, actorId, fingerprint)
                    if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
                        throw PersistenceConflictException(
                            "The voucher redeem operation was rolled back; use a new operation UUID",
                        )
                    }
                    return@inImmediateTransaction VoucherRedeemResult(
                        if (operation.state() == VoucherRedeemState.APPLIED) {
                            OperationOutcome.ALREADY_APPLIED
                        } else {
                            OperationOutcome.APPLIED
                        },
                        voucher,
                        operation,
                    )
                }
                requireNoActiveEvent(connection, "redeem a resource voucher")
                requireNoPreparedCorePlacement(connection, voucher.teamId())
                ResourceRepository.requireTeamMember(connection, voucher.teamId(), actorId)
                if (voucher.state() == ResourceVoucherState.REDEEMED
                    || voucher.state() == ResourceVoucherState.VOIDED
                ) {
                    throw PersistenceConflictException("The voucher is no longer redeemable")
                }
                if (voucher.state() == ResourceVoucherState.RESERVED) {
                    val prepared = loadPreparedRedeemForVoucher(connection, voucherId)
                    if (prepared.isPresent) {
                        throw PersistenceConflictException(
                            "The voucher is already reserved by another operation",
                        )
                    }
                }
                if (voucher.state() != ResourceVoucherState.AVAILABLE) {
                    throw PersistenceConflictException("The voucher is not available for deposit")
                }
                val operation = VoucherRedeemOperation(
                    operationId,
                    voucherId,
                    voucher.teamId(),
                    actorId,
                    voucher.resourceType(),
                    voucher.quantity(),
                    fingerprint,
                    VoucherRedeemState.PREPARED,
                    preparedAt,
                    null,
                    null,
                )
                insertRedeemOperation(connection, operation)
                updateVoucherReserved(connection, voucherId, preparedAt)
                VoucherRedeemResult(
                    OperationOutcome.APPLIED,
                    loadVoucher(connection, voucherId).orElseThrow(),
                    operation,
                )
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The voucher redeem conflicts with persisted data",
                    exception,
                )
            }
            throw failure("prepare voucher redeem", exception)
        }
    }

    /** Credits the team wallet and marks the voucher REDEEMED in one SQLite transaction. */
    fun applyRedeem(operationId: UUID, appliedAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val operation = loadRedeemOperation(connection, operationId).orElseThrow {
                    PersistenceConflictException("The voucher redeem operation does not exist")
                }
                if (operation.state() == VoucherRedeemState.APPLIED) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
                    throw PersistenceConflictException("The voucher redeem was rolled back")
                }
                requireNoActiveEvent(connection, "apply a resource voucher redeem")
                requireNoPreparedCorePlacement(connection, operation.teamId())
                ResourceRepository.requireTeamMember(
                    connection,
                    operation.teamId(),
                    operation.actorId(),
                )
                val voucher = loadVoucher(connection, operation.voucherId()).orElseThrow {
                    PersistenceConflictException("The voucher does not exist")
                }
                if (voucher.state() != ResourceVoucherState.RESERVED
                    || !voucher.teamId().equals(operation.teamId())
                    || voucher.resourceType() != operation.resourceType()
                    || voucher.quantity() != operation.quantity()
                ) {
                    throw PersistenceConflictException(
                        "The voucher no longer matches its redeem operation",
                    )
                }
                ResourceRepository.creditInTransaction(
                    connection,
                    operation.teamId(),
                    operation.resourceType(),
                    operation.quantity(),
                    operation.operationId(),
                    "VOUCHER_REDEEM|" + operation.voucherId(),
                    operation.payloadFingerprint(),
                    appliedAt,
                )
                updateVoucherRedeemed(connection, operation.voucherId(), appliedAt)
                updateRedeemState(
                    connection,
                    operationId,
                    VoucherRedeemState.APPLIED,
                    appliedAt,
                )
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("apply a resource voucher redeem", exception)
        }
    }

    /** Releases a RESERVED voucher only when the physical receipt was not applied. */
    fun rollbackRedeem(operationId: UUID, rolledBackAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(rolledBackAt, "rolledBackAt")
        return try {
            database.inImmediateTransaction { connection ->
                val operation = loadRedeemOperation(connection, operationId).orElseThrow {
                    PersistenceConflictException("The voucher redeem operation does not exist")
                }
                if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (operation.state() == VoucherRedeemState.APPLIED) {
                    throw PersistenceConflictException(
                        "An applied voucher redeem cannot be rolled back",
                    )
                }
                val voucher = loadVoucher(connection, operation.voucherId()).orElseThrow {
                    PersistenceConflictException("The voucher does not exist")
                }
                if (voucher.state() == ResourceVoucherState.RESERVED) {
                    updateVoucherAvailable(connection, operation.voucherId(), rolledBackAt)
                }
                updateRedeemState(
                    connection,
                    operationId,
                    VoucherRedeemState.ROLLED_BACK,
                    rolledBackAt,
                )
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("roll back a resource voucher redeem", exception)
        }
    }

    /** Used by team disbanding to reject orphaning a live voucher. */
    fun hasLiveVouchers(teamId: UUID): Boolean {
        Objects.requireNonNull(teamId, "teamId")
        return read("check live resource vouchers") { connection ->
            hasLiveVouchers(connection, teamId)
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
        @JvmStatic
        @Throws(SQLException::class)
        fun hasLiveVouchers(connection: Connection, teamId: UUID): Boolean {
            connection.prepareStatement(
                """
                SELECT 1 FROM resource_vouchers
                WHERE team_id = ? AND state IN ('PENDING_DELIVERY', 'AVAILABLE', 'RESERVED')
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, teamId.toString())
                statement.executeQuery().use { resultSet ->
                    return resultSet.next()
                }
            }
        }

        private fun withdrawalFingerprint(
            teamId: UUID,
            actorId: UUID,
            resourceType: ResourceType,
            quantity: Long,
        ): String = sha256(
            "VOUCHER_WITHDRAW|$teamId|$actorId|$resourceType|$quantity",
        )

        private fun deliveryFingerprint(
            voucher: ResourceVoucher,
            recipientId: UUID,
        ): String = sha256(
            "VOUCHER_DELIVERY|${voucher.voucherId()}|${voucher.teamId()}|$recipientId|"
                + "${voucher.resourceType()}|${voucher.quantity()}",
        )

        private fun redeemFingerprint(
            voucher: ResourceVoucher,
            actorId: UUID,
        ): String = sha256(
            "VOUCHER_REDEEM|${voucher.voucherId()}|${voucher.teamId()}|$actorId|"
                + "${voucher.resourceType()}|${voucher.quantity()}",
        )

        private fun deterministic(base: UUID, namespace: String, value: String): UUID =
            UUID.nameUUIDFromBytes(
                "$base|$namespace|$value".toByteArray(StandardCharsets.UTF_8),
            )

        private fun requireMatchingWithdrawal(
            voucher: ResourceVoucher,
            teamId: UUID,
            actorId: UUID,
            resourceType: ResourceType,
            quantity: Long,
            fingerprint: String,
        ) {
            if (!voucher.teamId().equals(teamId)
                || !voucher.deliveryRecipientPlayerId().equals(actorId)
                || voucher.resourceType() != resourceType
                || voucher.quantity() != quantity
                || !voucher.payloadFingerprint().equals(fingerprint)
            ) {
                throw PersistenceConflictException(
                    "The voucher withdrawal operation UUID is already assigned to another payload",
                )
            }
        }

        private fun requireDeliveryRecipient(voucher: ResourceVoucher, recipientId: UUID) {
            if (!voucher.deliveryRecipientPlayerId().equals(recipientId)) {
                throw PersistenceConflictException(
                    "A voucher can only be delivered to its original recipient",
                )
            }
        }

        private fun requireMatchingDelivery(
            operation: VoucherDeliveryOperation,
            voucherId: UUID,
            recipientId: UUID,
            fingerprint: String,
        ) {
            if (!operation.voucherId().equals(voucherId)
                || !operation.recipientPlayerId().equals(recipientId)
                || !operation.payloadFingerprint().equals(fingerprint)
            ) {
                throw PersistenceConflictException(
                    "The voucher delivery operation UUID is already assigned to another payload",
                )
            }
        }

        private fun requireMatchingRedeem(
            operation: VoucherRedeemOperation,
            voucher: ResourceVoucher,
            actorId: UUID,
            fingerprint: String,
        ) {
            if (!operation.voucherId().equals(voucher.voucherId())
                || !operation.teamId().equals(voucher.teamId())
                || !operation.actorId().equals(actorId)
                || operation.resourceType() != voucher.resourceType()
                || operation.quantity() != voucher.quantity()
                || !operation.payloadFingerprint().equals(fingerprint)
            ) {
                throw PersistenceConflictException(
                    "The voucher redeem operation UUID is already assigned to another payload",
                )
            }
        }

        private fun requireTeamOwner(
            connection: Connection,
            teamId: UUID,
            actorId: UUID,
        ) {
            connection.prepareStatement(
                """
                SELECT owner_player_id FROM teams WHERE team_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, teamId.toString())
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        throw PersistenceConflictException("The team does not exist")
                    }
                    if (actorId.toString() != resultSet.getString(1)) {
                        throw PersistenceConflictException(
                            "Only the team owner may withdraw resource vouchers",
                        )
                    }
                }
            }
        }

        private fun requireNoActiveEvent(connection: Connection, operation: String) {
            connection.prepareStatement("SELECT 1 FROM event_lock LIMIT 1").use { statement ->
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        throw PersistenceConflictException(
                            "Cannot $operation while a defense event is active",
                        )
                    }
                }
            }
        }

        private fun requireNoPreparedCorePlacement(connection: Connection, teamId: UUID) {
            connection.prepareStatement(
                """
                SELECT 1 FROM core_placement_operations
                WHERE team_id = ? AND state = 'PREPARED' LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, teamId.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        throw PersistenceConflictException(
                            "Cannot change resource vouchers while core placement is processing",
                        )
                    }
                }
            }
        }

        private fun loadPreparedDeliveryForVoucher(
            connection: Connection,
            voucherId: UUID,
        ): Optional<VoucherDeliveryOperation> {
            connection.prepareStatement(
                """
                SELECT delivery_operation_id, voucher_id, recipient_player_id,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_delivery_operations
                WHERE voucher_id = ? AND state = 'PREPARED'
                ORDER BY prepared_at DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, voucherId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(deliveryFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadPreparedRedeemForVoucher(
            connection: Connection,
            voucherId: UUID,
        ): Optional<VoucherRedeemOperation> {
            connection.prepareStatement(
                """
                SELECT operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_redeem_operations
                WHERE voucher_id = ? AND state = 'PREPARED'
                ORDER BY prepared_at DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, voucherId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(redeemFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun insertVoucher(connection: Connection, voucher: ResourceVoucher) {
            connection.prepareStatement(
                """
                INSERT INTO resource_vouchers(
                    voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                    state, delivery_recipient_player_id, payload_fingerprint, issued_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING_DELIVERY', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, voucher.voucherId().toString())
                statement.setString(2, voucher.withdrawalOperationId().toString())
                statement.setString(3, voucher.teamId().toString())
                statement.setString(4, voucher.resourceType().name)
                statement.setLong(5, voucher.quantity())
                statement.setString(6, voucher.deliveryRecipientPlayerId().toString())
                statement.setString(7, voucher.payloadFingerprint())
                statement.setString(8, voucher.issuedAt().toString())
                statement.executeUpdate()
            }
        }

        private fun insertDeliveryOperation(
            connection: Connection,
            operation: VoucherDeliveryOperation,
        ) {
            connection.prepareStatement(
                """
                INSERT INTO resource_voucher_delivery_operations(
                    delivery_operation_id, voucher_id, recipient_player_id, payload_fingerprint,
                    state, prepared_at)
                VALUES (?, ?, ?, ?, 'PREPARED', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operation.deliveryOperationId().toString())
                statement.setString(2, operation.voucherId().toString())
                statement.setString(3, operation.recipientPlayerId().toString())
                statement.setString(4, operation.payloadFingerprint())
                statement.setString(5, operation.preparedAt().toString())
                statement.executeUpdate()
            }
        }

        private fun insertRedeemOperation(
            connection: Connection,
            operation: VoucherRedeemOperation,
        ) {
            connection.prepareStatement(
                """
                INSERT INTO resource_voucher_redeem_operations(
                    operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                    payload_fingerprint, state, prepared_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operation.operationId().toString())
                statement.setString(2, operation.voucherId().toString())
                statement.setString(3, operation.teamId().toString())
                statement.setString(4, operation.actorId().toString())
                statement.setString(5, operation.resourceType().name)
                statement.setLong(6, operation.quantity())
                statement.setString(7, operation.payloadFingerprint())
                statement.setString(8, operation.preparedAt().toString())
                statement.executeUpdate()
            }
        }

        private fun updateVoucherAvailable(
            connection: Connection,
            voucherId: UUID,
            at: Instant,
        ) {
            connection.prepareStatement(
                """
                UPDATE resource_vouchers
                SET state = 'AVAILABLE', available_at = ?, reserved_at = NULL,
                    redeemed_at = NULL, voided_at = NULL
                WHERE voucher_id = ? AND state IN ('PENDING_DELIVERY', 'RESERVED')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, at.toString())
                statement.setString(2, voucherId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The voucher is not releasable")
                }
            }
        }

        private fun updateVoucherReserved(
            connection: Connection,
            voucherId: UUID,
            at: Instant,
        ) {
            connection.prepareStatement(
                """
                UPDATE resource_vouchers
                SET state = 'RESERVED', reserved_at = ?
                WHERE voucher_id = ? AND state = 'AVAILABLE'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, at.toString())
                statement.setString(2, voucherId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The voucher is no longer available")
                }
            }
        }

        private fun updateVoucherRedeemed(
            connection: Connection,
            voucherId: UUID,
            at: Instant,
        ) {
            connection.prepareStatement(
                """
                UPDATE resource_vouchers
                SET state = 'REDEEMED', redeemed_at = ?
                WHERE voucher_id = ? AND state = 'RESERVED'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, at.toString())
                statement.setString(2, voucherId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The voucher is no longer reserved")
                }
            }
        }

        private fun updateDeliveryState(
            connection: Connection,
            operationId: UUID,
            state: VoucherDeliveryState,
            at: Instant,
        ) {
            val sql = if (state == VoucherDeliveryState.APPLIED) {
                """
                UPDATE resource_voucher_delivery_operations
                SET state = 'APPLIED', applied_at = ?
                WHERE delivery_operation_id = ? AND state = 'PREPARED'
                """.trimIndent()
            } else {
                """
                UPDATE resource_voucher_delivery_operations
                SET state = 'ROLLED_BACK', rolled_back_at = ?
                WHERE delivery_operation_id = ? AND state = 'PREPARED'
                """.trimIndent()
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, at.toString())
                statement.setString(2, operationId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The voucher delivery state changed")
                }
            }
        }

        private fun resetDeliveryState(
            connection: Connection,
            operationId: UUID,
            preparedAt: Instant,
        ) {
            connection.prepareStatement(
                """
                UPDATE resource_voucher_delivery_operations
                SET state = 'PREPARED', prepared_at = ?, applied_at = NULL, rolled_back_at = NULL
                WHERE delivery_operation_id = ? AND state = 'ROLLED_BACK'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, preparedAt.toString())
                statement.setString(2, operationId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The voucher delivery state changed")
                }
            }
        }

        private fun updateRedeemState(
            connection: Connection,
            operationId: UUID,
            state: VoucherRedeemState,
            at: Instant,
        ) {
            val sql = if (state == VoucherRedeemState.APPLIED) {
                """
                UPDATE resource_voucher_redeem_operations
                SET state = 'APPLIED', applied_at = ?
                WHERE operation_id = ? AND state = 'PREPARED'
                """.trimIndent()
            } else {
                """
                UPDATE resource_voucher_redeem_operations
                SET state = 'ROLLED_BACK', rolled_back_at = ?
                WHERE operation_id = ? AND state = 'PREPARED'
                """.trimIndent()
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, at.toString())
                statement.setString(2, operationId.toString())
                if (statement.executeUpdate() != 1) {
                    throw PersistenceConflictException("The voucher redeem state changed")
                }
            }
        }

        private fun loadVoucherByWithdrawal(
            connection: Connection,
            withdrawalOperationId: UUID,
        ): Optional<ResourceVoucher> {
            connection.prepareStatement(
                """
                SELECT voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                       state, delivery_recipient_player_id, payload_fingerprint, issued_at,
                       available_at, reserved_at, redeemed_at, voided_at
                FROM resource_vouchers WHERE withdrawal_operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, withdrawalOperationId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(voucherFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadVoucher(
            connection: Connection,
            voucherId: UUID,
        ): Optional<ResourceVoucher> {
            connection.prepareStatement(
                """
                SELECT voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                       state, delivery_recipient_player_id, payload_fingerprint, issued_at,
                       available_at, reserved_at, redeemed_at, voided_at
                FROM resource_vouchers WHERE voucher_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, voucherId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(voucherFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadDeliveryOperation(
            connection: Connection,
            operationId: UUID,
        ): Optional<VoucherDeliveryOperation> {
            connection.prepareStatement(
                """
                SELECT delivery_operation_id, voucher_id, recipient_player_id,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_delivery_operations
                WHERE delivery_operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(deliveryFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun loadRedeemOperation(
            connection: Connection,
            operationId: UUID,
        ): Optional<VoucherRedeemOperation> {
            connection.prepareStatement(
                """
                SELECT operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_redeem_operations WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) {
                        Optional.of(redeemFromRow(resultSet))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }

        private fun voucherFromRow(resultSet: ResultSet): ResourceVoucher = ResourceVoucher(
            uuid(resultSet.getString("voucher_id")),
            uuid(resultSet.getString("withdrawal_operation_id")),
            uuid(resultSet.getString("team_id")),
            ResourceType.valueOf(resultSet.getString("resource_type")),
            resultSet.getLong("quantity"),
            ResourceVoucherState.valueOf(resultSet.getString("state")),
            uuid(resultSet.getString("delivery_recipient_player_id")),
            resultSet.getString("payload_fingerprint"),
            instant(resultSet.getString("issued_at")),
            nullableInstant(resultSet.getString("available_at")),
            nullableInstant(resultSet.getString("reserved_at")),
            nullableInstant(resultSet.getString("redeemed_at")),
            nullableInstant(resultSet.getString("voided_at")),
        )

        private fun deliveryFromRow(resultSet: ResultSet): VoucherDeliveryOperation =
            VoucherDeliveryOperation(
                uuid(resultSet.getString("delivery_operation_id")),
                uuid(resultSet.getString("voucher_id")),
                uuid(resultSet.getString("recipient_player_id")),
                resultSet.getString("payload_fingerprint"),
                VoucherDeliveryState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")),
            )

        private fun redeemFromRow(resultSet: ResultSet): VoucherRedeemOperation =
            VoucherRedeemOperation(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("voucher_id")),
                uuid(resultSet.getString("team_id")),
                uuid(resultSet.getString("actor_id")),
                ResourceType.valueOf(resultSet.getString("resource_type")),
                resultSet.getLong("quantity"),
                resultSet.getString("payload_fingerprint"),
                VoucherRedeemState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")),
            )

        private fun uuid(value: String): UUID = UUID.fromString(value)

        private fun instant(value: String): Instant = Instant.parse(value)

        private fun nullableInstant(value: String?): Instant? =
            if (value == null) null else Instant.parse(value)

        private fun sha256(value: String): String {
            return try {
                java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (exception: NoSuchAlgorithmException) {
                throw AssertionError("SHA-256 is required by the JDK", exception)
            }
        }

        private fun isConstraintViolation(exception: SQLException): Boolean {
            val message = exception.message
            return message != null
                && (message.contains("UNIQUE")
                    || message.contains("constraint")
                    || message.contains("CHECK"))
        }

        private fun failure(action: String, exception: SQLException): PersistenceException =
            PersistenceException("Could not $action", exception)
    }
}
