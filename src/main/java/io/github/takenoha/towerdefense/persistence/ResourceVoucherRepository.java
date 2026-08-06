package io.github.takenoha.towerdefense.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/**
 * Persistence boundary for optional, team-bound point vouchers.
 *
 * <p>Wallet mutations and voucher lifecycle changes are committed in one SQLite transaction. The
 * Paper bridge deliberately performs inventory mutations between the PREPARED and APPLIED rows,
 * so a process stop leaves a receipt that can be reconciled without trusting the item lore.</p>
 */
public final class ResourceVoucherRepository {
    private final Database database;

    public ResourceVoucherRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Debits a wallet and creates one recipient-fixed PENDING_DELIVERY voucher atomically. */
    public VoucherWithdrawalResult withdraw(
            UUID teamId,
            UUID actorId,
            ResourceType resourceType,
            long quantity,
            UUID withdrawalOperationId,
            Instant issuedAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        ResourceType.require(resourceType);
        Objects.requireNonNull(withdrawalOperationId, "withdrawalOperationId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (quantity <= 0L) {
            throw new IllegalArgumentException("voucher quantity must be positive");
        }
        String fingerprint = withdrawalFingerprint(teamId, actorId, resourceType, quantity);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<ResourceVoucher> existing = loadVoucherByWithdrawal(
                        connection, withdrawalOperationId);
                if (existing.isPresent()) {
                    ResourceVoucher voucher = existing.orElseThrow();
                    requireMatchingWithdrawal(
                            voucher, teamId, actorId, resourceType, quantity, fingerprint);
                    return new VoucherWithdrawalResult(OperationOutcome.ALREADY_APPLIED, voucher);
                }
                requireTeamOwner(connection, teamId, actorId);
                requireNoActiveEvent(connection, "withdraw a resource voucher");
                requireNoPreparedCorePlacement(connection, teamId);
                UUID voucherId = deterministic(withdrawalOperationId, "VOUCHER", resourceType.name());
                ResourceRepository.debitInTransaction(
                        connection,
                        teamId,
                        actorId,
                        resourceType,
                        quantity,
                        deterministic(withdrawalOperationId, "WALLET_DEBIT", resourceType.name()),
                        withdrawalOperationId.toString(),
                        fingerprint,
                        issuedAt);
                ResourceVoucher voucher = new ResourceVoucher(
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
                        null);
                insertVoucher(connection, voucher);
                return new VoucherWithdrawalResult(OperationOutcome.APPLIED, voucher);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The voucher withdrawal conflicts with persisted data", exception);
            }
            throw failure("withdraw a resource voucher", exception);
        }
    }

    public Optional<ResourceVoucher> findVoucher(UUID voucherId) {
        Objects.requireNonNull(voucherId, "voucherId");
        return read("load a resource voucher", connection -> loadVoucher(connection, voucherId));
    }

    public List<ResourceVoucher> loadPendingDeliveries(UUID recipientPlayerId) {
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        return read("load pending voucher deliveries", connection -> {
            List<ResourceVoucher> vouchers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                           state, delivery_recipient_player_id, payload_fingerprint, issued_at,
                           available_at, reserved_at, redeemed_at, voided_at
                    FROM resource_vouchers
                    WHERE delivery_recipient_player_id = ? AND state = 'PENDING_DELIVERY'
                    ORDER BY issued_at, voucher_id
                    """)) {
                statement.setString(1, recipientPlayerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        vouchers.add(voucherFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(vouchers);
        });
    }

    public List<VoucherDeliveryOperation> loadOpenDeliveryOperations(UUID recipientPlayerId) {
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        return read("load open voucher deliveries", connection -> {
            List<VoucherDeliveryOperation> operations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT delivery_operation_id, voucher_id, recipient_player_id,
                           payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                    FROM resource_voucher_delivery_operations
                    WHERE recipient_player_id = ? AND state IN ('PREPARED', 'APPLIED')
                    ORDER BY prepared_at, delivery_operation_id
                    """)) {
                statement.setString(1, recipientPlayerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        operations.add(deliveryFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(operations);
        });
    }

    public Optional<VoucherDeliveryOperation> findDeliveryOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return read("load a voucher delivery operation", connection ->
                loadDeliveryOperation(connection, operationId));
    }

    /** Creates or reuses a delivery PREPARED row; the recipient is fixed by the voucher row. */
    public VoucherDeliveryResult prepareDelivery(
            UUID voucherId,
            UUID recipientPlayerId,
            UUID deliveryOperationId,
            Instant preparedAt) {
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                ResourceVoucher voucher = loadVoucher(connection, voucherId).orElseThrow(
                        () -> new PersistenceConflictException("The voucher does not exist"));
                requireDeliveryRecipient(voucher, recipientPlayerId);
                String fingerprint = deliveryFingerprint(voucher, recipientPlayerId);
                Optional<VoucherDeliveryOperation> existing = loadDeliveryOperation(
                        connection, deliveryOperationId);
                if (existing.isPresent()) {
                    VoucherDeliveryOperation operation = existing.orElseThrow();
                    requireMatchingDelivery(
                            operation, voucherId, recipientPlayerId, fingerprint);
                    return switch (operation.state()) {
                        case PREPARED -> new VoucherDeliveryResult(
                                VoucherDeliveryOutcome.ALREADY_PREPARED, voucher, operation);
                        case APPLIED -> new VoucherDeliveryResult(
                                VoucherDeliveryOutcome.ALREADY_AVAILABLE, voucher, operation);
                        case ROLLED_BACK -> {
                            if (voucher.state() != ResourceVoucherState.PENDING_DELIVERY) {
                                throw new PersistenceConflictException(
                                        "The rolled-back delivery no longer targets a pending voucher");
                            }
                            resetDeliveryState(connection, deliveryOperationId, preparedAt);
                            yield new VoucherDeliveryResult(
                                    VoucherDeliveryOutcome.PREPARED,
                                    voucher,
                                    loadDeliveryOperation(connection, deliveryOperationId).orElseThrow());
                        }
                    };
                }
                if (voucher.state() == ResourceVoucherState.AVAILABLE) {
                    return new VoucherDeliveryResult(
                            VoucherDeliveryOutcome.ALREADY_AVAILABLE, voucher, null);
                }
                if (voucher.state() == ResourceVoucherState.VOIDED) {
                    return new VoucherDeliveryResult(VoucherDeliveryOutcome.VOIDED, voucher, null);
                }
                if (voucher.state() != ResourceVoucherState.PENDING_DELIVERY) {
                    throw new PersistenceConflictException(
                            "The voucher is not available for delivery");
                }
                Optional<VoucherDeliveryOperation> prepared = loadPreparedDeliveryForVoucher(
                        connection, voucherId);
                if (prepared.isPresent()) {
                    return new VoucherDeliveryResult(
                            VoucherDeliveryOutcome.ALREADY_PREPARED,
                            voucher,
                            prepared.orElseThrow());
                }
                VoucherDeliveryOperation operation = new VoucherDeliveryOperation(
                        deliveryOperationId,
                        voucherId,
                        recipientPlayerId,
                        fingerprint,
                        VoucherDeliveryState.PREPARED,
                        preparedAt,
                        null,
                        null);
                insertDeliveryOperation(connection, operation);
                return new VoucherDeliveryResult(
                        VoucherDeliveryOutcome.PREPARED, voucher, operation);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The voucher delivery conflicts with persisted data", exception);
            }
            throw failure("prepare voucher delivery", exception);
        }
    }

    /** Commits the PENDING_DELIVERY -> AVAILABLE transition after the tagged item is inserted. */
    public OperationOutcome applyDelivery(UUID voucherId, UUID deliveryOperationId, Instant appliedAt) {
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                VoucherDeliveryOperation operation = loadDeliveryOperation(
                        connection, deliveryOperationId).orElseThrow(
                                () -> new PersistenceConflictException(
                                        "The voucher delivery operation does not exist"));
                if (!operation.voucherId().equals(voucherId)) {
                    throw new PersistenceConflictException(
                            "The voucher delivery operation targets another voucher");
                }
                if (operation.state() == VoucherDeliveryState.APPLIED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (operation.state() == VoucherDeliveryState.ROLLED_BACK) {
                    throw new PersistenceConflictException(
                            "The voucher delivery operation was rolled back");
                }
                ResourceVoucher voucher = loadVoucher(connection, voucherId).orElseThrow(
                        () -> new PersistenceConflictException("The voucher does not exist"));
                if (voucher.state() == ResourceVoucherState.VOIDED) {
                    throw new PersistenceConflictException("The voucher was voided");
                }
                if (voucher.state() == ResourceVoucherState.PENDING_DELIVERY) {
                    updateVoucherAvailable(connection, voucherId, appliedAt);
                } else if (voucher.state() != ResourceVoucherState.AVAILABLE) {
                    throw new PersistenceConflictException(
                            "The voucher cannot become available from state " + voucher.state());
                }
                updateDeliveryState(connection, deliveryOperationId, VoucherDeliveryState.APPLIED, appliedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("apply voucher delivery", exception);
        }
    }

    /** Rolls back only a PREPARED delivery receipt; it never credits the wallet automatically. */
    public OperationOutcome rollbackDelivery(UUID deliveryOperationId, Instant rolledBackAt) {
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                VoucherDeliveryOperation operation = loadDeliveryOperation(
                        connection, deliveryOperationId).orElseThrow(
                                () -> new PersistenceConflictException(
                                        "The voucher delivery operation does not exist"));
                if (operation.state() == VoucherDeliveryState.ROLLED_BACK) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (operation.state() == VoucherDeliveryState.APPLIED) {
                    throw new PersistenceConflictException(
                            "An applied voucher delivery cannot be rolled back");
                }
                updateDeliveryState(
                        connection, deliveryOperationId, VoucherDeliveryState.ROLLED_BACK, rolledBackAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("roll back voucher delivery", exception);
        }
    }

    public List<VoucherRedeemOperation> loadOpenRedeems(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return loadRedeems(actorId, false);
    }

    /** Loads open and rolled-back operations whose physical receipt may need reconciliation. */
    public List<VoucherRedeemOperation> loadRedeemsForRecovery(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return loadRedeems(actorId, true);
    }

    private List<VoucherRedeemOperation> loadRedeems(
            UUID actorId,
            boolean includeRolledBack) {
        String states = includeRolledBack
                ? "'PREPARED', 'APPLIED', 'ROLLED_BACK'"
                : "'PREPARED', 'APPLIED'";
        return read("load voucher redeems for recovery", connection -> {
            List<VoucherRedeemOperation> operations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                           payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                    FROM resource_voucher_redeem_operations
                    WHERE actor_id = ? AND state IN (%s)
                    ORDER BY prepared_at, operation_id
                    """.formatted(states))) {
                statement.setString(1, actorId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        operations.add(redeemFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(operations);
        });
    }

    public Optional<VoucherRedeemOperation> findRedeemOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return read("load a voucher redeem operation", connection ->
                loadRedeemOperation(connection, operationId));
    }

    /** Reserves an AVAILABLE voucher before its physical item receives a redeem receipt. */
    public VoucherRedeemResult prepareRedeem(
            UUID voucherId,
            UUID actorId,
            UUID operationId,
            Instant preparedAt) {
        Objects.requireNonNull(voucherId, "voucherId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                ResourceVoucher voucher = loadVoucher(connection, voucherId).orElseThrow(
                        () -> new PersistenceConflictException("The voucher does not exist"));
                String fingerprint = redeemFingerprint(voucher, actorId);
                Optional<VoucherRedeemOperation> existing = loadRedeemOperation(
                        connection, operationId);
                if (existing.isPresent()) {
                    VoucherRedeemOperation operation = existing.orElseThrow();
                    requireMatchingRedeem(operation, voucher, actorId, fingerprint);
                    if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
                        throw new PersistenceConflictException(
                                "The voucher redeem operation was rolled back; use a new operation UUID");
                    }
                    return new VoucherRedeemResult(
                            operation.state() == VoucherRedeemState.APPLIED
                                    ? OperationOutcome.ALREADY_APPLIED
                                    : OperationOutcome.APPLIED,
                            voucher,
                            operation);
                }
                requireNoActiveEvent(connection, "redeem a resource voucher");
                requireNoPreparedCorePlacement(connection, voucher.teamId());
                ResourceRepository.requireTeamMember(connection, voucher.teamId(), actorId);
                if (voucher.state() == ResourceVoucherState.REDEEMED
                        || voucher.state() == ResourceVoucherState.VOIDED) {
                    throw new PersistenceConflictException(
                            "The voucher is no longer redeemable");
                }
                if (voucher.state() == ResourceVoucherState.RESERVED) {
                    Optional<VoucherRedeemOperation> prepared = loadPreparedRedeemForVoucher(
                            connection, voucherId);
                    if (prepared.isPresent()) {
                        throw new PersistenceConflictException(
                                "The voucher is already reserved by another operation");
                    }
                }
                if (voucher.state() != ResourceVoucherState.AVAILABLE) {
                    throw new PersistenceConflictException(
                            "The voucher is not available for deposit");
                }
                VoucherRedeemOperation operation = new VoucherRedeemOperation(
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
                        null);
                insertRedeemOperation(connection, operation);
                updateVoucherReserved(connection, voucherId, preparedAt);
                return new VoucherRedeemResult(
                        OperationOutcome.APPLIED,
                        loadVoucher(connection, voucherId).orElseThrow(),
                        operation);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The voucher redeem conflicts with persisted data", exception);
            }
            throw failure("prepare voucher redeem", exception);
        }
    }

    /** Credits the team wallet and marks the voucher REDEEMED in one SQLite transaction. */
    public OperationOutcome applyRedeem(UUID operationId, Instant appliedAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                VoucherRedeemOperation operation = loadRedeemOperation(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The voucher redeem operation does not exist"));
                if (operation.state() == VoucherRedeemState.APPLIED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
                    throw new PersistenceConflictException("The voucher redeem was rolled back");
                }
                requireNoActiveEvent(connection, "apply a resource voucher redeem");
                requireNoPreparedCorePlacement(connection, operation.teamId());
                ResourceRepository.requireTeamMember(connection, operation.teamId(), operation.actorId());
                ResourceVoucher voucher = loadVoucher(connection, operation.voucherId()).orElseThrow(
                        () -> new PersistenceConflictException("The voucher does not exist"));
                if (voucher.state() != ResourceVoucherState.RESERVED
                        || !voucher.teamId().equals(operation.teamId())
                        || voucher.resourceType() != operation.resourceType()
                        || voucher.quantity() != operation.quantity()) {
                    throw new PersistenceConflictException(
                            "The voucher no longer matches its redeem operation");
                }
                ResourceRepository.creditInTransaction(
                        connection,
                        operation.teamId(),
                        operation.resourceType(),
                        operation.quantity(),
                        operation.operationId(),
                        "VOUCHER_REDEEM|" + operation.voucherId(),
                        operation.payloadFingerprint(),
                        appliedAt);
                updateVoucherRedeemed(connection, operation.voucherId(), appliedAt);
                updateRedeemState(connection, operationId, VoucherRedeemState.APPLIED, appliedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("apply a resource voucher redeem", exception);
        }
    }

    /** Releases a RESERVED voucher only when the physical receipt was not applied. */
    public OperationOutcome rollbackRedeem(UUID operationId, Instant rolledBackAt) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        try {
            return database.inImmediateTransaction(connection -> {
                VoucherRedeemOperation operation = loadRedeemOperation(connection, operationId)
                        .orElseThrow(() -> new PersistenceConflictException(
                                "The voucher redeem operation does not exist"));
                if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (operation.state() == VoucherRedeemState.APPLIED) {
                    throw new PersistenceConflictException(
                            "An applied voucher redeem cannot be rolled back");
                }
                ResourceVoucher voucher = loadVoucher(connection, operation.voucherId()).orElseThrow(
                        () -> new PersistenceConflictException("The voucher does not exist"));
                if (voucher.state() == ResourceVoucherState.RESERVED) {
                    updateVoucherAvailable(connection, operation.voucherId(), rolledBackAt);
                }
                updateRedeemState(
                        connection, operationId, VoucherRedeemState.ROLLED_BACK, rolledBackAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("roll back a resource voucher redeem", exception);
        }
    }

    /** Used by team disbanding to reject orphaning a live voucher. */
    public boolean hasLiveVouchers(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return read("check live resource vouchers", connection -> hasLiveVouchers(connection, teamId));
    }

    static boolean hasLiveVouchers(Connection connection, UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM resource_vouchers
                WHERE team_id = ? AND state IN ('PENDING_DELIVERY', 'AVAILABLE', 'RESERVED')
                LIMIT 1
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String withdrawalFingerprint(
            UUID teamId, UUID actorId, ResourceType resourceType, long quantity) {
        return sha256("VOUCHER_WITHDRAW|" + teamId + "|" + actorId + "|"
                + resourceType + "|" + quantity);
    }

    private static String deliveryFingerprint(ResourceVoucher voucher, UUID recipientId) {
        return sha256("VOUCHER_DELIVERY|" + voucher.voucherId() + "|"
                + voucher.teamId() + "|" + recipientId + "|" + voucher.resourceType()
                + "|" + voucher.quantity());
    }

    private static String redeemFingerprint(ResourceVoucher voucher, UUID actorId) {
        return sha256("VOUCHER_REDEEM|" + voucher.voucherId() + "|"
                + voucher.teamId() + "|" + actorId + "|" + voucher.resourceType()
                + "|" + voucher.quantity());
    }

    private static UUID deterministic(UUID base, String namespace, String value) {
        return UUID.nameUUIDFromBytes((base + "|" + namespace + "|" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void requireMatchingWithdrawal(
            ResourceVoucher voucher,
            UUID teamId,
            UUID actorId,
            ResourceType resourceType,
            long quantity,
            String fingerprint) {
        if (!voucher.teamId().equals(teamId)
                || !voucher.deliveryRecipientPlayerId().equals(actorId)
                || voucher.resourceType() != resourceType
                || voucher.quantity() != quantity
                || !voucher.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The voucher withdrawal operation UUID is already assigned to another payload");
        }
    }

    private static void requireDeliveryRecipient(ResourceVoucher voucher, UUID recipientId) {
        if (!voucher.deliveryRecipientPlayerId().equals(recipientId)) {
            throw new PersistenceConflictException(
                    "A voucher can only be delivered to its original recipient");
        }
    }

    private static void requireMatchingDelivery(
            VoucherDeliveryOperation operation,
            UUID voucherId,
            UUID recipientId,
            String fingerprint) {
        if (!operation.voucherId().equals(voucherId)
                || !operation.recipientPlayerId().equals(recipientId)
                || !operation.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The voucher delivery operation UUID is already assigned to another payload");
        }
    }

    private static void requireMatchingRedeem(
            VoucherRedeemOperation operation,
            ResourceVoucher voucher,
            UUID actorId,
            String fingerprint) {
        if (!operation.voucherId().equals(voucher.voucherId())
                || !operation.teamId().equals(voucher.teamId())
                || !operation.actorId().equals(actorId)
                || operation.resourceType() != voucher.resourceType()
                || operation.quantity() != voucher.quantity()
                || !operation.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The voucher redeem operation UUID is already assigned to another payload");
        }
    }

    private static void requireTeamOwner(
            Connection connection, UUID teamId, UUID actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_player_id FROM teams WHERE team_id = ?
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("The team does not exist");
                }
                if (!actorId.toString().equals(resultSet.getString(1))) {
                    throw new PersistenceConflictException(
                            "Only the team owner may withdraw resource vouchers");
                }
            }
        }
    }

    private static void requireNoActiveEvent(Connection connection, String operation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM event_lock LIMIT 1")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new PersistenceConflictException(
                            "Cannot " + operation + " while a defense event is active");
                }
            }
        }
    }

    private static void requireNoPreparedCorePlacement(Connection connection, UUID teamId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM core_placement_operations
                WHERE team_id = ? AND state = 'PREPARED' LIMIT 1
                """)) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new PersistenceConflictException(
                            "Cannot change resource vouchers while core placement is processing");
                }
            }
        }
    }

    private static Optional<VoucherDeliveryOperation> loadPreparedDeliveryForVoucher(
            Connection connection, UUID voucherId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT delivery_operation_id, voucher_id, recipient_player_id,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_delivery_operations
                WHERE voucher_id = ? AND state = 'PREPARED'
                ORDER BY prepared_at DESC LIMIT 1
                """)) {
            statement.setString(1, voucherId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(deliveryFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<VoucherRedeemOperation> loadPreparedRedeemForVoucher(
            Connection connection, UUID voucherId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_redeem_operations
                WHERE voucher_id = ? AND state = 'PREPARED'
                ORDER BY prepared_at DESC LIMIT 1
                """)) {
            statement.setString(1, voucherId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(redeemFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static void insertVoucher(Connection connection, ResourceVoucher voucher)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_vouchers(
                    voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                    state, delivery_recipient_player_id, payload_fingerprint, issued_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING_DELIVERY', ?, ?, ?)
                """)) {
            statement.setString(1, voucher.voucherId().toString());
            statement.setString(2, voucher.withdrawalOperationId().toString());
            statement.setString(3, voucher.teamId().toString());
            statement.setString(4, voucher.resourceType().name());
            statement.setLong(5, voucher.quantity());
            statement.setString(6, voucher.deliveryRecipientPlayerId().toString());
            statement.setString(7, voucher.payloadFingerprint());
            statement.setString(8, voucher.issuedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void insertDeliveryOperation(
            Connection connection, VoucherDeliveryOperation operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_voucher_delivery_operations(
                    delivery_operation_id, voucher_id, recipient_player_id, payload_fingerprint,
                    state, prepared_at)
                VALUES (?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, operation.deliveryOperationId().toString());
            statement.setString(2, operation.voucherId().toString());
            statement.setString(3, operation.recipientPlayerId().toString());
            statement.setString(4, operation.payloadFingerprint());
            statement.setString(5, operation.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void insertRedeemOperation(
            Connection connection, VoucherRedeemOperation operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_voucher_redeem_operations(
                    operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                    payload_fingerprint, state, prepared_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, operation.voucherId().toString());
            statement.setString(3, operation.teamId().toString());
            statement.setString(4, operation.actorId().toString());
            statement.setString(5, operation.resourceType().name());
            statement.setLong(6, operation.quantity());
            statement.setString(7, operation.payloadFingerprint());
            statement.setString(8, operation.preparedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void updateVoucherAvailable(Connection connection, UUID voucherId, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_vouchers
                SET state = 'AVAILABLE', available_at = ?, reserved_at = NULL,
                    redeemed_at = NULL, voided_at = NULL
                WHERE voucher_id = ? AND state IN ('PENDING_DELIVERY', 'RESERVED')
                """)) {
            statement.setString(1, at.toString());
            statement.setString(2, voucherId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The voucher is not releasable");
            }
        }
    }

    private static void updateVoucherReserved(Connection connection, UUID voucherId, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_vouchers
                SET state = 'RESERVED', reserved_at = ?
                WHERE voucher_id = ? AND state = 'AVAILABLE'
                """)) {
            statement.setString(1, at.toString());
            statement.setString(2, voucherId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The voucher is no longer available");
            }
        }
    }

    private static void updateVoucherRedeemed(Connection connection, UUID voucherId, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_vouchers
                SET state = 'REDEEMED', redeemed_at = ?
                WHERE voucher_id = ? AND state = 'RESERVED'
                """)) {
            statement.setString(1, at.toString());
            statement.setString(2, voucherId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The voucher is no longer reserved");
            }
        }
    }

    private static void updateDeliveryState(
            Connection connection,
            UUID operationId,
            VoucherDeliveryState state,
            Instant at) throws SQLException {
        String sql = state == VoucherDeliveryState.APPLIED
                ? """
                  UPDATE resource_voucher_delivery_operations
                  SET state = 'APPLIED', applied_at = ?
                  WHERE delivery_operation_id = ? AND state = 'PREPARED'
                  """
                : """
                  UPDATE resource_voucher_delivery_operations
                  SET state = 'ROLLED_BACK', rolled_back_at = ?
                  WHERE delivery_operation_id = ? AND state = 'PREPARED'
                  """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, at.toString());
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The voucher delivery state changed");
            }
        }
    }

    private static void resetDeliveryState(
            Connection connection,
            UUID operationId,
            Instant preparedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_voucher_delivery_operations
                SET state = 'PREPARED', prepared_at = ?, applied_at = NULL, rolled_back_at = NULL
                WHERE delivery_operation_id = ? AND state = 'ROLLED_BACK'
                """)) {
            statement.setString(1, preparedAt.toString());
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The voucher delivery state changed");
            }
        }
    }

    private static void updateRedeemState(
            Connection connection,
            UUID operationId,
            VoucherRedeemState state,
            Instant at) throws SQLException {
        String sql = state == VoucherRedeemState.APPLIED
                ? """
                  UPDATE resource_voucher_redeem_operations
                  SET state = 'APPLIED', applied_at = ?
                  WHERE operation_id = ? AND state = 'PREPARED'
                  """
                : """
                  UPDATE resource_voucher_redeem_operations
                  SET state = 'ROLLED_BACK', rolled_back_at = ?
                  WHERE operation_id = ? AND state = 'PREPARED'
                  """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, at.toString());
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The voucher redeem state changed");
            }
        }
    }

    private static Optional<ResourceVoucher> loadVoucherByWithdrawal(
            Connection connection, UUID withdrawalOperationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                       state, delivery_recipient_player_id, payload_fingerprint, issued_at,
                       available_at, reserved_at, redeemed_at, voided_at
                FROM resource_vouchers WHERE withdrawal_operation_id = ?
                """)) {
            statement.setString(1, withdrawalOperationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(voucherFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<ResourceVoucher> loadVoucher(
            Connection connection, UUID voucherId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT voucher_id, withdrawal_operation_id, team_id, resource_type, quantity,
                       state, delivery_recipient_player_id, payload_fingerprint, issued_at,
                       available_at, reserved_at, redeemed_at, voided_at
                FROM resource_vouchers WHERE voucher_id = ?
                """)) {
            statement.setString(1, voucherId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(voucherFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<VoucherDeliveryOperation> loadDeliveryOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT delivery_operation_id, voucher_id, recipient_player_id,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_delivery_operations
                WHERE delivery_operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(deliveryFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<VoucherRedeemOperation> loadRedeemOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, voucher_id, team_id, actor_id, resource_type, quantity,
                       payload_fingerprint, state, prepared_at, applied_at, rolled_back_at
                FROM resource_voucher_redeem_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(redeemFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static ResourceVoucher voucherFromRow(ResultSet resultSet) throws SQLException {
        return new ResourceVoucher(
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
                nullableInstant(resultSet.getString("voided_at")));
    }

    private static VoucherDeliveryOperation deliveryFromRow(ResultSet resultSet)
            throws SQLException {
        return new VoucherDeliveryOperation(
                uuid(resultSet.getString("delivery_operation_id")),
                uuid(resultSet.getString("voucher_id")),
                uuid(resultSet.getString("recipient_player_id")),
                resultSet.getString("payload_fingerprint"),
                VoucherDeliveryState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                nullableInstant(resultSet.getString("applied_at")),
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private static VoucherRedeemOperation redeemFromRow(ResultSet resultSet)
            throws SQLException {
        return new VoucherRedeemOperation(
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
                nullableInstant(resultSet.getString("rolled_back_at")));
    }

    private <T> T read(String action, Database.SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw failure(action, exception);
        }
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

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the JDK", exception);
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String message = exception.getMessage();
        return message != null
                && (message.contains("UNIQUE")
                        || message.contains("constraint")
                        || message.contains("CHECK"));
    }

    private static PersistenceException failure(String action, SQLException exception) {
        return new PersistenceException("Could not " + action, exception);
    }
}
