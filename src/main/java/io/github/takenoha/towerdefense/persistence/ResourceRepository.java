package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Team point-wallet boundary for event materials.
 *
 * <p>All writes are operation-UUID protected. Terminal settlement is also exposed as a
 * package-private connection method so the escrow rows, event terminal state, and wallet credit
 * commit in the same SQLite transaction.</p>
 */
public final class ResourceRepository {
    private static final String ACTIVE_EVENT_STATES =
            "('COUNTDOWN', 'PREPARATION', 'WAVE_ACTIVE', 'INTERMISSION')";

    private final Database database;

    public ResourceRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        ensureWalletRows();
        migrateLegacyResourceQueues();
    }

    /** Repairs wallet rows for teams created before the wallet migration or partial restores. */
    private void ensureWalletRows() {
        try {
            database.inImmediateTransaction(connection -> {
                Instant repairedAt = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
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
                        """)) {
                    statement.setString(1, repairedAt.toString());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw resourceFailure("ensure team resource wallet rows", exception);
        }
    }

    public TeamResourceSnapshot load(UUID teamId, UUID viewerId) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(viewerId, "viewerId");
        try (Connection connection = database.openConnection()) {
            return loadSnapshot(connection, teamId, viewerId);
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load team resource balances", exception);
        }
    }

    /** Loads the durable amount that the terminal transaction assigned to each wallet. */
    public TeamResourceSettlement loadTerminalSettlement(
            UUID eventId,
            DefensePhase phase) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(phase, "phase");
        if (phase == DefensePhase.RECOVERY) {
            try (Connection connection = database.openConnection()) {
                return new TeamResourceSettlement(
                        eventId,
                        loadEventTeam(connection, eventId),
                        phase,
                        0L,
                        0L);
            } catch (SQLException exception) {
                throw new PersistenceException(
                        "Could not load the recovered resource settlement", exception);
            }
        }
        try (Connection connection = database.openConnection()) {
            UUID teamId = loadEventTeam(connection, eventId);
            return new TeamResourceSettlement(
                    eventId,
                    teamId,
                    phase,
                    terminalAmount(connection, eventId, ResourceType.DEFENSE_POINTS, phase),
                    terminalAmount(connection, eventId, ResourceType.ENHANCEMENT_POINTS, phase));
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load the resource settlement", exception);
        }
    }

    public ResourcePickupFeedback loadPickupFeedback(
            UUID eventId,
            UUID playerId,
            ResourceType resourceType,
            int claimedQuantity) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(playerId, "playerId");
        ResourceType.require(resourceType);
        if (claimedQuantity <= 0) {
            throw new IllegalArgumentException("claimedQuantity must be positive");
        }
        try (Connection connection = database.openConnection()) {
            return loadPickupFeedback(
                    connection, eventId, playerId, resourceType, claimedQuantity);
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load resource pickup feedback", exception);
        }
    }

    /** Reads post-claim feedback on the same SQLite connection as the claim transaction. */
    static ResourcePickupFeedback loadPickupFeedback(
            Connection connection,
            UUID eventId,
            UUID playerId,
            ResourceType resourceType,
            int claimedQuantity) throws SQLException {
        UUID teamId = loadEventTeam(connection, eventId);
        long eventPlayerTotal;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(c.quantity), 0)
                FROM event_drop_claims c
                JOIN event_drop_escrow d ON d.drop_id = c.drop_id
                WHERE c.event_id = ? AND c.recipient_id = ? AND d.item_id = ?
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, resourceType.itemId());
            try (ResultSet resultSet = statement.executeQuery()) {
                eventPlayerTotal = resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
        return new ResourcePickupFeedback(
                eventId,
                playerId,
                resourceType,
                claimedQuantity,
                eventPlayerTotal,
                balance(connection, teamId, resourceType));
    }

    /** Credits a wallet through a standalone idempotent operation. */
    public ResourceMutationResult credit(
            UUID teamId,
            ResourceType resourceType,
            long amount,
            UUID operationId,
            String sourceId,
            Instant appliedAt) {
        Objects.requireNonNull(teamId, "teamId");
        ResourceType.require(resourceType);
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (amount <= 0L) {
            throw new IllegalArgumentException("credit amount must be positive");
        }
        try {
            return database.inImmediateTransaction(connection -> {
                OperationOutcome outcome = applyDelta(
                        connection,
                        teamId,
                        null,
                        resourceType,
                        amount,
                        operationId,
                        "CREDIT",
                        sourceId,
                        sourceId + "|" + resourceType,
                        appliedAt);
                return new ResourceMutationResult(
                        outcome,
                        loadSnapshot(connection, teamId, null));
            });
        } catch (SQLException exception) {
            throw resourceFailure("credit team resources", exception);
        }
    }

    /** Debits a wallet after checking team membership and the persisted balance. */
    public ResourceMutationResult debit(
            UUID teamId,
            UUID actorId,
            ResourceType resourceType,
            long amount,
            UUID operationId,
            String sourceId,
            Instant appliedAt) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(actorId, "actorId");
        ResourceType.require(resourceType);
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (amount <= 0L) {
            throw new IllegalArgumentException("debit amount must be positive");
        }
        try {
            return database.inImmediateTransaction(connection -> {
                requireTeamMember(connection, teamId, actorId);
                OperationOutcome outcome = applyDelta(
                        connection,
                        teamId,
                        actorId,
                        resourceType,
                        -amount,
                        operationId,
                        "DEBIT",
                        sourceId,
                        teamId + "|" + actorId + "|" + resourceType + "|" + amount,
                        appliedAt);
                return new ResourceMutationResult(
                        outcome,
                        loadSnapshot(connection, teamId, actorId));
            });
        } catch (SQLException exception) {
            throw resourceFailure("debit team resources", exception);
        }
    }

    /** Applies all resource settlement rows inside the event terminal transaction. */
    static void settleForTerminal(
            Connection connection,
            UUID eventId,
            UUID terminalOperationId,
            DefensePhase terminalPhase,
            Instant settledAt) throws SQLException {
        UUID teamId = loadEventTeam(connection, eventId);
        for (ResourceType resourceType : ResourceType.values()) {
            String sourceId = eventId + "|" + resourceType.name();
            UUID operationId = deterministic(
                    terminalOperationId, "RESOURCE_SETTLE", resourceType.name());
            long amount = terminalAmount(connection, eventId, resourceType, terminalPhase);
            applyDelta(
                    connection,
                    teamId,
                    null,
                    resourceType,
                    amount,
                    operationId,
                    "EVENT_SETTLEMENT",
                    sourceId,
                    eventId + "|" + terminalPhase + "|" + resourceType.name() + "|" + amount,
                    settledAt);
        }
    }

    private static long terminalAmount(
            Connection connection,
            UUID eventId,
            ResourceType resourceType,
            DefensePhase terminalPhase) throws SQLException {
        if (terminalPhase == DefensePhase.RECOVERY) {
            return 0L;
        }
        String expression = terminalPhase == DefensePhase.VICTORY
                ? "d.quantity"
                : "COALESCE(c.claimed_quantity, 0)";
        String sql = """
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
                """.formatted(expression);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, resourceType.itemId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    /** Marks a technical recovery as a zero-credit resource terminal. */
    static void settleForRecovery(
            Connection connection,
            UUID eventId,
            UUID recoveryOperationId,
            Instant recoveredAt) throws SQLException {
        UUID teamId = loadEventTeam(connection, eventId);
        for (ResourceType resourceType : ResourceType.values()) {
            UUID operationId = deterministic(
                    recoveryOperationId, "RESOURCE_RECOVERY", resourceType.name());
            applyDelta(
                    connection,
                    teamId,
                    null,
                    resourceType,
                    0L,
                    operationId,
                    "EVENT_SETTLEMENT",
                    eventId + "|" + resourceType.name(),
                    eventId + "|RECOVERY|" + resourceType.name(),
                    recoveredAt);
        }
    }

    /** Shared connection hook for core repair and tower upgrade wallet payments. */
    static OperationOutcome debitInTransaction(
            Connection connection,
            UUID teamId,
            UUID actorId,
            ResourceType resourceType,
            long amount,
            UUID operationId,
            String sourceId,
            String payloadFingerprint,
            Instant appliedAt) throws SQLException {
        requireTeamMember(connection, teamId, actorId);
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
                appliedAt);
    }

    static boolean isWalletResource(String itemId) {
        return ResourceType.fromItemId(itemId).isPresent();
    }

    /** Converts old undelivered physical-material queues exactly once at startup. */
    private void migrateLegacyResourceQueues() {
        try {
            database.inImmediateTransaction(connection -> {
                Instant migratedAt = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
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
                        """);
                        ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        UUID queueId = UUID.fromString(resultSet.getString("queue_id"));
                        UUID eventId = UUID.fromString(resultSet.getString("event_id"));
                        UUID teamId = UUID.fromString(resultSet.getString("team_id"));
                        ResourceType resourceType = ResourceType.fromItemId(
                                resultSet.getString("item_id")).orElseThrow();
                        long quantity = resultSet.getLong("quantity");
                        UUID operationId = deterministic(
                                queueId, "LEGACY_REWARD_QUEUE", resourceType.name());
                        applyDelta(
                                connection,
                                teamId,
                                null,
                                resourceType,
                                quantity,
                                operationId,
                                "EVENT_SETTLEMENT",
                                queueId.toString(),
                                eventId + "|LEGACY_QUEUE|" + queueId + "|" + resourceType,
                                migratedAt);
                        try (PreparedStatement update = connection.prepareStatement("""
                                UPDATE event_reward_queue
                                SET status = 'DELIVERED', updated_at = ?
                                WHERE queue_id = ? AND status = 'PENDING'
                                """)) {
                            update.setString(1, migratedAt.toString());
                            update.setString(2, queueId.toString());
                            update.executeUpdate();
                        }
                    }
                }
                return null;
            });
        } catch (SQLException exception) {
            throw resourceFailure("migrate legacy resource queues", exception);
        }
    }

    private static OperationOutcome applyDelta(
            Connection connection,
            UUID teamId,
            UUID actorId,
            ResourceType resourceType,
            long delta,
            UUID operationId,
            String operationKind,
            String sourceId,
            String payloadFingerprint,
            Instant appliedAt) throws SQLException {
        ResourceType.require(resourceType);
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationKind, "operationKind");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Optional<ResourceOperationRow> existing = loadOperation(connection, operationId);
        if (existing.isPresent()) {
            ResourceOperationRow row = existing.orElseThrow();
            if (!row.teamId().equals(teamId)
                    || row.resourceType() != resourceType
                    || !row.operationKind().equals(operationKind)
                    || !row.sourceId().equals(sourceId)
                    || row.delta() != delta
                    || !row.payloadFingerprint().equals(payloadFingerprint)) {
                throw new PersistenceConflictException(
                        "The resource operation UUID is already assigned to another payload");
            }
            return OperationOutcome.ALREADY_APPLIED;
        }
        if (delta < 0L && balance(connection, teamId, resourceType) < -delta) {
            throw new PersistenceConflictException(
                    "The team does not have enough " + resourceType.displayName());
        }
        long current = balance(connection, teamId, resourceType);
        long next;
        try {
            next = Math.addExact(current, delta);
        } catch (ArithmeticException overflow) {
            throw new PersistenceConflictException("The resource balance overflowed", overflow);
        }
        if (next < 0L) {
            throw new PersistenceConflictException("The resource balance cannot be negative");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO team_resource_operations(
                    operation_id, team_id, resource_type, operation_kind, source_id,
                    delta, payload_fingerprint, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, teamId.toString());
            statement.setString(3, resourceType.name());
            statement.setString(4, operationKind);
            statement.setString(5, sourceId);
            statement.setLong(6, delta);
            statement.setString(7, payloadFingerprint);
            statement.setString(8, appliedAt.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE team_resource_balances
                SET balance = ?, updated_at = ?
                WHERE team_id = ? AND resource_type = ?
                """)) {
            statement.setLong(1, next);
            statement.setString(2, appliedAt.toString());
            statement.setString(3, teamId.toString());
            statement.setString(4, resourceType.name());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The team resource balance row does not exist");
            }
        }
        return OperationOutcome.APPLIED;
    }

    private static TeamResourceSnapshot loadSnapshot(
            Connection connection, UUID teamId, UUID viewerId) throws SQLException {
        long defense = balance(connection, teamId, ResourceType.DEFENSE_POINTS);
        long enhancement = balance(connection, teamId, ResourceType.ENHANCEMENT_POINTS);
        long teamProvisionalDefense = provisional(
                connection, teamId, null, ResourceType.DEFENSE_POINTS);
        long teamProvisionalEnhancement = provisional(
                connection, teamId, null, ResourceType.ENHANCEMENT_POINTS);
        long viewerProvisionalDefense = provisional(
                connection, teamId, viewerId, ResourceType.DEFENSE_POINTS);
        long viewerProvisionalEnhancement = provisional(
                connection, teamId, viewerId, ResourceType.ENHANCEMENT_POINTS);
        return new TeamResourceSnapshot(
                teamId,
                defense,
                enhancement,
                teamProvisionalDefense,
                teamProvisionalEnhancement,
                viewerProvisionalDefense,
                viewerProvisionalEnhancement);
    }

    private static long provisional(
            Connection connection,
            UUID teamId,
            UUID viewerId,
            ResourceType resourceType) throws SQLException {
        boolean viewerOnly = viewerId != null;
        String sql = "SELECT COALESCE(SUM(c.quantity), 0) "
                + "FROM event_drop_claims c "
                + "JOIN event_drop_escrow d ON d.drop_id = c.drop_id "
                + "JOIN defense_events e ON e.event_id = d.event_id "
                + "WHERE e.team_id = ? "
                + (viewerOnly ? "AND c.recipient_id = ? " : "")
                + "AND d.item_id = ? "
                + "AND e.state IN " + ACTIVE_EVENT_STATES;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teamId.toString());
            int itemIndex = 2;
            if (viewerOnly) {
                statement.setString(itemIndex++, viewerId.toString());
            }
            statement.setString(itemIndex, resourceType.itemId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private static long balance(
            Connection connection, UUID teamId, ResourceType resourceType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT balance FROM team_resource_balances
                WHERE team_id = ? AND resource_type = ?
                """)) {
            statement.setString(1, teamId.toString());
            statement.setString(2, resourceType.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("The team resource balance row does not exist");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private static UUID loadEventTeam(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT team_id FROM defense_events WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("The defense event does not exist");
                }
                return UUID.fromString(resultSet.getString(1));
            }
        }
    }

    private static Optional<ResourceOperationRow> loadOperation(
            Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, resource_type, operation_kind, source_id, delta, payload_fingerprint
                FROM team_resource_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResourceOperationRow(
                        UUID.fromString(resultSet.getString("team_id")),
                        ResourceType.valueOf(resultSet.getString("resource_type")),
                        resultSet.getString("operation_kind"),
                        resultSet.getString("source_id"),
                        resultSet.getLong("delta"),
                        resultSet.getString("payload_fingerprint")));
            }
        }
    }

    private static void requireTeamMember(
            Connection connection, UUID teamId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM team_members WHERE team_id = ? AND player_id = ?
                """)) {
            statement.setString(1, teamId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException(
                            "The player is not a member of the team");
                }
            }
        }
    }

    private static UUID deterministic(UUID base, String namespace, String value) {
        return UUID.nameUUIDFromBytes((base + "|" + namespace + "|" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static PersistenceException resourceFailure(String action, SQLException exception) {
        return new PersistenceException("Could not " + action, exception);
    }

    private record ResourceOperationRow(
            UUID teamId,
            ResourceType resourceType,
            String operationKind,
            String sourceId,
            long delta,
            String payloadFingerprint) {
    }
}
