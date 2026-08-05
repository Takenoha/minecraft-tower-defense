package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.DefensePhase;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-owned virtual drops and reward queues.
 *
 * <p>No method in this class returns an ItemStack. A Paper listener may display a tagged entity,
 * but a pickup first goes through {@link #prepareClaim(UUID, UUID, UUID, int, UUID, Instant)} and
 * {@link #applyClaim(UUID, UUID, UUID, int, UUID, Instant)}. This keeps a physical entity from
 * becoming a usable item before the event has reached a normal terminal state.</p>
 */
public final class EscrowRepository {
    static final Duration DEFAULT_TEAM_QUEUE_RETENTION = Duration.ofDays(7L);

    private final Database database;

    public EscrowRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Creates one held drop before its visual entity is spawned. */
    public OperationOutcome prepare(
            EscrowDrop drop,
            UUID createOperationId,
            Instant createdAt) {
        Objects.requireNonNull(drop, "drop");
        Objects.requireNonNull(createOperationId, "createOperationId");
        Objects.requireNonNull(createdAt, "createdAt");
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<StoredEscrowDrop> existing = loadDrop(connection, drop.dropId());
                if (existing.isPresent()) {
                    StoredEscrowDrop value = existing.orElseThrow();
                    if (!value.drop().equals(drop)
                            || !loadCreateOperationId(connection, drop.dropId())
                                    .equals(createOperationId)) {
                        throw new PersistenceConflictException(
                                "The escrow drop UUID is already assigned to another payload");
                    }
                    return OperationOutcome.ALREADY_APPLIED;
                }
                requireActiveEvent(connection, drop.eventId());
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO event_drop_escrow(
                            drop_id, event_id, source_kind, source_id, item_id, item_payload,
                            quantity, status, display_entity_id, create_operation_id,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'HELD', ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, drop.dropId().toString());
                    statement.setString(2, drop.eventId().toString());
                    statement.setString(3, drop.sourceKind().name());
                    statement.setString(4, drop.sourceId().toString());
                    statement.setString(5, drop.itemId());
                    statement.setString(6, drop.itemPayload());
                    statement.setInt(7, drop.quantity());
                    statement.setString(8, drop.displayEntityId()
                            .map(UUID::toString)
                            .orElse(null));
                    statement.setString(9, createOperationId.toString());
                    statement.setString(10, createdAt.toString());
                    statement.setString(11, createdAt.toString());
                    statement.executeUpdate();
                }
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The escrow drop conflicts with persisted data", exception);
            }
            throw failure("prepare an escrow drop", exception);
        }
    }

    /** Associates or clears the visual entity which represents a held drop. */
    public void updateDisplayEntity(
            UUID eventId,
            UUID dropId,
            Optional<UUID> displayEntityId,
            Instant updatedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(dropId, "dropId");
        Optional<UUID> display = Objects.requireNonNull(displayEntityId, "displayEntityId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        try {
            database.inImmediateTransaction(connection -> {
                StoredEscrowDrop drop = requireDrop(connection, dropId);
                if (!drop.drop().eventId().equals(eventId)) {
                    throw new PersistenceConflictException(
                            "The escrow drop belongs to another defense event");
                }
                if (drop.status() != EscrowDropStatus.HELD && display.isPresent()) {
                    throw new IllegalStateException(
                            "A terminal escrow drop cannot receive a display entity");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_drop_escrow
                        SET display_entity_id = ?, updated_at = ?
                        WHERE drop_id = ?
                        """)) {
                    statement.setString(1, display.map(UUID::toString).orElse(null));
                    statement.setString(2, updatedAt.toString());
                    statement.setString(3, dropId.toString());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw failure("update an escrow display entity", exception);
        }
    }

    /** Clears stale physical display references after the Paper entity has been removed. */
    public void clearDisplayEntity(UUID eventId, UUID dropId, Instant clearedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(dropId, "dropId");
        Objects.requireNonNull(clearedAt, "clearedAt");
        try {
            database.inImmediateTransaction(connection -> {
                StoredEscrowDrop drop = requireDrop(connection, dropId);
                if (!drop.drop().eventId().equals(eventId)) {
                    throw new PersistenceConflictException(
                            "The escrow drop belongs to another defense event");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_drop_escrow
                        SET display_entity_id = NULL, updated_at = ?
                        WHERE drop_id = ?
                        """)) {
                    statement.setString(1, clearedAt.toString());
                    statement.setString(2, dropId.toString());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw failure("clear an escrow display entity", exception);
        }
    }

    /** Voids a drop prepared for a block action that could not be applied. */
    public OperationOutcome voidPreparedDrop(
            UUID eventId,
            UUID dropId,
            UUID operationId,
            Instant voidedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(dropId, "dropId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(voidedAt, "voidedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                requireActiveEvent(connection, eventId);
                StoredEscrowDrop drop = requireDrop(connection, dropId);
                if (!drop.drop().eventId().equals(eventId)) {
                    throw new PersistenceConflictException(
                            "The escrow drop belongs to another defense event");
                }
                String targetId = dropId + "|DISCARD";
                String fingerprint = sha256(dropId + "|DISCARD");
                if (drop.status() == EscrowDropStatus.VOIDED) {
                    ensureResourceOperationApplied(
                            connection,
                            operationId,
                            eventId,
                            "DROP_VOID",
                            targetId,
                            fingerprint,
                            voidedAt);
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (drop.status() != EscrowDropStatus.HELD) {
                    throw new PersistenceConflictException(
                            "Only a held escrow drop can be voided before termination");
                }
                ensureResourceOperationApplied(
                        connection,
                        operationId,
                        eventId,
                        "DROP_VOID",
                        targetId,
                        fingerprint,
                        voidedAt);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_drop_escrow
                        SET status = 'VOIDED', display_entity_id = NULL, updated_at = ?
                        WHERE drop_id = ? AND status = 'HELD'
                        """)) {
                    statement.setString(1, voidedAt.toString());
                    statement.setString(2, dropId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The escrow drop was concurrently resolved");
                    }
                }
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("void an unapplied block drop", exception);
        }
    }

    /** Reserves a pickup operation without changing the claimed quantity. */
    public OperationOutcome prepareClaim(
            UUID eventId,
            UUID dropId,
            UUID recipientId,
            int quantity,
            UUID operationId,
            Instant preparedAt) {
        requireClaimArguments(eventId, dropId, recipientId, quantity, operationId, preparedAt);
        String targetId = claimTarget(dropId, recipientId);
        String fingerprint = claimFingerprint(dropId, recipientId, quantity);
        try {
            return database.inImmediateTransaction(connection -> {
                requireActiveEvent(connection, eventId);
                requireRegisteredParticipant(connection, eventId, recipientId);
                StoredEscrowDrop drop = requireDrop(connection, dropId);
                if (!drop.drop().eventId().equals(eventId)) {
                    throw new PersistenceConflictException(
                            "The escrow drop belongs to another defense event");
                }
                if (drop.status() != EscrowDropStatus.HELD
                        || quantity > drop.remainingQuantity()) {
                    throw new PersistenceConflictException(
                            "The escrow drop has no remaining quantity for this claim");
                }
                Optional<ResourceOperation> existing = loadResourceOperation(
                        connection, eventId, "DROP_CLAIM", targetId);
                if (existing.isPresent()) {
                    ResourceOperation value = existing.orElseThrow();
                    requireMatchingResourceOperation(
                            value, operationId, eventId, "DROP_CLAIM", targetId, fingerprint);
                    return value.state() == ResourceOperationState.APPLIED
                            ? OperationOutcome.ALREADY_APPLIED
                            : OperationOutcome.APPLIED;
                }
                Optional<ResourceOperation> sameOperation = loadResourceOperation(
                        connection, operationId);
                if (sameOperation.isPresent()) {
                    requireMatchingResourceOperation(
                            sameOperation.orElseThrow(),
                            operationId,
                            eventId,
                            "DROP_CLAIM",
                            targetId,
                            fingerprint);
                    return OperationOutcome.ALREADY_APPLIED;
                }
                insertResourceOperation(
                        connection,
                        operationId,
                        eventId,
                        "DROP_CLAIM",
                        targetId,
                        fingerprint,
                        preparedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The escrow claim conflicts with persisted data", exception);
            }
            throw failure("prepare an escrow claim", exception);
        }
    }

    /** Applies a prepared pickup and records it against the registered participant. */
    public EscrowClaimResult applyClaim(
            UUID eventId,
            UUID dropId,
            UUID recipientId,
            int quantity,
            UUID operationId,
            Instant claimedAt) {
        requireClaimArguments(eventId, dropId, recipientId, quantity, operationId, claimedAt);
        String targetId = claimTarget(dropId, recipientId);
        String fingerprint = claimFingerprint(dropId, recipientId, quantity);
        try {
            return database.inImmediateTransaction(connection -> {
                ResourceOperation operation = requireResourceOperation(
                        connection, operationId, eventId, "DROP_CLAIM", targetId, fingerprint);
                if (operation.state() == ResourceOperationState.APPLIED) {
                    StoredEscrowDrop existingDrop = requireDrop(connection, dropId);
                    Optional<ResourcePickupFeedback> feedback = loadResourcePickupFeedback(
                            connection,
                            eventId,
                            existingDrop.drop().itemId(),
                            recipientId,
                            quantity);
                    return new EscrowClaimResult(
                            OperationOutcome.ALREADY_APPLIED, quantity, feedback);
                }
                requireActiveEvent(connection, eventId);
                requireRegisteredParticipant(connection, eventId, recipientId);
                StoredEscrowDrop drop = requireDrop(connection, dropId);
                if (!drop.drop().eventId().equals(eventId)
                        || drop.status() != EscrowDropStatus.HELD) {
                    throw new PersistenceConflictException(
                            "The escrow drop is not claimable");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_drop_escrow
                        SET claimed_quantity = claimed_quantity + ?, updated_at = ?
                        WHERE drop_id = ? AND status = 'HELD'
                          AND claimed_quantity + ? <= quantity
                        """)) {
                    statement.setInt(1, quantity);
                    statement.setString(2, claimedAt.toString());
                    statement.setString(3, dropId.toString());
                    statement.setInt(4, quantity);
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The escrow drop was claimed by another operation");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO event_drop_claims(
                            event_id, drop_id, recipient_id, quantity, operation_id, claimed_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(event_id, drop_id, recipient_id) DO UPDATE SET
                            quantity = event_drop_claims.quantity + excluded.quantity,
                            operation_id = excluded.operation_id,
                            claimed_at = excluded.claimed_at
                        """)) {
                    statement.setString(1, eventId.toString());
                    statement.setString(2, dropId.toString());
                    statement.setString(3, recipientId.toString());
                    statement.setInt(4, quantity);
                    statement.setString(5, operationId.toString());
                    statement.setString(6, claimedAt.toString());
                    statement.executeUpdate();
                }
                markResourceOperationApplied(connection, operationId, claimedAt);
                Optional<ResourcePickupFeedback> feedback = loadResourcePickupFeedback(
                        connection,
                        eventId,
                        drop.drop().itemId(),
                        recipientId,
                        quantity);
                return new EscrowClaimResult(OperationOutcome.APPLIED, quantity, feedback);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The escrow claim conflicts with persisted participant data", exception);
            }
            throw failure("apply an escrow claim", exception);
        }
    }

    private static Optional<ResourcePickupFeedback> loadResourcePickupFeedback(
            Connection connection,
            UUID eventId,
            String itemId,
            UUID recipientId,
            int quantity) throws SQLException {
        Optional<ResourceType> resourceType = ResourceType.fromItemId(itemId);
        if (resourceType.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ResourceRepository.loadPickupFeedback(
                connection, eventId, recipientId, resourceType.orElseThrow(), quantity));
    }

    /** Convenience method for callers which do not need to interleave a Paper entity action. */
    public EscrowClaimResult claim(
            UUID eventId,
            UUID dropId,
            UUID recipientId,
            int quantity,
            UUID operationId,
            Instant claimedAt) {
        OperationOutcome prepared = prepareClaim(
                eventId, dropId, recipientId, quantity, operationId, claimedAt);
        if (prepared == OperationOutcome.ALREADY_APPLIED) {
            return new EscrowClaimResult(prepared, quantity);
        }
        return applyClaim(eventId, dropId, recipientId, quantity, operationId, claimedAt);
    }

    /** Settles all held drops for a normal terminal event and issues durable reward queue rows. */
    public OperationOutcome settleEvent(
            UUID eventId,
            UUID terminalOperationId,
            DefensePhase terminalPhase,
            Instant settledAt) {
        return settleEvent(
                eventId,
                terminalOperationId,
                terminalPhase,
                settledAt,
                DEFAULT_TEAM_QUEUE_RETENTION);
    }

    /** Settles an event with an explicit TEAM queue retention duration. */
    public OperationOutcome settleEvent(
            UUID eventId,
            UUID terminalOperationId,
            DefensePhase terminalPhase,
            Instant settledAt,
            Duration teamQueueRetention) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(terminalOperationId, "terminalOperationId");
        Objects.requireNonNull(terminalPhase, "terminalPhase");
        Objects.requireNonNull(settledAt, "settledAt");
        requirePositiveDuration(teamQueueRetention);
        if (terminalPhase != DefensePhase.VICTORY
                && terminalPhase != DefensePhase.DEFEAT
                && terminalPhase != DefensePhase.ABORTED) {
            throw new IllegalArgumentException("settleEvent requires a normal terminal phase");
        }
        try {
            return database.inImmediateTransaction(connection -> {
                requireTerminalEvent(connection, eventId, terminalPhase);
                if (hasOnlySettledDrops(connection, eventId)) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                settleForTerminal(
                        connection,
                        eventId,
                        terminalOperationId,
                        terminalPhase,
                        settledAt,
                        teamQueueRetention);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The escrow settlement conflicts with persisted queue data", exception);
            }
            throw failure("settle event drops", exception);
        }
    }

    /** Voids held drops during technical recovery; claimed quantities remain audit data only. */
    public OperationOutcome voidEvent(
            UUID eventId,
            UUID recoveryOperationId,
            Instant voidedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(recoveryOperationId, "recoveryOperationId");
        Objects.requireNonNull(voidedAt, "voidedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                requireTerminalEvent(connection, eventId, DefensePhase.RECOVERY);
                if (hasOnlyVoidedDrops(connection, eventId)) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                voidForRecovery(connection, eventId, recoveryOperationId, voidedAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("void event drops", exception);
        }
    }

    /** Loads escrow in stable drop UUID order. */
    public List<StoredEscrowDrop> loadDrops(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load escrow drops", connection -> {
            List<StoredEscrowDrop> drops = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT drop_id, event_id, source_kind, source_id, item_id, item_payload,
                           quantity, claimed_quantity, status, display_entity_id,
                           create_operation_id, created_at, updated_at
                    FROM event_drop_escrow WHERE event_id = ? ORDER BY drop_id
                    """)) {
                statement.setString(1, eventId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        drops.add(dropFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(drops);
        });
    }

    /** Loads all pickup claims for one event. */
    public List<EscrowClaim> loadClaims(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load escrow claims", connection -> {
            List<EscrowClaim> claims = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT event_id, drop_id, recipient_id, quantity, operation_id, claimed_at
                    FROM event_drop_claims WHERE event_id = ?
                    ORDER BY drop_id, recipient_id
                    """)) {
                statement.setString(1, eventId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        claims.add(new EscrowClaim(
                                uuid(resultSet.getString("event_id")),
                                uuid(resultSet.getString("drop_id")),
                                uuid(resultSet.getString("recipient_id")),
                                resultSet.getInt("quantity"),
                                uuid(resultSet.getString("operation_id")),
                                instant(resultSet.getString("claimed_at"))));
                    }
                }
            }
            return List.copyOf(claims);
        });
    }

    /** Loads all pending and delivered reward queue rows for one event. */
    public List<RewardQueueEntry> loadRewardQueue(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return read("load the reward queue", connection -> {
            List<RewardQueueEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT queue_id, event_id, scope, recipient_id, item_id, item_payload,
                           quantity, source_drop_id, status, issued_operation_id,
                           created_at, updated_at, team_claim_deadline
                    FROM event_reward_queue WHERE event_id = ? ORDER BY queue_id
                    """)) {
                statement.setString(1, eventId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(rewardQueueFromRow(resultSet));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }

    /** Loads pending rows which a player is currently allowed to receive. */
    public List<RewardQueueEntry> loadPendingRewardQueueForPlayer(UUID playerId) {
        return loadPendingRewardQueueForPlayer(playerId, Instant.now());
    }

    /**
     * Loads pending rows which a player is allowed to receive at a supplied point in time.
     *
     * <p>TEAM rows remain claimable by registered event participants indefinitely. Once a
     * row's retention deadline has passed, the current team owner is also eligible. Legacy
     * rows without a deadline deliberately remain participant-only.</p>
     */
    public List<RewardQueueEntry> loadPendingRewardQueueForPlayer(
            UUID playerId,
            Instant at) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(at, "at");
        return read("load pending rewards for a player", connection -> {
            Map<UUID, RewardQueueEntry> entries = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
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
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        RewardQueueEntry entry = rewardQueueFromRow(resultSet);
                        entries.put(entry.queueId(), entry);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
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
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        RewardQueueEntry entry = rewardQueueFromRow(resultSet);
                        if (entry.teamClaimDeadline().isPresent()
                                && !at.isBefore(entry.teamClaimDeadline().orElseThrow())) {
                            entries.put(entry.queueId(), entry);
                        }
                    }
                }
            }
            List<RewardQueueEntry> ordered = new ArrayList<>(entries.values());
            ordered.sort(Comparator.comparing(RewardQueueEntry::createdAt)
                    .thenComparing(RewardQueueEntry::queueId));
            return List.copyOf(ordered);
        });
    }

    /** Loads one queue row for reconciliation after a Paper-side delivery retry. */
    public Optional<RewardQueueEntry> findRewardQueue(UUID queueId) {
        Objects.requireNonNull(queueId, "queueId");
        return read("load a reward queue row", connection -> loadRewardQueue(connection, queueId));
    }

    /**
     * Commits one physical inventory delivery after Paper has accepted the item stack.
     *
     * <p>The operation and the queue transition are one SQLite transaction. The Paper caller
     * uses a queue UUID receipt on the accepted ItemStack, so a stop between inventory mutation
     * and this method is recoverable without issuing a second queue row.</p>
     */
    public RewardDeliveryOutcome prepareRewardDelivery(
            UUID queueId,
            UUID playerId,
            UUID operationId,
            Instant preparedAt) {
        Objects.requireNonNull(queueId, "queueId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        try {
            return database.inImmediateTransaction(connection -> {
                RewardQueueEntry entry = loadRewardQueue(connection, queueId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "Unknown reward queue row " + queueId));
                requireAuthorizedRewardRecipient(connection, entry, playerId, preparedAt);
                if (entry.status() == RewardQueueStatus.DELIVERED) {
                    return RewardDeliveryOutcome.ALREADY_DELIVERED;
                }
                if (entry.status() == RewardQueueStatus.VOIDED) {
                    return RewardDeliveryOutcome.VOIDED;
                }

                String fingerprint = rewardDeliveryFingerprint(entry, playerId);
                Optional<RewardDeliveryOperation> existing =
                        loadRewardDeliveryOperationForQueue(connection, queueId);
                if (existing.isPresent()) {
                    RewardDeliveryOperation operation = existing.orElseThrow();
                    if (!operation.playerId().equals(playerId)) {
                        return RewardDeliveryOutcome.HELD_BY_OTHER;
                    }
                    requireMatchingRewardDelivery(
                            operation, operation.operationId(), entry, playerId, fingerprint);
                    if (!operation.operationId().equals(operationId)) {
                        throw new PersistenceConflictException(
                                "The reward queue is already reserved by this player"
                                        + " with another operation UUID");
                    }
                    return operation.state() == RewardDeliveryState.APPLIED
                            ? RewardDeliveryOutcome.ALREADY_DELIVERED
                            : RewardDeliveryOutcome.ALREADY_ACQUIRED;
                }
                if (loadRewardDeliveryOperation(connection, operationId).isPresent()) {
                    throw new PersistenceConflictException(
                            "The reward delivery operation UUID is already in use");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO event_reward_delivery_operations(
                            operation_id, queue_id, event_id, player_id, quantity,
                            payload_fingerprint, state, prepared_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                        """)) {
                    statement.setString(1, operationId.toString());
                    statement.setString(2, queueId.toString());
                    statement.setString(3, entry.eventId().toString());
                    statement.setString(4, playerId.toString());
                    statement.setInt(5, entry.quantity());
                    statement.setString(6, fingerprint);
                    statement.setString(7, preparedAt.toString());
                    statement.executeUpdate();
                }
                return RewardDeliveryOutcome.ACQUIRED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The reward delivery reservation conflicts with persisted data", exception);
            }
            throw failure("reserve a reward delivery", exception);
        }
    }

    /**
     * Commits one physical inventory delivery after Paper has accepted the item stack.
     *
     * <p>The operation and the queue transition are one SQLite transaction. The Paper caller
     * uses a queue UUID receipt on the accepted ItemStack, so a stop between inventory mutation
     * and this method is recoverable without issuing a second queue row.</p>
     */
    public OperationOutcome markRewardDelivered(
            UUID queueId,
            UUID playerId,
            UUID operationId,
            Instant deliveredAt) {
        Objects.requireNonNull(queueId, "queueId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        try {
            return database.inImmediateTransaction(connection -> {
                RewardQueueEntry entry = loadRewardQueue(connection, queueId).orElseThrow(
                        () -> new PersistenceConflictException(
                                "Unknown reward queue row " + queueId));
                requireAuthorizedRewardRecipient(connection, entry, playerId, deliveredAt);
                if (entry.status() == RewardQueueStatus.DELIVERED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (entry.status() == RewardQueueStatus.VOIDED) {
                    throw new PersistenceConflictException(
                            "A voided reward queue row cannot be delivered");
                }

                String fingerprint = rewardDeliveryFingerprint(entry, playerId);
                RewardDeliveryOperation operation = loadRewardDeliveryOperationForQueue(
                        connection, queueId).orElseThrow(
                                () -> new PersistenceConflictException(
                                        "The reward delivery was not reserved first"));
                requireMatchingRewardDelivery(
                        operation, operationId, entry, playerId, fingerprint);
                if (operation.state() == RewardDeliveryState.APPLIED) {
                    return OperationOutcome.ALREADY_APPLIED;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_reward_delivery_operations
                        SET state = 'APPLIED', applied_at = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """)) {
                    statement.setString(1, deliveredAt.toString());
                    statement.setString(2, operationId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The reward delivery reservation was concurrently resolved");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE event_reward_queue
                        SET status = 'DELIVERED', updated_at = ?
                        WHERE queue_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setString(1, deliveredAt.toString());
                    statement.setString(2, queueId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The reward queue row was concurrently resolved");
                    }
                }
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new PersistenceConflictException(
                        "The reward delivery operation conflicts with persisted data", exception);
            }
            throw failure("mark a reward as delivered", exception);
        }
    }

    /** Package-private hook used by the event terminal transaction. */
    static void settleForTerminal(
            Connection connection,
            UUID eventId,
            UUID terminalOperationId,
            DefensePhase terminalPhase,
            Instant settledAt) throws SQLException {
        settleForTerminal(
                connection,
                eventId,
                terminalOperationId,
                terminalPhase,
                settledAt,
                DEFAULT_TEAM_QUEUE_RETENTION);
    }

    static void settleForTerminal(
            Connection connection,
            UUID eventId,
            UUID terminalOperationId,
            DefensePhase terminalPhase,
            Instant settledAt,
            Duration teamQueueRetention) throws SQLException {
        requirePositiveDuration(teamQueueRetention);
        UUID teamId = loadTeamId(connection, eventId);
        Instant teamClaimDeadline = settledAt.plus(teamQueueRetention);
        ResourceRepository.settleForTerminal(
                connection,
                eventId,
                terminalOperationId,
                terminalPhase,
                settledAt);
        List<StoredEscrowDrop> drops = loadDrops(connection, eventId);
        for (StoredEscrowDrop drop : drops) {
            if (drop.status() != EscrowDropStatus.HELD) {
                continue;
            }
            UUID settleOperationId = deterministicOperation(
                    terminalOperationId, "DROP_SETTLE", drop.drop().dropId().toString());
            ensureResourceOperationApplied(
                    connection,
                    settleOperationId,
                    eventId,
                    "DROP_SETTLE",
                    drop.drop().dropId().toString(),
                    sha256(drop.drop().dropId() + "|" + terminalPhase),
                    settledAt);

            if (ResourceRepository.isWalletResource(drop.drop().itemId())) {
                markSettled(connection, drop, settledAt);
                continue;
            }

            for (EscrowClaim claim : loadClaims(connection, eventId, drop.drop().dropId())) {
                UUID issueOperationId = deterministicOperation(
                        terminalOperationId,
                        "PLAYER",
                        drop.drop().dropId() + "|" + claim.recipientId());
                issueReward(
                        connection,
                        issueOperationId,
                        eventId,
                        RewardQueueScope.PLAYER,
                        claim.recipientId(),
                        drop,
                        claim.quantity(),
                        Optional.empty(),
                        settledAt);
            }
            int remaining = drop.remainingQuantity();
            if (terminalPhase == DefensePhase.VICTORY && remaining > 0) {
                UUID issueOperationId = deterministicOperation(
                        terminalOperationId, "TEAM", drop.drop().dropId().toString());
                issueReward(
                        connection,
                        issueOperationId,
                        eventId,
                        RewardQueueScope.TEAM,
                        teamId,
                        drop,
                        remaining,
                        Optional.of(teamClaimDeadline),
                        settledAt);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE event_drop_escrow
                    SET status = 'SETTLED', display_entity_id = NULL, updated_at = ?
                    WHERE drop_id = ? AND status = 'HELD'
                    """)) {
                statement.setString(1, settledAt.toString());
                statement.setString(2, drop.drop().dropId().toString());
                statement.executeUpdate();
            }
        }
    }

    /** Package-private hook used by technical event recovery. */
    static void voidForRecovery(
            Connection connection,
            UUID eventId,
            UUID recoveryOperationId,
            Instant voidedAt) throws SQLException {
        ResourceRepository.settleForRecovery(
                connection,
                eventId,
                recoveryOperationId,
                voidedAt);
        for (StoredEscrowDrop drop : loadDrops(connection, eventId)) {
            if (drop.status() != EscrowDropStatus.HELD) {
                continue;
            }
            UUID voidOperationId = deterministicOperation(
                    recoveryOperationId, "DROP_VOID", drop.drop().dropId().toString());
            ensureResourceOperationApplied(
                    connection,
                    voidOperationId,
                    eventId,
                    "DROP_VOID",
                    drop.drop().dropId().toString(),
                    sha256(drop.drop().dropId() + "|VOID"),
                    voidedAt);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE event_drop_escrow
                    SET status = 'VOIDED', display_entity_id = NULL, updated_at = ?
                    WHERE drop_id = ? AND status = 'HELD'
                    """)) {
                statement.setString(1, voidedAt.toString());
                statement.setString(2, drop.drop().dropId().toString());
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_reward_queue SET status = 'VOIDED', updated_at = ?
                WHERE event_id = ? AND status = 'PENDING'
                """)) {
            statement.setString(1, voidedAt.toString());
            statement.setString(2, eventId.toString());
            statement.executeUpdate();
        }
    }

    private static void markSettled(
            Connection connection,
            StoredEscrowDrop drop,
            Instant settledAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_drop_escrow
                SET status = 'SETTLED', display_entity_id = NULL, updated_at = ?
                WHERE drop_id = ? AND status = 'HELD'
                """)) {
            statement.setString(1, settledAt.toString());
            statement.setString(2, drop.drop().dropId().toString());
            statement.executeUpdate();
        }
    }

    private static void issueReward(
            Connection connection,
            UUID issueOperationId,
            UUID eventId,
            RewardQueueScope scope,
            UUID recipientId,
            StoredEscrowDrop drop,
            int quantity,
            Optional<Instant> teamClaimDeadline,
            Instant issuedAt) throws SQLException {
        if (quantity <= 0) {
            return;
        }
        String targetId = drop.drop().dropId() + "|" + scope + "|" + recipientId;
        String fingerprint = sha256(drop.drop().dropId() + "|" + scope + "|"
                + recipientId + "|" + drop.drop().itemId() + "|" + quantity);
        ensureResourceOperationApplied(
                connection,
                issueOperationId,
                eventId,
                "REWARD_ISSUE",
                targetId,
                fingerprint,
                issuedAt);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_reward_queue(
                    queue_id, event_id, scope, recipient_id, item_id, item_payload,
                    quantity, source_drop_id, status, issued_operation_id, created_at, updated_at,
                    team_claim_deadline
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                ON CONFLICT(event_id, source_drop_id, scope, recipient_id) DO NOTHING
                """)) {
            statement.setString(1, deterministicOperation(issueOperationId, "QUEUE", targetId)
                    .toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, scope.name());
            statement.setString(4, recipientId.toString());
            statement.setString(5, drop.drop().itemId());
            statement.setString(6, drop.drop().itemPayload());
            statement.setInt(7, quantity);
            statement.setString(8, drop.drop().dropId().toString());
            statement.setString(9, issueOperationId.toString());
            statement.setString(10, issuedAt.toString());
            statement.setString(11, issuedAt.toString());
            statement.setString(12, teamClaimDeadline.map(Instant::toString).orElse(null));
            statement.executeUpdate();
        }
    }

    private static List<StoredEscrowDrop> loadDrops(Connection connection, UUID eventId)
            throws SQLException {
        List<StoredEscrowDrop> drops = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT drop_id, event_id, source_kind, source_id, item_id, item_payload,
                       quantity, claimed_quantity, status, display_entity_id,
                       create_operation_id, created_at, updated_at
                FROM event_drop_escrow WHERE event_id = ? ORDER BY drop_id
                """)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    drops.add(dropFromRow(resultSet));
                }
            }
        }
        return drops;
    }

    private static List<EscrowClaim> loadClaims(
            Connection connection, UUID eventId, UUID dropId) throws SQLException {
        List<EscrowClaim> claims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, drop_id, recipient_id, quantity, operation_id, claimed_at
                FROM event_drop_claims WHERE event_id = ? AND drop_id = ?
                ORDER BY recipient_id
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, dropId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    claims.add(new EscrowClaim(
                            uuid(resultSet.getString("event_id")),
                            uuid(resultSet.getString("drop_id")),
                            uuid(resultSet.getString("recipient_id")),
                            resultSet.getInt("quantity"),
                            uuid(resultSet.getString("operation_id")),
                            instant(resultSet.getString("claimed_at"))));
                }
            }
        }
        return claims;
    }

    private static Optional<RewardQueueEntry> loadRewardQueue(
            Connection connection, UUID queueId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT queue_id, event_id, scope, recipient_id, item_id, item_payload,
                       quantity, source_drop_id, status, issued_operation_id,
                       created_at, updated_at, team_claim_deadline
                FROM event_reward_queue WHERE queue_id = ?
                """)) {
            statement.setString(1, queueId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(rewardQueueFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static RewardQueueEntry rewardQueueFromRow(ResultSet resultSet)
            throws SQLException {
        return new RewardQueueEntry(
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
                resultSet.getString("team_claim_deadline") == null
                        ? Optional.empty()
                        : Optional.of(instant(resultSet.getString("team_claim_deadline"))));
    }

    private static void requireAuthorizedRewardRecipient(
            Connection connection,
            RewardQueueEntry entry,
            UUID playerId,
            Instant at) throws SQLException {
        if (entry.scope() == RewardQueueScope.PLAYER) {
            if (!entry.recipientId().equals(playerId)) {
                throw new PersistenceConflictException(
                        "Only the personal reward recipient may receive this queue row");
            }
            return;
        }
        if (entry.teamClaimDeadline().isPresent()
                && !at.isBefore(entry.teamClaimDeadline().orElseThrow())) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1
                    FROM defense_events e
                    JOIN teams t ON t.team_id = e.team_id
                    WHERE e.event_id = ?
                      AND e.team_id = ?
                      AND t.owner_player_id = ?
                    """)) {
                statement.setString(1, entry.eventId().toString());
                statement.setString(2, entry.recipientId().toString());
                statement.setString(3, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return;
                    }
                }
            }
            throw new PersistenceConflictException(
                    "Only the current owner of the reward team may receive this expired queue row");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM defense_events e
                JOIN team_members m ON m.team_id = e.team_id AND m.player_id = ?
                JOIN event_participants p
                  ON p.event_id = e.event_id AND p.player_id = m.player_id
                WHERE e.event_id = ?
                  AND e.team_id = ?
                  AND p.registered = 1
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, entry.eventId().toString());
            statement.setString(3, entry.recipientId().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException(
                            "Only a registered member of the reward team may receive this queue row");
                }
            }
        }
    }

    private static void requirePositiveDuration(Duration duration) {
        Objects.requireNonNull(duration, "teamQueueRetention");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("teamQueueRetention must be positive");
        }
    }

    private static String rewardDeliveryFingerprint(RewardQueueEntry entry, UUID playerId) {
        return sha256(entry.queueId() + "|" + entry.eventId() + "|" + playerId + "|"
                + entry.itemId() + "|" + entry.quantity());
    }

    private static Optional<RewardDeliveryOperation> loadRewardDeliveryOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, queue_id, event_id, player_id, quantity,
                       payload_fingerprint, state, prepared_at, applied_at
                FROM event_reward_delivery_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(rewardDeliveryOperationFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<RewardDeliveryOperation> loadRewardDeliveryOperationForQueue(
            Connection connection, UUID queueId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, queue_id, event_id, player_id, quantity,
                       payload_fingerprint, state, prepared_at, applied_at
                FROM event_reward_delivery_operations WHERE queue_id = ?
                """)) {
            statement.setString(1, queueId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(rewardDeliveryOperationFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static RewardDeliveryOperation rewardDeliveryOperationFromRow(
            ResultSet resultSet) throws SQLException {
        return new RewardDeliveryOperation(
                uuid(resultSet.getString("operation_id")),
                uuid(resultSet.getString("queue_id")),
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("player_id")),
                resultSet.getInt("quantity"),
                resultSet.getString("payload_fingerprint"),
                RewardDeliveryState.valueOf(resultSet.getString("state")),
                instant(resultSet.getString("prepared_at")),
                resultSet.getString("applied_at") == null
                        ? Optional.empty()
                        : Optional.of(instant(resultSet.getString("applied_at"))));
    }

    private static void requireMatchingRewardDelivery(
            RewardDeliveryOperation operation,
            UUID operationId,
            RewardQueueEntry entry,
            UUID playerId,
            String fingerprint) {
        if (!operation.operationId().equals(operationId)
                || !operation.queueId().equals(entry.queueId())
                || !operation.eventId().equals(entry.eventId())
                || !operation.playerId().equals(playerId)
                || operation.quantity() != entry.quantity()
                || !operation.payloadFingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The reward delivery operation UUID is already assigned to another payload");
        }
    }

    private static StoredEscrowDrop dropFromRow(ResultSet resultSet) throws SQLException {
        String displayEntity = resultSet.getString("display_entity_id");
        EscrowDrop drop = new EscrowDrop(
                uuid(resultSet.getString("event_id")),
                uuid(resultSet.getString("drop_id")),
                DropSourceKind.valueOf(resultSet.getString("source_kind")),
                uuid(resultSet.getString("source_id")),
                resultSet.getString("item_id"),
                resultSet.getString("item_payload"),
                resultSet.getInt("quantity"),
                displayEntity == null ? Optional.empty() : Optional.of(uuid(displayEntity)));
        return new StoredEscrowDrop(
                drop,
                resultSet.getInt("claimed_quantity"),
                EscrowDropStatus.valueOf(resultSet.getString("status")),
                instant(resultSet.getString("created_at")),
                instant(resultSet.getString("updated_at")));
    }

    private static UUID loadCreateOperationId(Connection connection, UUID dropId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT create_operation_id FROM event_drop_escrow WHERE drop_id = ?")) {
            statement.setString(1, dropId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("Unknown escrow drop " + dropId);
                }
                return uuid(resultSet.getString("create_operation_id"));
            }
        }
    }

    private static Optional<StoredEscrowDrop> loadDrop(Connection connection, UUID dropId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT drop_id, event_id, source_kind, source_id, item_id, item_payload,
                       quantity, claimed_quantity, status, display_entity_id,
                       create_operation_id, created_at, updated_at
                FROM event_drop_escrow WHERE drop_id = ?
                """)) {
            statement.setString(1, dropId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(dropFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static StoredEscrowDrop requireDrop(Connection connection, UUID dropId)
            throws SQLException {
        return loadDrop(connection, dropId).orElseThrow(
                () -> new PersistenceConflictException("Unknown escrow drop " + dropId));
    }

    private static void requireClaimArguments(
            UUID eventId,
            UUID dropId,
            UUID recipientId,
            int quantity,
            UUID operationId,
            Instant timestamp) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(dropId, "dropId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(timestamp, "timestamp");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
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
                if (isTerminal(resultSet.getString("state"))) {
                    throw new IllegalStateException(
                            "Cannot mutate escrow for a terminal defense event");
                }
            }
        }
    }

    private static void requireTerminalEvent(
            Connection connection, UUID eventId, DefensePhase expectedPhase) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM defense_events WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("Unknown defense event " + eventId);
                }
                if (!expectedPhase.name().equals(resultSet.getString("state"))) {
                    throw new PersistenceConflictException(
                            "The escrow terminal phase does not match the persisted event state");
                }
            }
        }
    }

    private static void requireRegisteredParticipant(
            Connection connection, UUID eventId, UUID recipientId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM event_participants
                WHERE event_id = ? AND player_id = ? AND registered = 1
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, recipientId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException(
                            "Only a registered participant may claim an event drop");
                }
            }
        }
    }

    private static UUID loadTeamId(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT team_id FROM defense_events WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("Unknown defense event " + eventId);
                }
                return uuid(resultSet.getString("team_id"));
            }
        }
    }

    private static boolean hasOnlySettledDrops(Connection connection, UUID eventId)
            throws SQLException {
        return hasNoDropsOtherThan(connection, eventId, "SETTLED");
    }

    private static boolean hasOnlyVoidedDrops(Connection connection, UUID eventId)
            throws SQLException {
        return hasNoDropsOtherThan(connection, eventId, "VOIDED");
    }

    private static boolean hasNoDropsOtherThan(
            Connection connection, UUID eventId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM event_drop_escrow
                WHERE event_id = ? AND status <> ? LIMIT 1
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, status);
            try (ResultSet resultSet = statement.executeQuery()) {
                return !resultSet.next();
            }
        }
    }

    private static void ensureResourceOperationApplied(
            Connection connection,
            UUID operationId,
            UUID eventId,
            String kind,
            String targetId,
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
                connection, operationId, eventId, kind, targetId, fingerprint, timestamp);
        markResourceOperationApplied(connection, operationId, timestamp);
    }

    private static ResourceOperation requireResourceOperation(
            Connection connection,
            UUID operationId,
            UUID eventId,
            String kind,
            String targetId,
            String fingerprint) throws SQLException {
        ResourceOperation operation = loadResourceOperation(connection, operationId).orElseThrow(
                () -> new PersistenceConflictException(
                        "The escrow operation was not prepared: " + operationId));
        requireMatchingResourceOperation(
                operation, operationId, eventId, kind, targetId, fingerprint);
        return operation;
    }

    private static void requireMatchingResourceOperation(
            ResourceOperation operation,
            UUID operationId,
            UUID eventId,
            String kind,
            String targetId,
            String fingerprint) {
        if (!operation.operationId().equals(operationId)
                || !operation.eventId().equals(eventId)
                || !operation.kind().equals(kind)
                || !operation.targetId().equals(targetId)
                || !operation.fingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The escrow operation UUID is already assigned to another payload");
        }
    }

    private static void insertResourceOperation(
            Connection connection,
            UUID operationId,
            UUID eventId,
            String kind,
            String targetId,
            String fingerprint,
            Instant preparedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_mutation_operations(
                    operation_id, event_id, operation_kind, target_id,
                    payload_fingerprint, state, prepared_at
                ) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, kind);
            statement.setString(4, targetId);
            statement.setString(5, fingerprint);
            statement.setString(6, preparedAt.toString());
            statement.executeUpdate();
        }
    }

    private static void markResourceOperationApplied(
            Connection connection, UUID operationId, Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE event_mutation_operations
                SET state = 'APPLIED', applied_at = ?
                WHERE operation_id = ? AND state = 'PREPARED'
                """)) {
            statement.setString(1, appliedAt.toString());
            statement.setString(2, operationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceConflictException(
                        "The escrow operation was already applied or disappeared");
            }
        }
    }

    private static Optional<ResourceOperation> loadResourceOperation(
            Connection connection,
            UUID eventId,
            String kind,
            String targetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, event_id, operation_kind, target_id,
                       payload_fingerprint, state, prepared_at, applied_at
                FROM event_mutation_operations
                WHERE event_id = ? AND operation_kind = ? AND target_id = ?
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, kind);
            statement.setString(3, targetId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(resourceOperationFromRow(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static Optional<ResourceOperation> loadResourceOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, event_id, operation_kind, target_id,
                       payload_fingerprint, state, prepared_at, applied_at
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
                appliedAt == null ? Optional.empty() : Optional.of(instant(appliedAt)));
    }

    private static UUID deterministicOperation(UUID base, String namespace, String value) {
        return UUID.nameUUIDFromBytes((base + "|" + namespace + "|" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String claimTarget(UUID dropId, UUID recipientId) {
        return dropId + "|" + recipientId;
    }

    private static String claimFingerprint(UUID dropId, UUID recipientId, int quantity) {
        return sha256(dropId + "|" + recipientId + "|" + quantity);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime must provide SHA-256", exception);
        }
    }

    private static boolean isTerminal(String state) {
        return "VICTORY".equals(state)
                || "DEFEAT".equals(state)
                || "ABORTED".equals(state)
                || "RECOVERY".equals(state);
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

    private record ResourceOperation(
            UUID operationId,
            UUID eventId,
            String kind,
            String targetId,
            String fingerprint,
            ResourceOperationState state,
            Instant preparedAt,
            Optional<Instant> appliedAt) {
    }

    private enum ResourceOperationState {
        PREPARED,
        APPLIED
    }

    private record RewardDeliveryOperation(
            UUID operationId,
            UUID queueId,
            UUID eventId,
            UUID playerId,
            int quantity,
            String payloadFingerprint,
            RewardDeliveryState state,
            Instant preparedAt,
            Optional<Instant> appliedAt) {
    }

    private enum RewardDeliveryState {
        PREPARED,
        APPLIED
    }
}
