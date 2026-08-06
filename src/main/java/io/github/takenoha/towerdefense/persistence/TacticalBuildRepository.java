package io.github.takenoha.towerdefense.persistence;

import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalBuildLifecycle;
import io.github.takenoha.towerdefense.tactical.TacticalBuildSelectionView;
import io.github.takenoha.towerdefense.tactical.TacticalBuildStateProvider;
import io.github.takenoha.towerdefense.tactical.TacticalCandidate;
import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet;
import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalTerminalResult;
import io.github.takenoha.towerdefense.tactical.TacticalUnlockResult;
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
 * Transactional persistence boundary for candidate generation, owner selection, and runtime
 * unlock progress. Runtime reads use one selected definition snapshot and never consult the
 * candidate list or configuration on the hot path.
 */
public final class TacticalBuildRepository
        implements TacticalBuildStateProvider, TacticalBuildLifecycle {
    private static final String GENERATE = "GENERATE";
    private static final String SELECT = "SELECT";
    private static final String BIND = "BIND";
    private static final String UNLOCK = "UNLOCK";
    private static final String TERMINAL = "TERMINAL";
    private static final String CANCEL = "CANCEL";

    private final Database database;

    public TacticalBuildRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Persists the deterministic candidate result; retries return the original candidate set. */
    public TacticalCandidateSet createCandidates(TacticalCandidateSet requested) {
        Objects.requireNonNull(requested, "requested");
        String fingerprint = generationFingerprint(requested);
        try {
            return database.inImmediateTransaction(connection -> {
                Optional<TacticalBuildSession> existing = loadSessionByStartOperation(
                        connection, requested.startOperationId());
                if (existing.isPresent()) {
                    TacticalBuildSession session = existing.orElseThrow();
                    if (!session.tacticalSessionId().equals(requested.tacticalSessionId())
                            || !session.teamId().equals(requested.teamId())
                            || session.stage() != requested.stage()
                            || session.seed() != requested.seed()
                            || session.generatorVersion() != requested.generatorVersion()) {
                        throw new PersistenceConflictException(
                                "The tactical generation operation UUID is already assigned to another payload");
                    }
                    TacticalCandidateSet persisted = loadCandidates(connection, session.tacticalSessionId());
                    if (!generationFingerprint(persisted).equals(fingerprint)) {
                        throw new PersistenceConflictException(
                                "The tactical generation operation UUID has a different candidate payload");
                    }
                    return persisted;
                }
                requireTeam(connection, requested.teamId());
                if (loadSession(connection, requested.tacticalSessionId()).isPresent()) {
                    throw new PersistenceConflictException(
                            "The tactical session UUID is already assigned to another generation");
                }
                insertSession(connection, requested, requested.generatedAt());
                insertOperation(
                        connection,
                        requested.startOperationId(),
                        requested.tacticalSessionId(),
                        GENERATE,
                        fingerprint,
                        requested.generatedAt());
                for (TacticalCandidate candidate : requested.candidates()) {
                    TacticalBuildDefinition definition = candidate.definition();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO tactical_build_candidates(
                                tactical_session_id, candidate_slot, build_id, build_version, snapshot)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                        statement.setString(1, requested.tacticalSessionId().toString());
                        statement.setInt(2, candidate.slot());
                        statement.setString(3, definition.id());
                        statement.setInt(4, definition.version());
                        statement.setString(5, TacticalDefinitionCodec.encode(definition));
                        statement.executeUpdate();
                    }
                }
                return requested;
            });
        } catch (SQLException exception) {
            throw failure("create tactical candidates", exception);
        }
    }

    public Optional<TacticalCandidateSet> findCandidates(UUID tacticalSessionId) {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        return read(
                "load tactical candidates",
                connection -> {
                    if (loadSession(connection, tacticalSessionId).isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(loadCandidates(connection, tacticalSessionId));
                });
    }

    /** Finds the newest unselected candidate set so a restart can reopen the same choices. */
    public Optional<TacticalCandidateSet> findGeneratedByTeamAndStage(UUID teamId, int stage) {
        Objects.requireNonNull(teamId, "teamId");
        if (stage <= 0) {
            throw new IllegalArgumentException("stage must be positive");
        }
        return read("load generated tactical candidates", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT tactical_session_id
                    FROM tactical_build_sessions
                    WHERE team_id = ? AND stage = ? AND state = 'GENERATED'
                    ORDER BY created_at DESC, tactical_session_id DESC
                    LIMIT 1
                    """)) {
                statement.setString(1, teamId.toString());
                statement.setInt(2, stage);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(loadCandidates(
                                    connection, uuid(resultSet.getString(1))))
                            : Optional.empty();
                }
            }
        });
    }

    /** Selects one candidate with owner authorization and an idempotent operation UUID. */
    public TacticalSelectionResult selectBuild(
            UUID tacticalSessionId,
            UUID actorId,
            String buildId,
            UUID operationId,
            Instant selectedAt) {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(selectedAt, "selectedAt");
        String fingerprint = actorId + "|" + buildId;
        try {
            return database.inImmediateTransaction(connection -> {
                TacticalBuildSession session = requireSession(connection, tacticalSessionId);
                Optional<OperationRow> prior = loadOperation(connection, operationId);
                if (prior.isPresent()) {
                    requireMatchingOperation(prior.orElseThrow(), tacticalSessionId, SELECT, fingerprint);
                    return new TacticalSelectionResult(
                            OperationOutcome.ALREADY_APPLIED,
                            selectionView(session));
                }
                if (session.state() != TacticalBuildSessionState.GENERATED) {
                    throw new PersistenceConflictException(
                            "A tactical build can only be selected before the defense starts");
                }
                requireTeamOwner(connection, session.teamId(), actorId);
                TacticalBuildDefinition selected = loadCandidateDefinition(
                        connection, tacticalSessionId, buildId).orElseThrow(
                                () -> new PersistenceConflictException(
                                        "The selected tactical build is not a candidate"));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tactical_build_sessions
                        SET state = 'SELECTED', selected_build_id = ?, selected_build_version = ?,
                            selected_snapshot = ?, updated_at = ?
                        WHERE tactical_session_id = ? AND state = 'GENERATED'
                        """)) {
                    statement.setString(1, selected.id());
                    statement.setInt(2, selected.version());
                    statement.setString(3, TacticalDefinitionCodec.encode(selected));
                    statement.setString(4, selectedAt.toString());
                    statement.setString(5, tacticalSessionId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The tactical session changed before selection was applied");
                    }
                }
                insertOperation(
                        connection,
                        operationId,
                        tacticalSessionId,
                        SELECT,
                        fingerprint,
                        selectedAt);
                TacticalBuildSession updated = requireSession(connection, tacticalSessionId);
                return new TacticalSelectionResult(OperationOutcome.APPLIED, selectionView(updated));
            });
        } catch (SQLException exception) {
            throw failure("select a tactical build", exception);
        }
    }

    /** Binds the selected session to the durable defense event after the existing start succeeds. */
    public OperationOutcome bindToDefense(
            UUID tacticalSessionId,
            UUID defenseId,
            UUID operationId,
            Instant boundAt) {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        Objects.requireNonNull(defenseId, "defenseId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(boundAt, "boundAt");
        String fingerprint = defenseId.toString();
        try {
            return database.inImmediateTransaction(connection -> {
                TacticalBuildSession session = requireSession(connection, tacticalSessionId);
                Optional<OperationRow> prior = loadOperation(connection, operationId);
                if (prior.isPresent()) {
                    requireMatchingOperation(prior.orElseThrow(), tacticalSessionId, BIND, fingerprint);
                    return OperationOutcome.ALREADY_APPLIED;
                }
                if (session.state() != TacticalBuildSessionState.SELECTED) {
                    throw new PersistenceConflictException(
                            "Only a selected tactical build can be bound to a defense");
                }
                requireDefenseMatches(connection, defenseId, session.teamId(), session.stage());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tactical_build_sessions
                        SET defense_id = ?, state = 'ACTIVE', updated_at = ?
                        WHERE tactical_session_id = ? AND state = 'SELECTED' AND defense_id IS NULL
                        """)) {
                    statement.setString(1, defenseId.toString());
                    statement.setString(2, boundAt.toString());
                    statement.setString(3, tacticalSessionId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The tactical session is already bound to a different defense");
                    }
                }
                insertOperation(connection, operationId, tacticalSessionId, BIND, fingerprint, boundAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("bind tactical build to defense", exception);
        }
    }

    /** Cancels a candidate session before it is bound to a defense without consuming a start item. */
    public OperationOutcome cancelBeforeSelection(
            UUID tacticalSessionId,
            UUID actorId,
            UUID operationId,
            Instant cancelledAt) {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(cancelledAt, "cancelledAt");
        String fingerprint = actorId.toString();
        try {
            return database.inImmediateTransaction(connection -> {
                TacticalBuildSession session = requireSession(connection, tacticalSessionId);
                Optional<OperationRow> prior = loadOperation(connection, operationId);
                if (prior.isPresent()) {
                    requireMatchingOperation(prior.orElseThrow(), tacticalSessionId, CANCEL, fingerprint);
                    return OperationOutcome.ALREADY_APPLIED;
                }
                requireTeamOwner(connection, session.teamId(), actorId);
                if (session.state() != TacticalBuildSessionState.GENERATED
                        && session.state() != TacticalBuildSessionState.SELECTED) {
                        throw new PersistenceConflictException(
                            "Only an unbound tactical session can be cancelled");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tactical_build_sessions
                        SET state = 'CANCELLED', updated_at = ?
                        WHERE tactical_session_id = ? AND state IN ('GENERATED', 'SELECTED')
                        """)) {
                    statement.setString(1, cancelledAt.toString());
                    statement.setString(2, tacticalSessionId.toString());
                    statement.executeUpdate();
                }
                insertOperation(connection, operationId, tacticalSessionId, CANCEL, fingerprint, cancelledAt);
                return OperationOutcome.APPLIED;
            });
        } catch (SQLException exception) {
            throw failure("cancel tactical selection", exception);
        }
    }

    @Override
    public Optional<TacticalBuildSelectionView> findActiveByDefense(UUID defenseId) {
        Objects.requireNonNull(defenseId, "defenseId");
        return read("load active tactical build", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT tactical_session_id FROM tactical_build_sessions
                    WHERE defense_id = ? AND state = 'ACTIVE'
                    """)) {
                statement.setString(1, defenseId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(selectionView(requireSession(
                                    connection, uuid(resultSet.getString(1)))))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public TacticalUnlockResult activateAtPreparation(UUID defenseId, UUID operationId) {
        return unlockForDefense(defenseId, 1, operationId, "PREPARATION");
    }

    @Override
    public TacticalUnlockResult advanceAfterWave(
            UUID defenseId,
            int completedWaveCount,
            int totalWaveCount,
            UUID operationId) {
        Objects.requireNonNull(defenseId, "defenseId");
        Objects.requireNonNull(operationId, "operationId");
        if (completedWaveCount < 0 || totalWaveCount <= 0
                || completedWaveCount > totalWaveCount) {
            throw new IllegalArgumentException("wave progress is outside its valid range");
        }
        int targetTier = 0;
        int[] thresholds = {20, 40, 60, 80};
        for (int index = 0; index < thresholds.length; index++) {
            if ((long) completedWaveCount * 100L >= (long) totalWaveCount * thresholds[index]) {
                targetTier = index + 2;
            }
        }
        return unlockForDefense(defenseId, targetTier, operationId, "WAVE_" + completedWaveCount);
    }

    @Override
    public TacticalUnlockResult activateFinalTier(UUID defenseId, UUID operationId) {
        return unlockForDefense(defenseId, 6, operationId, "FINAL");
    }

    @Override
    public void markTerminal(UUID defenseId, TacticalTerminalResult result, UUID operationId) {
        Objects.requireNonNull(defenseId, "defenseId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(operationId, "operationId");
        String fingerprint = result.name();
        try {
            database.inImmediateTransaction(connection -> {
                TacticalBuildSession session = requireSessionByDefense(connection, defenseId);
                Optional<OperationRow> prior = loadOperation(connection, operationId);
                if (prior.isPresent()) {
                    requireMatchingOperation(prior.orElseThrow(), session.tacticalSessionId(), TERMINAL,
                            fingerprint);
                    return null;
                }
                if (session.state() == TacticalBuildSessionState.TERMINAL) {
                    if (session.terminalResult().orElseThrow() != result) {
                        throw new PersistenceConflictException(
                                "The tactical session already has a different terminal result");
                    }
                    insertOperation(
                            connection,
                            operationId,
                            session.tacticalSessionId(),
                            TERMINAL,
                            fingerprint,
                            Instant.now());
                    return null;
                }
                if (session.state() != TacticalBuildSessionState.ACTIVE
                        && session.state() != TacticalBuildSessionState.RECOVERY_HOLD) {
                    throw new PersistenceConflictException(
                            "The tactical session is not terminalizable from its current state");
                }
                Instant now = Instant.now();
                insertOperation(
                        connection,
                        operationId,
                        session.tacticalSessionId(),
                        TERMINAL,
                        fingerprint,
                        now);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE tactical_build_sessions
                        SET state = 'TERMINAL', terminal_result = ?, terminal_at = ?, updated_at = ?
                        WHERE tactical_session_id = ? AND terminal_result IS NULL
                        """)) {
                    statement.setString(1, result.name());
                    statement.setString(2, now.toString());
                    statement.setString(3, now.toString());
                    statement.setString(4, session.tacticalSessionId().toString());
                    if (statement.executeUpdate() != 1) {
                        throw new PersistenceConflictException(
                                "The tactical terminal state changed before it was recorded");
                    }
                }
                return null;
            });
        } catch (SQLException exception) {
            throw failure("mark tactical session terminal", exception);
        }
    }

    private TacticalUnlockResult unlockForDefense(
            UUID defenseId,
            int targetTier,
            UUID operationId,
            String progressKey) {
        Objects.requireNonNull(defenseId, "defenseId");
        Objects.requireNonNull(operationId, "operationId");
        if (targetTier < 0 || targetTier > 6) {
            throw new IllegalArgumentException("targetTier must be between 0 and 6");
        }
        String fingerprint = progressKey + "|" + targetTier;
        try {
            return database.inImmediateTransaction(connection -> {
                TacticalBuildSession session = requireSessionByDefense(connection, defenseId);
                Optional<OperationRow> prior = loadOperation(connection, operationId);
                if (prior.isPresent()) {
                    requireMatchingOperation(prior.orElseThrow(), session.tacticalSessionId(), UNLOCK,
                            fingerprint);
                    return TacticalUnlockResult.unchanged(session.highestUnlockedTier());
                }
                if (session.state() == TacticalBuildSessionState.TERMINAL
                        || session.state() == TacticalBuildSessionState.CANCELLED) {
                    insertOperation(
                            connection,
                            operationId,
                            session.tacticalSessionId(),
                            UNLOCK,
                            fingerprint,
                            Instant.now());
                    return TacticalUnlockResult.unchanged(session.highestUnlockedTier());
                }
                if (session.state() != TacticalBuildSessionState.ACTIVE) {
                    throw new PersistenceConflictException(
                            "Tactical unlock progress requires an active tactical session");
                }
                int currentTier = session.highestUnlockedTier();
                int nextTier = Math.max(currentTier, targetTier);
                TacticalBuildDefinition definition = session.selectedDefinition().orElseThrow(
                        () -> new PersistenceConflictException(
                                "Active tactical session has no selected definition"));
                Instant now = Instant.now();
                insertOperation(
                        connection,
                        operationId,
                        session.tacticalSessionId(),
                        UNLOCK,
                        fingerprint,
                        now);
                List<String> newlyUnlocked = new ArrayList<>();
                for (TacticalSkillNodeDefinition node : definition.nodes()) {
                    if (node.tier() > currentTier && node.tier() <= nextTier) {
                        try (PreparedStatement statement = connection.prepareStatement("""
                                INSERT INTO tactical_build_unlocked_nodes(
                                    tactical_session_id, tier, node_id, operation_id, unlocked_at)
                                VALUES (?, ?, ?, ?, ?)
                                """)) {
                            statement.setString(1, session.tacticalSessionId().toString());
                            statement.setInt(2, node.tier());
                            statement.setString(3, node.id());
                            statement.setString(4, operationId.toString());
                            statement.setString(5, now.toString());
                            statement.executeUpdate();
                        }
                        newlyUnlocked.add(node.id());
                    }
                }
                if (nextTier != currentTier) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE tactical_build_sessions
                            SET highest_unlocked_tier = ?, updated_at = ?
                            WHERE tactical_session_id = ? AND highest_unlocked_tier = ?
                            """)) {
                        statement.setInt(1, nextTier);
                        statement.setString(2, now.toString());
                        statement.setString(3, session.tacticalSessionId().toString());
                        statement.setInt(4, currentTier);
                        if (statement.executeUpdate() != 1) {
                            throw new PersistenceConflictException(
                                    "The tactical unlock tier changed before it was persisted");
                        }
                    }
                }
                return new TacticalUnlockResult(
                        nextTier == currentTier
                                ? OperationOutcome.ALREADY_APPLIED
                                : OperationOutcome.APPLIED,
                        nextTier,
                        newlyUnlocked);
            });
        } catch (SQLException exception) {
            throw failure("advance tactical unlock progress", exception);
        }
    }

    private static void insertSession(
            Connection connection,
            TacticalCandidateSet requested,
            Instant createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tactical_build_sessions(
                    tactical_session_id, start_operation_id, team_id, stage, seed,
                    generator_version, state, highest_unlocked_tier, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'GENERATED', 0, ?, ?)
                """)) {
            statement.setString(1, requested.tacticalSessionId().toString());
            statement.setString(2, requested.startOperationId().toString());
            statement.setString(3, requested.teamId().toString());
            statement.setInt(4, requested.stage());
            statement.setLong(5, requested.seed());
            statement.setInt(6, requested.generatorVersion());
            statement.setString(7, requested.generatedAt().toString());
            statement.setString(8, createdAt.toString());
            statement.executeUpdate();
        }
    }

    private static void insertOperation(
            Connection connection,
            UUID operationId,
            UUID tacticalSessionId,
            String operationKind,
            String fingerprint,
            Instant appliedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tactical_build_operations(
                    operation_id, tactical_session_id, operation_kind,
                    payload_fingerprint, applied_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, tacticalSessionId.toString());
            statement.setString(3, operationKind);
            statement.setString(4, fingerprint);
            statement.setString(5, appliedAt.toString());
            statement.executeUpdate();
        }
    }

    private static Optional<TacticalBuildSession> loadSessionByStartOperation(
            Connection connection,
            UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tactical_session_id FROM tactical_build_sessions WHERE start_operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? loadSession(connection, uuid(resultSet.getString(1)))
                        : Optional.empty();
            }
        }
    }

    private static Optional<TacticalBuildSession> loadSession(
            Connection connection,
            UUID tacticalSessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tactical_session_id, start_operation_id, defense_id, team_id, stage, seed,
                       generator_version, state, selected_snapshot, highest_unlocked_tier,
                       terminal_result, created_at, updated_at, terminal_at
                FROM tactical_build_sessions WHERE tactical_session_id = ?
                """)) {
            statement.setString(1, tacticalSessionId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                String selectedSnapshot = resultSet.getString("selected_snapshot");
                String terminalValue = resultSet.getString("terminal_result");
                String terminalAt = resultSet.getString("terminal_at");
                return Optional.of(new TacticalBuildSession(
                        uuid(resultSet.getString("tactical_session_id")),
                        uuid(resultSet.getString("start_operation_id")),
                        Optional.ofNullable(resultSet.getString("defense_id")).map(TacticalBuildRepository::uuid),
                        uuid(resultSet.getString("team_id")),
                        resultSet.getInt("stage"),
                        resultSet.getLong("seed"),
                        resultSet.getInt("generator_version"),
                        TacticalBuildSessionState.valueOf(resultSet.getString("state")),
                        Optional.ofNullable(selectedSnapshot).map(TacticalDefinitionCodec::decode),
                        resultSet.getInt("highest_unlocked_tier"),
                        Optional.ofNullable(terminalValue).map(TacticalTerminalResult::valueOf),
                        instant(resultSet.getString("created_at")),
                        instant(resultSet.getString("updated_at")),
                        Optional.ofNullable(terminalAt).map(TacticalBuildRepository::instant)));
            }
        }
    }

    private static TacticalCandidateSet loadCandidates(
            Connection connection,
            UUID tacticalSessionId) throws SQLException {
        TacticalBuildSession session = requireSession(connection, tacticalSessionId);
        List<TacticalCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT candidate_slot, snapshot
                FROM tactical_build_candidates
                WHERE tactical_session_id = ? ORDER BY candidate_slot
                """)) {
            statement.setString(1, tacticalSessionId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(new TacticalCandidate(
                            resultSet.getInt("candidate_slot"),
                            TacticalDefinitionCodec.decode(resultSet.getString("snapshot"))));
                }
            }
        }
        return new TacticalCandidateSet(
                session.tacticalSessionId(),
                session.startOperationId(),
                session.teamId(),
                session.stage(),
                session.seed(),
                session.generatorVersion(),
                candidates,
                session.createdAt());
    }

    private static Optional<TacticalBuildDefinition> loadCandidateDefinition(
            Connection connection,
            UUID tacticalSessionId,
            String buildId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot FROM tactical_build_candidates
                WHERE tactical_session_id = ? AND build_id = ?
                """)) {
            statement.setString(1, tacticalSessionId.toString());
            statement.setString(2, buildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(TacticalDefinitionCodec.decode(resultSet.getString(1)))
                        : Optional.empty();
            }
        }
    }

    private static Optional<OperationRow> loadOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, tactical_session_id, operation_kind, payload_fingerprint
                FROM tactical_build_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new OperationRow(
                                uuid(resultSet.getString("operation_id")),
                                uuid(resultSet.getString("tactical_session_id")),
                                resultSet.getString("operation_kind"),
                                resultSet.getString("payload_fingerprint")))
                        : Optional.empty();
            }
        }
    }

    private static TacticalBuildSession requireSession(Connection connection, UUID sessionId)
            throws SQLException {
        return loadSession(connection, sessionId).orElseThrow(
                () -> new PersistenceConflictException("Tactical session does not exist: " + sessionId));
    }

    private static TacticalBuildSession requireSessionByDefense(Connection connection, UUID defenseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tactical_session_id FROM tactical_build_sessions WHERE defense_id = ?
                """)) {
            statement.setString(1, defenseId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException(
                            "No tactical build is bound to defense " + defenseId);
                }
                return requireSession(connection, uuid(resultSet.getString(1)));
            }
        }
    }

    private static void requireTeam(Connection connection, UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM teams WHERE team_id = ?")) {
            statement.setString(1, teamId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new PersistenceConflictException("Team does not exist: " + teamId);
                }
            }
        }
    }

    private static void requireTeamOwner(Connection connection, UUID teamId, UUID actorId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role FROM team_members WHERE team_id = ? AND player_id = ?
                """)) {
            statement.setString(1, teamId.toString());
            statement.setString(2, actorId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !"OWNER".equals(resultSet.getString(1))) {
                    throw new PersistenceConflictException(
                            "Only the tactical team owner may select a build");
                }
            }
        }
    }

    private static void requireDefenseMatches(
            Connection connection,
            UUID defenseId,
            UUID teamId,
            int stage) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT team_id, stage_level FROM defense_events WHERE event_id = ?
                """)) {
            statement.setString(1, defenseId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !teamId.toString().equals(resultSet.getString("team_id"))
                        || resultSet.getLong("stage_level") != stage) {
                    throw new PersistenceConflictException(
                            "The defense does not match the tactical session");
                }
            }
        }
    }

    private static void requireMatchingOperation(
            OperationRow operation,
            UUID tacticalSessionId,
            String kind,
            String fingerprint) {
        if (!operation.tacticalSessionId().equals(tacticalSessionId)
                || !operation.kind().equals(kind)
                || !operation.fingerprint().equals(fingerprint)) {
            throw new PersistenceConflictException(
                    "The tactical operation UUID is already assigned to another payload");
        }
    }

    private static TacticalBuildSelectionView selectionView(TacticalBuildSession session) {
        TacticalBuildDefinition definition = session.selectedDefinition().orElseThrow(
                () -> new PersistenceConflictException(
                        "Tactical session does not have a selected definition"));
        return definition.selectionView(
                session.tacticalSessionId(),
                session.teamId(),
                session.stage(),
                session.highestUnlockedTier());
    }

    private static String generationFingerprint(TacticalCandidateSet candidates) {
        return candidates.candidates().stream()
                .map(candidate -> candidate.slot() + ":"
                        + TacticalDefinitionCodec.encode(candidate.definition()))
                .reduce(
                        candidates.startOperationId() + "|" + candidates.teamId() + "|"
                                + candidates.stage() + "|" + candidates.seed() + "|"
                                + candidates.generatorVersion(),
                        (left, right) -> left + ";" + right);
    }

    private <T> T read(String operation, Database.SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw failure(operation, exception);
        }
    }

    private static PersistenceException failure(String operation, SQLException exception) {
        return new PersistenceException("Could not " + operation, exception);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalidUuid) {
            throw new PersistenceException("Invalid UUID in tactical persistence", invalidUuid);
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalidInstant) {
            throw new PersistenceException("Invalid timestamp in tactical persistence", invalidInstant);
        }
    }

    private record OperationRow(
            UUID operationId,
            UUID tacticalSessionId,
            String kind,
            String fingerprint) {
    }
}
