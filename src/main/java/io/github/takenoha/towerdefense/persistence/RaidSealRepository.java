package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import java.nio.charset.StandardCharsets;
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
 * Authoritative persistence for non-stackable challenge seals.
 *
 * <p>The normal player-facing path reserves a seal before touching an inventory and consumes it
 * only after the start transaction has passed all validation. Technical refunds always create a
 * new seal UUID; the original UUID is never made usable again.</p>
 */
public final class RaidSealRepository {
    private final Database database;

    public RaidSealRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Registers a newly crafted seal in the available state. */
    public RaidSeal register(UUID sealId, UUID ownerPlayerId, long stageLevel, Instant createdAt) {
        Objects.requireNonNull(sealId, "sealId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
        RaidSeal seal = new RaidSeal(
                sealId,
                ownerPlayerId,
                stageLevel,
                RaidSealStatus.AVAILABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                createdAt,
                createdAt);
        try {
            database.inImmediateTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO raid_seals(
                            seal_id, owner_player_id, stage_level, state, created_at, updated_at
                        ) VALUES (?, ?, ?, 'AVAILABLE', ?, ?)
                        """)) {
                    statement.setString(1, sealId.toString());
                    statement.setString(2, ownerPlayerId.toString());
                    statement.setLong(3, stageLevel);
                    statement.setString(4, createdAt.toString());
                    statement.setString(5, createdAt.toString());
                    statement.executeUpdate();
                }
                return null;
            });
            return seal;
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The raid seal UUID is already registered", exception);
            }
            throw failure("register a raid seal", exception);
        }
    }

    /** Reserves a seal before the corresponding physical inventory item is removed. */
    public OperationOutcome reserve(
            UUID sealId,
            UUID eventId,
            UUID ownerPlayerId,
            long stageLevel,
            UUID operationId,
            Instant reservedAt) {
        requireSealMutationArguments(
                sealId, eventId, ownerPlayerId, stageLevel, operationId, reservedAt);
        try {
            return database.inImmediateTransaction(connection ->
                    reserve(connection, sealId, eventId, ownerPlayerId, stageLevel, operationId,
                            reservedAt));
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The raid seal reservation conflicts with another event", exception);
            }
            throw failure("reserve a raid seal", exception);
        }
    }

    /** Commits a reservation after the caller has removed the physical token. */
    public OperationOutcome consume(
            UUID sealId,
            UUID eventId,
            UUID operationId,
            Instant consumedAt) {
        Objects.requireNonNull(sealId, "sealId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(consumedAt, "consumedAt");
        try {
            return database.inImmediateTransaction(connection ->
                    consume(connection, sealId, eventId, operationId, consumedAt));
        } catch (SQLException exception) {
            throw failure("consume a reserved raid seal", exception);
        }
    }

    /** Returns a fresh usable seal for a technical recovery. */
    public RaidSealRefundResult refund(
            UUID eventId,
            UUID recoveryOperationId,
            Instant refundedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(recoveryOperationId, "recoveryOperationId");
        Objects.requireNonNull(refundedAt, "refundedAt");
        try {
            return database.inImmediateTransaction(connection ->
                    refund(connection, eventId, recoveryOperationId, refundedAt));
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The raid seal refund conflicts with persisted data", exception);
            }
            throw failure("refund a raid seal", exception);
        }
    }

    public Optional<RaidSeal> find(UUID sealId) {
        Objects.requireNonNull(sealId, "sealId");
        return read("load a raid seal", connection -> load(connection, sealId));
    }

    public List<RaidSeal> loadForOwner(UUID ownerPlayerId) {
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        return read("load a player's raid seals", connection -> {
            List<RaidSeal> seals = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT seal_id, owner_player_id, stage_level, state, event_id,
                           reservation_operation_id, consumption_operation_id,
                           refund_operation_id, created_at, updated_at
                    FROM raid_seals WHERE owner_player_id = ? ORDER BY seal_id
                    """)) {
                statement.setString(1, ownerPlayerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        seals.add(sealFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(seals);
        });
    }

    /**
     * Loads technical-refund seals which are ready to be materialized as a new physical item.
     * The returned UUID is never the UUID that was consumed for the failed event.
     */
    public List<RaidSeal> loadAvailableRefunds(UUID ownerPlayerId) {
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        return read("load a player's refundable raid seals", connection -> {
            List<RaidSeal> seals = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.seal_id, s.owner_player_id, s.stage_level, s.state, s.event_id,
                           s.reservation_operation_id, s.consumption_operation_id,
                           s.refund_operation_id, s.created_at, s.updated_at
                    FROM raid_seals s
                    INNER JOIN raid_seal_returns r ON r.returned_seal_id = s.seal_id
                    WHERE s.owner_player_id = ? AND s.state = 'AVAILABLE'
                    ORDER BY s.created_at, s.seal_id
                    """)) {
                statement.setString(1, ownerPlayerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        seals.add(sealFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(seals);
        });
    }

    /** Package-private hook used by the atomic start transaction when a seal is supplied. */
    static void consumeForStart(
            Connection connection,
            StartRequest request) throws SQLException {
        if (request.raidSealId().isEmpty()) {
            return;
        }
        reserveForStart(connection, request);
        consumeReservedForStart(
                connection,
                request.session().eventId(),
                request.raidSealId().orElseThrow(),
                request.startedAt());
    }

    /** Package-private hook for a Paper start which must remove the physical item between reserve and consume. */
    static void reserveForStart(
            Connection connection,
            StartRequest request) throws SQLException {
        if (request.raidSealId().isEmpty()) {
            return;
        }
        UUID sealId = request.raidSealId().orElseThrow();
        UUID eventId = request.session().eventId();
        UUID teamId = request.session().teamId();
        UUID ownerPlayerId = loadTeamOwner(connection, teamId);
        UUID reservationOperationId = deterministicOperation(eventId, "SEAL_RESERVE");
        long stageLevel = request.session().stageLevel();
        OperationOutcome reserved = reserve(
                connection,
                sealId,
                eventId,
                ownerPlayerId,
                stageLevel,
                reservationOperationId,
                request.startedAt());
        if (reserved == OperationOutcome.ALREADY_APPLIED) {
            RaidSeal current = load(connection, sealId).orElseThrow();
            if (current.status() == RaidSealStatus.CONSUMED
                    && current.eventId().orElseThrow().equals(eventId)) {
                return;
            }
        }
    }

    /** Package-private hook for a Paper start after its physical item has been removed. */
    static OperationOutcome consumeReservedForStart(
            Connection connection,
            UUID eventId,
            UUID sealId,
            Instant consumedAt) throws SQLException {
        requireActiveEvent(connection, eventId);
        return consume(
                connection,
                sealId,
                eventId,
                deterministicOperation(eventId, "SEAL_CONSUME"),
                consumedAt);
    }

    /** Package-private hook used by technical event recovery. */
    static RaidSealRefundResult refund(
            Connection connection,
            UUID eventId,
            UUID recoveryOperationId,
            Instant refundedAt) throws SQLException {
        requireRecoveryEvent(connection, eventId);
        Optional<RaidSealReturn> existingReturn = loadReturn(connection, recoveryOperationId);
        if (existingReturn.isPresent()) {
            RaidSeal returned = load(connection, existingReturn.orElseThrow().returnedSealId())
                    .orElseThrow();
            return new RaidSealRefundResult(OperationOutcome.ALREADY_APPLIED, returned);
        }
        Optional<RaidSeal> original = loadByEvent(connection, eventId);
        if (original.isEmpty()) {
            throw new PersistenceConflictException(
                    "No consumed or reserved raid seal belongs to event " + eventId);
        }
        RaidSeal source = original.orElseThrow();
        if (source.status() != RaidSealStatus.RESERVED
                && source.status() != RaidSealStatus.CONSUMED) {
            throw new PersistenceConflictException(
                    "The raid seal is not eligible for a technical refund");
        }
        UUID returnedSealId = deterministicOperation(recoveryOperationId, "SEAL_RETURN");
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE raid_seals
                SET state = 'REFUNDED', refund_operation_id = ?, updated_at = ?
                WHERE seal_id = ? AND state IN ('RESERVED', 'CONSUMED')
                """)) {
            statement.setString(1, recoveryOperationId.toString());
            statement.setString(2, refundedAt.toString());
            statement.setString(3, source.sealId().toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException(
                        "The raid seal was concurrently refunded or consumed");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO raid_seals(
                    seal_id, owner_player_id, stage_level, state, created_at, updated_at
                ) VALUES (?, ?, ?, 'AVAILABLE', ?, ?)
                """)) {
            statement.setString(1, returnedSealId.toString());
            statement.setString(2, source.ownerPlayerId().toString());
            statement.setLong(3, source.stageLevel());
            statement.setString(4, refundedAt.toString());
            statement.setString(5, refundedAt.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO raid_seal_returns(
                    return_operation_id, event_id, original_seal_id, returned_seal_id,
                    owner_player_id, stage_level, issued_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, recoveryOperationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, source.sealId().toString());
            statement.setString(4, returnedSealId.toString());
            statement.setString(5, source.ownerPlayerId().toString());
            statement.setLong(6, source.stageLevel());
            statement.setString(7, refundedAt.toString());
            statement.executeUpdate();
        }
        RaidSeal returned = load(connection, returnedSealId).orElseThrow();
        return new RaidSealRefundResult(OperationOutcome.APPLIED, returned);
    }

    /** Package-private hook for administrator-only starts which did not use a seal. */
    static Optional<RaidSealRefundResult> refundIfPresent(
            Connection connection,
            UUID eventId,
            UUID recoveryOperationId,
            Instant refundedAt) throws SQLException {
        if (loadByEvent(connection, eventId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(refund(connection, eventId, recoveryOperationId, refundedAt));
    }

    private static OperationOutcome reserve(
            Connection connection,
            UUID sealId,
            UUID eventId,
            UUID ownerPlayerId,
            long stageLevel,
            UUID operationId,
            Instant reservedAt) throws SQLException {
        requireActiveEvent(connection, eventId);
        Optional<RaidSeal> current = load(connection, sealId);
        if (current.isEmpty()) {
            throw new PersistenceConflictException("Unknown raid seal " + sealId);
        }
        RaidSeal value = current.orElseThrow();
        if (value.status() == RaidSealStatus.RESERVED
                && value.eventId().orElseThrow().equals(eventId)
                && value.reservationOperationId().orElseThrow().equals(operationId)) {
            return OperationOutcome.ALREADY_APPLIED;
        }
        if (value.status() == RaidSealStatus.CONSUMED
                && value.eventId().orElseThrow().equals(eventId)
                && value.reservationOperationId().orElseThrow().equals(operationId)) {
            return OperationOutcome.ALREADY_APPLIED;
        }
        if (value.status() != RaidSealStatus.AVAILABLE
                || !value.ownerPlayerId().equals(ownerPlayerId)
                || value.stageLevel() != stageLevel) {
            throw new PersistenceConflictException(
                    "The raid seal is unavailable for this owner, stage, or event");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE raid_seals
                SET state = 'RESERVED', event_id = ?, reservation_operation_id = ?, updated_at = ?
                WHERE seal_id = ? AND state = 'AVAILABLE'
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, operationId.toString());
            statement.setString(3, reservedAt.toString());
            statement.setString(4, sealId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The raid seal was reserved concurrently");
            }
        }
        return OperationOutcome.APPLIED;
    }

    private static OperationOutcome consume(
            Connection connection,
            UUID sealId,
            UUID eventId,
            UUID operationId,
            Instant consumedAt) throws SQLException {
        Optional<RaidSeal> current = load(connection, sealId);
        if (current.isEmpty()) {
            throw new PersistenceConflictException("Unknown raid seal " + sealId);
        }
        RaidSeal value = current.orElseThrow();
        if (value.status() == RaidSealStatus.CONSUMED
                && value.eventId().orElseThrow().equals(eventId)
                && value.consumptionOperationId().orElseThrow().equals(operationId)) {
            return OperationOutcome.ALREADY_APPLIED;
        }
        if (value.status() != RaidSealStatus.RESERVED
                || !value.eventId().orElseThrow().equals(eventId)) {
            throw new PersistenceConflictException(
                    "Only a reservation belonging to this event can be consumed");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE raid_seals
                SET state = 'CONSUMED', consumption_operation_id = ?, updated_at = ?
                WHERE seal_id = ? AND event_id = ? AND state = 'RESERVED'
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, consumedAt.toString());
            statement.setString(3, sealId.toString());
            statement.setString(4, eventId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException("The raid seal was consumed concurrently");
            }
        }
        return OperationOutcome.APPLIED;
    }

    private static Optional<RaidSeal> load(Connection connection, UUID sealId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT seal_id, owner_player_id, stage_level, state, event_id,
                       reservation_operation_id, consumption_operation_id,
                       refund_operation_id, created_at, updated_at
                FROM raid_seals WHERE seal_id = ?
                """)) {
            statement.setString(1, sealId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(sealFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<RaidSeal> loadByEvent(Connection connection, UUID eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT seal_id, owner_player_id, stage_level, state, event_id,
                       reservation_operation_id, consumption_operation_id,
                       refund_operation_id, created_at, updated_at
                FROM raid_seals WHERE event_id = ? AND state IN ('RESERVED', 'CONSUMED')
                """)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(sealFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<RaidSealReturn> loadReturn(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT returned_seal_id FROM raid_seal_returns WHERE return_operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new RaidSealReturn(uuid(resultSet.getString("returned_seal_id"))))
                        : Optional.empty();
            }
        }
    }

    private static RaidSeal sealFromRow(ResultSet resultSet) throws SQLException {
        return new RaidSeal(
                uuid(resultSet.getString("seal_id")),
                uuid(resultSet.getString("owner_player_id")),
                resultSet.getLong("stage_level"),
                RaidSealStatus.valueOf(resultSet.getString("state")),
                optionalUuid(resultSet.getString("event_id")),
                optionalUuid(resultSet.getString("reservation_operation_id")),
                optionalUuid(resultSet.getString("consumption_operation_id")),
                optionalUuid(resultSet.getString("refund_operation_id")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")));
    }

    private static UUID loadTeamOwner(Connection connection, UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_player_id FROM teams WHERE team_id = ?")) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("Unknown defense team " + teamId);
                }
                return uuid(resultSet.getString("owner_player_id"));
            }
        }
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
                String state = resultSet.getString("state");
                if (state.equals(DefensePhase.VICTORY.name())
                        || state.equals(DefensePhase.DEFEAT.name())
                        || state.equals(DefensePhase.ABORTED.name())
                        || state.equals(DefensePhase.RECOVERY.name())) {
                    throw new IllegalStateException(
                            "Cannot mutate a raid seal for a terminal defense event");
                }
            }
        }
    }

    private static void requireRecoveryEvent(Connection connection, UUID eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM defense_events WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("Unknown defense event " + eventId);
                }
                if (!DefensePhase.RECOVERY.name().equals(resultSet.getString("state"))) {
                    throw new PersistenceConflictException(
                            "A raid seal may only be refunded during technical recovery");
                }
            }
        }
    }

    private static void requireSealMutationArguments(
            UUID sealId,
            UUID eventId,
            UUID ownerPlayerId,
            long stageLevel,
            UUID operationId,
            Instant timestamp) {
        Objects.requireNonNull(sealId, "sealId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(timestamp, "timestamp");
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
    }

    private static UUID deterministicOperation(UUID eventId, String kind) {
        return UUID.nameUUIDFromBytes((eventId + "|" + kind).getBytes(StandardCharsets.UTF_8));
    }

    private <T> T read(String action, Database.SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw failure(action, exception);
        }
    }

    private static Optional<UUID> optionalUuid(String value) {
        return value == null ? Optional.empty() : Optional.of(uuid(value));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static Instant instant(String value) {
        return Instant.parse(value);
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

    private record RaidSealReturn(UUID returnedSealId) {
    }
}
