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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed write-ahead ledger for event-owned world changes.
 *
 * <p>The Paper adapter must persist a change with {@link #prepare(BlockChange, UUID, Instant)}
 * before touching a block. It then prepares and applies the physical operation separately. On
 * recovery, {@link BlockRollbackPlanner} compares the durable expected state with the live block
 * before the adapter calls {@link #prepareRollback(UUID, UUID, UUID, BlockRollbackDecision,
 * Instant)} and {@link #applyRollback(UUID, UUID, UUID, BlockRollbackDecision, Instant)}.</p>
 */
public final class BlockChangeRepository {
    private final Database database;

    public BlockChangeRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Writes the before/after snapshot before the world mutation is attempted. */
    public OperationOutcome prepare(
            BlockChange change,
            UUID prepareOperationId,
            Instant preparedAt) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(prepareOperationId, "prepareOperationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        String fingerprint = fingerprint(change);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<StoredBlockChange> existing = loadChange(connection, change.changeId());
                if (existing.isPresent()) {
                    StoredBlockChange value = existing.orElseThrow();
                    if (!value.change().equals(change)
                            || !value.prepareOperationId().equals(prepareOperationId)) {
                        throw new PersistenceConflictException(
                                "The block change UUID is already assigned to another payload");
                    }
                    return OperationOutcome.ALREADY_APPLIED;
                }
                requireActiveEvent(connection, change.eventId());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO event_block_changes(
                            change_id, event_id, world_id, block_x, block_y, block_z,
                            change_kind, generation, before_block_data, before_block_state,
                            before_tile_nbt, expected_after_block_data, expected_after_block_state,
                            expected_after_tile_nbt, status,
                            prepare_operation_id, prepared_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?)
                        """)) {
                    statement.setString(1, change.changeId().toString());
                    statement.setString(2, change.eventId().toString());
                    statement.setString(3, change.worldId().toString());
                    statement.setInt(4, change.blockX());
                    statement.setInt(5, change.blockY());
                    statement.setInt(6, change.blockZ());
                    statement.setString(7, change.kind().name());
                    statement.setLong(8, change.generation());
                    statement.setString(9, change.beforeBlockData());
                    statement.setString(10, change.beforeBlockState());
                    statement.setString(11, change.beforeTileNbt());
                    statement.setString(12, change.expectedAfterBlockData());
                    statement.setString(13, change.expectedAfterBlockState());
                    statement.setString(14, change.expectedAfterTileNbt());
                    statement.setString(15, prepareOperationId.toString());
                    statement.setString(16, preparedAt.toString());
                    statement.executeUpdate();
                }
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The block change conflicts with an existing ledger row", exception);
            }
            throw failure("prepare a block change", exception);
        }
    }

    /** Records that the physical block operation is about to run. */
    public OperationOutcome prepareApply(
            UUID eventId,
            UUID changeId,
            UUID operationId,
            Instant preparedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        return prepareResourceOperation(
                eventId,
                changeId,
                operationId,
                "BLOCK_APPLY",
                loadFingerprint(eventId, changeId),
                null,
                preparedAt);
    }

    /** Calculates the next per-coordinate generation for a main-thread block action. */
    public long nextGeneration(
            UUID eventId,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(worldId, "worldId");
        try {
            return database.inImmediateTransaction(connection -> {
                requireActiveEvent(connection, eventId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT COALESCE(MAX(generation), 0)
                        FROM event_block_changes
                        WHERE event_id = ? AND world_id = ?
                          AND block_x = ? AND block_y = ? AND block_z = ?
                        """)) {
                    statement.setString(1, eventId.toString());
                    statement.setString(2, worldId.toString());
                    statement.setInt(3, blockX);
                    statement.setInt(4, blockY);
                    statement.setInt(5, blockZ);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        long current = resultSet.getLong(1);
                        if (current == Long.MAX_VALUE) {
                            throw new IllegalStateException(
                                    "The block mutation generation reached Long.MAX_VALUE");
                        }
                        return current + 1L;
                    }
                }
            });
        } catch (SQLException exception) {
            throw failure("allocate a block mutation generation", exception);
        }
    }

    /** Marks a previously prepared physical block operation as applied. */
    public OperationOutcome apply(
            UUID eventId,
            UUID changeId,
            UUID operationId,
            Instant appliedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        String fingerprint = loadFingerprint(eventId, changeId);
        try {
            return database.inImmediateTransaction(connection -> {
                ResourceOperation operation = requireResourceOperation(
                        connection, eventId, changeId, operationId, "BLOCK_APPLY", fingerprint);
                if (operation.state() == ResourceOperationState.APPLIED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                requireActiveEvent(connection, eventId);
                StoredBlockChange change = requireChange(connection, changeId);
                if (change.status() != BlockChangeStatus.PREPARED) {
                    throw new PersistenceConflictException(
                            "A block change is no longer prepared for application");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_block_changes
                        SET status = 'APPLIED', apply_operation_id = ?, applied_at = ?
                        WHERE change_id = ? AND status = 'PREPARED'
                        """)) {
                    statement.setString(1, operationId.toString());
                    statement.setString(2, appliedAt.toString());
                    statement.setString(3, changeId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The block change was concurrently applied or resolved");
                    }
                }
                markResourceOperationApplied(connection, operationId, appliedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("apply a prepared block change", exception);
        }
    }

    /** Records that a recovery rollback decision is about to be executed. */
    public OperationOutcome prepareRollback(
            UUID eventId,
            UUID changeId,
            UUID operationId,
            BlockRollbackDecision decision,
            Instant preparedAt) {
        Objects.requireNonNull(decision, "decision");
        return prepareResourceOperation(
                eventId,
                changeId,
                operationId,
                "BLOCK_ROLLBACK",
                rollbackFingerprint(eventId, changeId, decision),
                decision.name(),
                preparedAt);
    }

    /**
     * Marks a rollback as complete. A conflict is durable and intentionally does not overwrite the
     * live block; an administrator can inspect it before deciding on a manual repair.
     */
    public OperationOutcome applyRollback(
            UUID eventId,
            UUID changeId,
            UUID operationId,
            BlockRollbackDecision decision,
            Instant resolvedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        String fingerprint = rollbackFingerprint(eventId, changeId, decision);
        try {
            return database.inImmediateTransaction(connection -> {
                ResourceOperation operation = requireResourceOperation(
                        connection,
                        eventId,
                        changeId,
                        operationId,
                        "BLOCK_ROLLBACK",
                        fingerprint);
                if (operation.state() == ResourceOperationState.APPLIED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                requireActiveEvent(connection, eventId);
                StoredBlockChange change = requireChange(connection, changeId);
                if (change.status() != BlockChangeStatus.PREPARED
                        && change.status() != BlockChangeStatus.APPLIED) {
                    throw new PersistenceConflictException(
                            "A block change has already reached a terminal recovery status");
                }
                BlockChangeStatus nextStatus = decision == BlockRollbackDecision.CONFLICT
                        ? BlockChangeStatus.CONFLICT
                        : BlockChangeStatus.ROLLED_BACK;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_block_changes
                        SET status = ?, rollback_operation_id = ?,
                            applied_at = COALESCE(applied_at, ?), resolved_at = ?
                        WHERE change_id = ? AND status IN ('PREPARED', 'APPLIED')
                        """)) {
                    statement.setString(1, nextStatus.name());
                    statement.setString(2, operationId.toString());
                    statement.setString(3, resolvedAt.toString());
                    statement.setString(4, resolvedAt.toString());
                    statement.setString(5, changeId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The block change was concurrently resolved");
                    }
                }
                markResourceOperationApplied(connection, operationId, resolvedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("apply a block rollback", exception);
        }
    }

    /** Loads all ledger rows in reverse generation order for safe rollback. */
    public List<StoredBlockChange> loadChanges(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load block changes", connection -> loadChanges(connection, eventId));
    }

    /** Loads only rows which still need a recovery decision. */
    public List<StoredBlockChange> loadUnresolvedChanges(UUID eventId) {
        return loadChanges(eventId).stream()
                .filter(change -> change.status() == BlockChangeStatus.PREPARED
                        || change.status() == BlockChangeStatus.APPLIED)
                .toList();
    }

    /** Loads a rollback operation which was prepared but not committed before a stop. */
    public Optional<PreparedRollback> loadPreparedRollback(UUID eventId, UUID changeId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(changeId, "changeId");
        return read("load a prepared block rollback", connection -> {
            Optional<ResourceOperation> operation = loadResourceOperation(
                    connection, eventId, "BLOCK_ROLLBACK", changeId);
            if (operation.isEmpty()
                    || operation.orElseThrow().state() != ResourceOperationState.PREPARED) {
                return Optional.empty();
            }
            ResourceOperation value = operation.orElseThrow();
            BlockRollbackDecision decision = value.rollbackDecision().orElseThrow(
                    () -> new PersistenceConflictException(
                            "A prepared rollback has no persisted decision"));
            return Optional.of(new PreparedRollback(value.operationId(), decision));
        });
    }

    /** Package-private guard used by event recovery to avoid releasing a dirty world mutation. */
    static boolean hasUnresolved(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM event_block_changes
                WHERE event_id = ? AND status IN ('PREPARED', 'APPLIED')
                LIMIT 1
                """)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /**
     * Settles applied event-destruction rows in the same transaction as the normal terminal event.
     * Temporary rows must already have been physically removed by the Paper adapter. Keeping this
     * operation inside the terminal transaction means a crash before lock release still exposes
     * the destruction rows to technical recovery.
     */
    static void settleAppliedEventBlocks(
            Connection connection,
            UUID eventId,
            UUID terminalOperationId,
            Instant settledAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(terminalOperationId, "terminalOperationId");
        Objects.requireNonNull(settledAt, "settledAt");
        requireActiveEvent(connection, eventId);
        for (StoredBlockChange change : loadChanges(connection, eventId)) {
            if (change.status() == BlockChangeStatus.PREPARED) {
                throw new PersistenceConflictException(
                        "A block change is still prepared during normal terrain settlement");
            }
            if (change.status() != BlockChangeStatus.APPLIED) {
                continue;
            }
            if (change.change().kind() != BlockChangeKind.EVENT_BLOCK) {
                throw new PersistenceConflictException(
                        "A temporary block remained unresolved during normal terrain settlement");
            }
            UUID operationId = deterministicOperation(
                    terminalOperationId,
                    "BLOCK_SETTLE",
                    change.change().changeId());
            ensureResourceOperationApplied(
                    connection,
                    operationId,
                    eventId,
                    "BLOCK_SETTLE",
                    change.change().changeId(),
                    settlementFingerprint(change.change()),
                    settledAt);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE event_block_changes
                    SET status = 'SETTLED', rollback_operation_id = ?, resolved_at = ?
                    WHERE change_id = ? AND status = 'APPLIED'
                    """)) {
                statement.setString(1, operationId.toString());
                statement.setString(2, settledAt.toString());
                statement.setString(3, change.change().changeId().toString());
                if (statement.executeUpdate() != 1) {
                    throw new PersistenceConflictException(
                            "The event block changed while terminal settlement was running");
                }
            }
        }
        if (hasUnresolved(connection, eventId)) {
            throw new PersistenceConflictException(
                    "Unresolved block changes remain after normal terrain settlement");
        }
    }

    private static List<StoredBlockChange> loadChanges(
            Connection connection,
            UUID eventId) throws SQLException {
        List<StoredBlockChange> changes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT change_id, event_id, world_id, block_x, block_y, block_z,
                       change_kind, generation, before_block_data, before_block_state,
                       before_tile_nbt, expected_after_block_data, expected_after_block_state,
                       expected_after_tile_nbt, status,
                       prepare_operation_id, apply_operation_id, rollback_operation_id,
                       prepared_at, applied_at, resolved_at
                FROM event_block_changes
                WHERE event_id = ?
                ORDER BY generation DESC, change_id DESC
                """)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    changes.add(blockChangeFromRow(resultSet));
                }
            }
        }
        return List.copyOf(changes);
    }

    private OperationOutcome prepareResourceOperation(
            UUID eventId,
            UUID targetId,
            UUID operationId,
            String kind,
            String fingerprint,
            String rollbackDecision,
            Instant preparedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(preparedAt, "preparedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                requireActiveEvent(connection, eventId);
                Optional<ResourceOperation> existing = loadResourceOperation(
                        connection, eventId, kind, targetId);
                if (existing.isPresent()) {
                    ResourceOperation value = existing.orElseThrow();
                    requireMatchingResourceOperation(
                            value, operationId, eventId, kind, targetId, fingerprint);
                    return value.state() == ResourceOperationState.APPLIED
                            ? OperationOutcome.ALREADY_APPLIED
                            : OperationOutcome.ALREADY_APPLIED;
                }
                Optional<ResourceOperation> sameOperation = loadResourceOperation(
                        connection, operationId);
                if (sameOperation.isPresent()) {
                    requireMatchingResourceOperation(
                            sameOperation.orElseThrow(),
                            operationId,
                            eventId,
                            kind,
                            targetId,
                            fingerprint);
                    return OperationOutcome.ALREADY_APPLIED;
                }
                insertResourceOperation(
                        connection,
                        operationId,
                        eventId,
                        kind,
                        targetId,
                        fingerprint,
                        rollbackDecision,
                        preparedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The block resource operation conflicts with persisted data", exception);
            }
            throw failure("prepare a block resource operation", exception);
        }
    }

    private static void ensureResourceOperationApplied(
            Connection connection,
            UUID operationId,
            UUID eventId,
            String kind,
            UUID targetId,
            String fingerprint,
            Instant timestamp) throws SQLException {
        Optional<ResourceOperation> existing = loadResourceOperation(
                connection, eventId, kind, targetId);
        if (existing.isPresent()) {
            requireMatchingResourceOperation(
                    existing.orElseThrow(), operationId, eventId, kind, targetId, fingerprint);
            if (existing.orElseThrow().state() == ResourceOperationState.PREPARED) {
                markResourceOperationApplied(connection, operationId, timestamp);
            }
            return;
        }
        Optional<ResourceOperation> sameOperation = loadResourceOperation(connection, operationId);
        if (sameOperation.isPresent()) {
            requireMatchingResourceOperation(
                    sameOperation.orElseThrow(), operationId, eventId, kind, targetId, fingerprint);
            if (sameOperation.orElseThrow().state() == ResourceOperationState.PREPARED) {
                markResourceOperationApplied(connection, operationId, timestamp);
            }
            return;
        }
        insertResourceOperation(
                connection, operationId, eventId, kind, targetId, fingerprint, null, timestamp);
        markResourceOperationApplied(connection, operationId, timestamp);
    }

    private static ResourceOperation requireResourceOperation(
            Connection connection,
            UUID eventId,
            UUID targetId,
            UUID operationId,
            String kind,
            String fingerprint) throws SQLException {
        ResourceOperation operation = loadResourceOperation(connection, operationId).orElseThrow(
                () -> new PersistenceConflictException(
                        "The resource operation was not prepared: " + operationId));
        requireMatchingResourceOperation(
                operation, operationId, eventId, kind, targetId, fingerprint);
        return operation;
    }

    private static void requireMatchingResourceOperation(
            ResourceOperation operation,
            UUID operationId,
            UUID eventId,
            String kind,
            UUID targetId,
            String fingerprint) {
        if (!operation.operationId().equals(operationId)
                || !operation.eventId().equals(eventId)
                || !operation.kind().equals(kind)
                || !operation.targetId().equals(targetId.toString())
                || !operation.fingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The resource operation UUID is already assigned to another payload");
        }
    }

    private static void insertResourceOperation(
            Connection connection,
            UUID operationId,
            UUID eventId,
            String kind,
            UUID targetId,
            String fingerprint,
            String rollbackDecision,
            Instant preparedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_mutation_operations(
                    operation_id, event_id, operation_kind, target_id,
                    payload_fingerprint, state, prepared_at, rollback_decision
                ) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, kind);
            statement.setString(4, targetId.toString());
            statement.setString(5, fingerprint);
            statement.setString(6, preparedAt.toString());
            statement.setString(7, rollbackDecision);
            statement.executeUpdate();
        }
    }

    private static void markResourceOperationApplied(
            Connection connection,
            UUID operationId,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_mutation_operations
                SET state = 'APPLIED', applied_at = ?
                WHERE operation_id = ? AND state = 'PREPARED'
                """)) {
            statement.setString(1, appliedAt.toString());
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException(
                        "The resource operation was already applied or disappeared");
            }
        }
    }

    private static Optional<ResourceOperation> loadResourceOperation(
            Connection connection,
            UUID eventId,
            String kind,
            UUID targetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, event_id, operation_kind, target_id,
                       payload_fingerprint, state, prepared_at, applied_at,
                       rollback_decision
                FROM event_mutation_operations
                WHERE event_id = ? AND operation_kind = ? AND target_id = ?
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, kind);
            statement.setString(3, targetId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(resourceOperationFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<ResourceOperation> loadResourceOperation(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, event_id, operation_kind, target_id,
                       payload_fingerprint, state, prepared_at, applied_at,
                       rollback_decision
                FROM event_mutation_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(resourceOperationFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static ResourceOperation resourceOperationFromRow(ResultSet resultSet)
            throws SQLException {
        String appliedAt = resultSet.getString("applied_at");
        return new ResourceOperation(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("event_id")),
                resultSet.getString("operation_kind"),
                resultSet.getString("target_id"),
                resultSet.getString("payload_fingerprint"),
                ResourceOperationState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                appliedAt == null ? Optional.empty() : Optional.of(instant(appliedAt)),
                Optional.ofNullable(resultSet.getString("rollback_decision"))
                        .map(BlockRollbackDecision::valueOf));
    }

    private static Optional<StoredBlockChange> loadChange(
            Connection connection, UUID changeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT change_id, event_id, world_id, block_x, block_y, block_z,
                       change_kind, generation, before_block_data, before_block_state,
                       before_tile_nbt, expected_after_block_data, expected_after_block_state,
                       expected_after_tile_nbt, status,
                       prepare_operation_id, apply_operation_id, rollback_operation_id,
                       prepared_at, applied_at, resolved_at
                FROM event_block_changes WHERE change_id = ?
                """)) {
            statement.setString(1, changeId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(blockChangeFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static StoredBlockChange requireChange(Connection connection, UUID changeId)
            throws SQLException {
        return loadChange(connection, changeId).orElseThrow(
                () -> new PersistenceConflictException("Unknown block change " + changeId));
    }

    private static StoredBlockChange blockChangeFromRow(ResultSet resultSet) throws SQLException {
        String applyOperation = resultSet.getString("apply_operation_id");
        String rollbackOperation = resultSet.getString("rollback_operation_id");
        String appliedAt = resultSet.getString("applied_at");
        String resolvedAt = resultSet.getString("resolved_at");
        BlockChange change = new BlockChange(
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("change_id")),
                uuid(resultSet.getString("world_id")),
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
                resultSet.getString("expected_after_tile_nbt"));
        return new StoredBlockChange(
                change,
                BlockChangeStatus.valueOf(resultSet.getString("status")),
                uuid(resultSet.getString("prepare_operation_id")),
                applyOperation == null ? Optional.empty() : Optional.of(uuid(applyOperation)),
                rollbackOperation == null
                        ? Optional.empty()
                        : Optional.of(uuid(rollbackOperation)),
                instant(resultSet.getString("prepared_at")),
                appliedAt == null ? Optional.empty() : Optional.of(instant(appliedAt)),
                resolvedAt == null ? Optional.empty() : Optional.of(instant(resolvedAt)));
    }

    private String loadFingerprint(UUID eventId, UUID changeId) {
        return read("load a block change fingerprint", connection -> {
            StoredBlockChange change = requireChange(connection, changeId);
            if (!change.change().eventId().equals(eventId)) {
                throw new PersistenceConflictException(
                        "The block change belongs to another defense event");
            }
            return fingerprint(change.change());
        });
    }

    private static void requireActiveEvent(Connection connection, UUID eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM defense_events WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("Unknown defense event " + eventId);
                }
                if (DefensePhaseNames.isTerminal(resultSet.getString("state"))) {
                    throw new IllegalStateException(
                            "Cannot mutate the block ledger of a terminal event");
                }
            }
        }
    }

    private <T> T read(String action, Database.SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw failure(action, exception);
        }
    }

    private static String fingerprint(BlockChange change) {
        String canonical = change.eventId() + "|" + change.changeId() + "|"
                + change.worldId() + "|" + change.blockX() + "|" + change.blockY() + "|"
                + change.blockZ() + "|" + change.kind() + "|" + change.generation() + "|"
                + change.beforeBlockData() + "|" + change.beforeBlockState() + "|"
                + change.beforeTileNbt() + "|" + change.expectedAfterBlockData() + "|"
                + change.expectedAfterBlockState() + "|" + change.expectedAfterTileNbt();
        return sha256(canonical);
    }

    private String rollbackFingerprint(
            UUID eventId, UUID changeId, BlockRollbackDecision decision) {
        String changeFingerprint = loadFingerprint(eventId, changeId);
        return sha256(changeFingerprint + "|" + decision.name());
    }

    private static String settlementFingerprint(BlockChange change) {
        return sha256(fingerprint(change) + "|BLOCK_SETTLE");
    }

    private static UUID deterministicOperation(UUID base, String namespace, UUID value) {
        return UUID.nameUUIDFromBytes((base + "|" + namespace + "|" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime must provide SHA-256", exception);
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

    private record ResourceOperation(
            UUID operationId,
            UUID eventId,
            String kind,
            String targetId,
            String fingerprint,
            ResourceOperationState state,
            Instant preparedAt,
            Optional<Instant> appliedAt,
            Optional<BlockRollbackDecision> rollbackDecision) {
    }

    private enum ResourceOperationState {
        PREPARED,
        APPLIED
    }

    private static final class DefensePhaseNames {
        private DefensePhaseNames() {
        }

        private static boolean isTerminal(String phase) {
            return "VICTORY".equals(phase)
                    || "DEFEAT".equals(phase)
                    || "ABORTED".equals(phase)
                    || "RECOVERY".equals(phase);
        }
    }
}
