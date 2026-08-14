package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition
import io.github.takenoha.towerdefense.tactical.TacticalBuildLifecycle
import io.github.takenoha.towerdefense.tactical.TacticalBuildSelectionView
import io.github.takenoha.towerdefense.tactical.TacticalBuildStateProvider
import io.github.takenoha.towerdefense.tactical.TacticalCandidate
import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet
import io.github.takenoha.towerdefense.tactical.TacticalSkillNodeDefinition
import io.github.takenoha.towerdefense.tactical.TacticalTerminalResult
import io.github.takenoha.towerdefense.tactical.TacticalUnlockResult
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.Objects
import java.util.Optional
import java.util.UUID

private const val GENERATE = "GENERATE"
private const val SELECT = "SELECT"
private const val BIND = "BIND"
private const val UNLOCK = "UNLOCK"
private const val TERMINAL = "TERMINAL"
private const val CANCEL = "CANCEL"

/**
 * Transactional persistence boundary for candidate generation, owner selection, and runtime
 * unlock progress. Runtime reads use one selected definition snapshot and never consult the
 * candidate list or configuration on the hot path.
 */
class TacticalBuildRepository(database: Database) : TacticalBuildStateProvider, TacticalBuildLifecycle {
    private val database: Database = Objects.requireNonNull(database, "database")

    /** Persists the deterministic candidate result; retries return the original candidate set. */
    fun createCandidates(requested: TacticalCandidateSet): TacticalCandidateSet {
        Objects.requireNonNull(requested, "requested")
        val fingerprint = generationFingerprint(requested)
        return try {
            database.inImmediateTransaction { connection ->
                val existing = loadSessionByStartOperation(connection, requested.startOperationId())
                if (existing.isPresent) {
                    val session = existing.orElseThrow()
                    if (session.tacticalSessionId() != requested.tacticalSessionId()
                        || session.teamId() != requested.teamId()
                        || session.stage() != requested.stage()
                        || session.seed() != requested.seed()
                        || session.generatorVersion() != requested.generatorVersion()
                    ) {
                        throw PersistenceConflictException(
                            "The tactical generation operation UUID is already assigned to another payload",
                        )
                    }
                    val persisted = loadCandidates(connection, session.tacticalSessionId())
                    if (generationFingerprint(persisted) != fingerprint) {
                        throw PersistenceConflictException(
                            "The tactical generation operation UUID has a different candidate payload",
                        )
                    }
                    return@inImmediateTransaction persisted
                }
                requireTeam(connection, requested.teamId())
                if (loadSession(connection, requested.tacticalSessionId()).isPresent) {
                    throw PersistenceConflictException(
                        "The tactical session UUID is already assigned to another generation",
                    )
                }
                insertSession(connection, requested, requested.generatedAt())
                insertOperation(
                    connection,
                    requested.startOperationId(),
                    requested.tacticalSessionId(),
                    GENERATE,
                    fingerprint,
                    requested.generatedAt(),
                )
                for (candidate in requested.candidates()) {
                    val definition = candidate.definition
                    connection.prepareStatement(
                        """
                        INSERT INTO tactical_build_candidates(
                            tactical_session_id, candidate_slot, build_id, build_version, snapshot)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, requested.tacticalSessionId().toString())
                        statement.setInt(2, candidate.slot)
                        statement.setString(3, definition.id())
                        statement.setInt(4, definition.version())
                        statement.setString(5, TacticalDefinitionCodec.encode(definition))
                        statement.executeUpdate()
                    }
                }
                requested
            }
        } catch (exception: SQLException) {
            throw failure("create tactical candidates", exception)
        }
    }

    fun findCandidates(tacticalSessionId: UUID): Optional<TacticalCandidateSet> {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId")
        return read("load tactical candidates") { connection ->
            if (loadSession(connection, tacticalSessionId).isEmpty) {
                Optional.empty()
            } else {
                Optional.of(loadCandidates(connection, tacticalSessionId))
            }
        }
    }

    /** Finds the newest unselected candidate set so a restart can reopen the same choices. */
    fun findGeneratedByTeamAndStage(teamId: UUID, stage: Int): Optional<TacticalCandidateSet> {
        Objects.requireNonNull(teamId, "teamId")
        if (stage <= 0) {
            throw IllegalArgumentException("stage must be positive")
        }
        return read("load generated tactical candidates") { connection ->
            connection.prepareStatement(
                """
                SELECT tactical_session_id
                FROM tactical_build_sessions
                WHERE team_id = ? AND stage = ? AND state = 'GENERATED'
                ORDER BY created_at DESC, tactical_session_id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, teamId.toString())
                statement.setInt(2, stage)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        Optional.of(loadCandidates(connection, uuid(resultSet.getString(1))))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }
    }

    /** Selects one candidate with owner authorization and an idempotent operation UUID. */
    fun selectBuild(
        tacticalSessionId: UUID,
        actorId: UUID,
        buildId: String,
        operationId: UUID,
        selectedAt: Instant,
    ): TacticalSelectionResult = selectBuild(
        tacticalSessionId,
        actorId,
        buildId,
        null,
        operationId,
        selectedAt,
    )

    /** Selects one candidate and optionally pins the branch used by the active defense. */
    fun selectBuild(
        tacticalSessionId: UUID,
        actorId: UUID,
        buildId: String,
        selectedBranchId: String?,
        operationId: UUID,
        selectedAt: Instant,
    ): TacticalSelectionResult {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId")
        Objects.requireNonNull(actorId, "actorId")
        Objects.requireNonNull(buildId, "buildId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(selectedAt, "selectedAt")
        if (selectedBranchId != null && selectedBranchId.isBlank()) {
            throw IllegalArgumentException("selectedBranchId must not be blank")
        }
        val fingerprint = if (selectedBranchId == null) {
            "$actorId|$buildId"
        } else {
            "$actorId|$buildId|branch=$selectedBranchId"
        }
        return try {
            database.inImmediateTransaction { connection ->
                val session = requireSession(connection, tacticalSessionId)
                val prior = loadOperation(connection, operationId)
                if (prior.isPresent) {
                    requireMatchingOperation(prior.orElseThrow(), tacticalSessionId, SELECT, fingerprint)
                    return@inImmediateTransaction TacticalSelectionResult(
                        OperationOutcome.ALREADY_APPLIED,
                        selectionView(connection, session),
                    )
                }
                if (session.state() != TacticalBuildSessionState.GENERATED) {
                    throw PersistenceConflictException(
                        "A tactical build can only be selected before the defense starts",
                    )
                }
                requireTeamOwner(connection, session.teamId(), actorId)
                val selected = loadCandidateDefinition(connection, tacticalSessionId, buildId).orElseThrow {
                    PersistenceConflictException("The selected tactical build is not a candidate")
                }
                validateSelectedBranch(selected, selectedBranchId)
                connection.prepareStatement(
                    """
                    UPDATE tactical_build_sessions
                    SET state = 'SELECTED', selected_build_id = ?, selected_build_version = ?,
                        selected_snapshot = ?, selected_branch_id = ?, updated_at = ?
                    WHERE tactical_session_id = ? AND state = 'GENERATED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, selected.id())
                    statement.setInt(2, selected.version())
                    statement.setString(3, TacticalDefinitionCodec.encode(selected))
                    statement.setString(4, selectedBranchId)
                    statement.setString(5, selectedAt.toString())
                    statement.setString(6, tacticalSessionId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The tactical session changed before selection was applied",
                        )
                    }
                }
                insertOperation(
                    connection,
                    operationId,
                    tacticalSessionId,
                    SELECT,
                    fingerprint,
                    selectedAt,
                )
                val updated = requireSession(connection, tacticalSessionId)
                TacticalSelectionResult(
                    OperationOutcome.APPLIED,
                    selectionView(connection, updated),
                )
            }
        } catch (exception: SQLException) {
            throw failure("select a tactical build", exception)
        }
    }

    /** Binds the selected session to the durable defense event after the existing start succeeds. */
    fun bindToDefense(
        tacticalSessionId: UUID,
        defenseId: UUID,
        operationId: UUID,
        boundAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId")
        Objects.requireNonNull(defenseId, "defenseId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(boundAt, "boundAt")
        val fingerprint = defenseId.toString()
        return try {
            database.inImmediateTransaction { connection ->
                val session = requireSession(connection, tacticalSessionId)
                val prior = loadOperation(connection, operationId)
                if (prior.isPresent) {
                    requireMatchingOperation(prior.orElseThrow(), tacticalSessionId, BIND, fingerprint)
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (session.state() != TacticalBuildSessionState.SELECTED) {
                    throw PersistenceConflictException(
                        "Only a selected tactical build can be bound to a defense",
                    )
                }
                requireDefenseMatches(connection, defenseId, session.teamId(), session.stage())
                connection.prepareStatement(
                    """
                    UPDATE tactical_build_sessions
                    SET defense_id = ?, state = 'ACTIVE', updated_at = ?
                    WHERE tactical_session_id = ? AND state = 'SELECTED' AND defense_id IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, defenseId.toString())
                    statement.setString(2, boundAt.toString())
                    statement.setString(3, tacticalSessionId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The tactical session is already bound to a different defense",
                        )
                    }
                }
                insertOperation(connection, operationId, tacticalSessionId, BIND, fingerprint, boundAt)
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("bind tactical build to defense", exception)
        }
    }

    /** Cancels a candidate session before it is bound to a defense without consuming a start item. */
    fun cancelBeforeSelection(
        tacticalSessionId: UUID,
        actorId: UUID,
        operationId: UUID,
        cancelledAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(tacticalSessionId, "tacticalSessionId")
        Objects.requireNonNull(actorId, "actorId")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(cancelledAt, "cancelledAt")
        val fingerprint = actorId.toString()
        return try {
            database.inImmediateTransaction { connection ->
                val session = requireSession(connection, tacticalSessionId)
                val prior = loadOperation(connection, operationId)
                if (prior.isPresent) {
                    requireMatchingOperation(prior.orElseThrow(), tacticalSessionId, CANCEL, fingerprint)
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                requireTeamOwner(connection, session.teamId(), actorId)
                if (session.state() != TacticalBuildSessionState.GENERATED
                    && session.state() != TacticalBuildSessionState.SELECTED
                ) {
                    throw PersistenceConflictException(
                        "Only an unbound tactical session can be cancelled",
                    )
                }
                connection.prepareStatement(
                    """
                    UPDATE tactical_build_sessions
                    SET state = 'CANCELLED', updated_at = ?
                    WHERE tactical_session_id = ? AND state IN ('GENERATED', 'SELECTED')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, cancelledAt.toString())
                    statement.setString(2, tacticalSessionId.toString())
                    statement.executeUpdate()
                }
                insertOperation(connection, operationId, tacticalSessionId, CANCEL, fingerprint, cancelledAt)
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("cancel tactical selection", exception)
        }
    }

    override fun findActiveByDefense(defenseId: UUID): Optional<TacticalBuildSelectionView> {
        Objects.requireNonNull(defenseId, "defenseId")
        return read("load active tactical build") { connection ->
            connection.prepareStatement(
                """
                SELECT tactical_session_id FROM tactical_build_sessions
                WHERE defense_id = ? AND state = 'ACTIVE'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, defenseId.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        Optional.of(
                            selectionView(
                                connection,
                                requireSession(connection, uuid(resultSet.getString(1))),
                            ),
                        )
                    } else {
                        Optional.empty()
                    }
                }
            }
        }
    }

    override fun activateAtPreparation(defenseId: UUID, operationId: UUID): TacticalUnlockResult =
        unlockForDefense(defenseId, 1, operationId, "PREPARATION")

    override fun advanceAfterWave(
        defenseId: UUID,
        completedWaveCount: Int,
        totalWaveCount: Int,
        operationId: UUID,
    ): TacticalUnlockResult {
        Objects.requireNonNull(defenseId, "defenseId")
        Objects.requireNonNull(operationId, "operationId")
        if (completedWaveCount < 0 || totalWaveCount <= 0 || completedWaveCount > totalWaveCount) {
            throw IllegalArgumentException("wave progress is outside its valid range")
        }
        var targetTier = 0
        val thresholds = intArrayOf(20, 40, 60, 80)
        for (index in thresholds.indices) {
            if (completedWaveCount.toLong() * 100L >= totalWaveCount.toLong() * thresholds[index]) {
                targetTier = index + 2
            }
        }
        return unlockForDefense(defenseId, targetTier, operationId, "WAVE_$completedWaveCount")
    }

    override fun activateFinalTier(defenseId: UUID, operationId: UUID): TacticalUnlockResult =
        unlockForDefense(defenseId, 6, operationId, "FINAL")

    override fun markTerminal(defenseId: UUID, result: TacticalTerminalResult, operationId: UUID) {
        Objects.requireNonNull(defenseId, "defenseId")
        Objects.requireNonNull(result, "result")
        Objects.requireNonNull(operationId, "operationId")
        val fingerprint = result.name
        try {
            database.inImmediateTransaction<Unit> { connection ->
                val session = requireSessionByDefense(connection, defenseId)
                val prior = loadOperation(connection, operationId)
                if (prior.isPresent) {
                    requireMatchingOperation(prior.orElseThrow(), session.tacticalSessionId(), TERMINAL, fingerprint)
                    return@inImmediateTransaction Unit
                }
                if (session.state() == TacticalBuildSessionState.TERMINAL) {
                    if (session.terminalResult().orElseThrow() != result) {
                        throw PersistenceConflictException(
                            "The tactical session already has a different terminal result",
                        )
                    }
                    insertOperation(
                        connection,
                        operationId,
                        session.tacticalSessionId(),
                        TERMINAL,
                        fingerprint,
                        Instant.now(),
                    )
                    return@inImmediateTransaction Unit
                }
                if (session.state() != TacticalBuildSessionState.ACTIVE
                    && session.state() != TacticalBuildSessionState.RECOVERY_HOLD
                ) {
                    throw PersistenceConflictException(
                        "The tactical session is not terminalizable from its current state",
                    )
                }
                val now = Instant.now()
                insertOperation(
                    connection,
                    operationId,
                    session.tacticalSessionId(),
                    TERMINAL,
                    fingerprint,
                    now,
                )
                connection.prepareStatement(
                    """
                    UPDATE tactical_build_sessions
                    SET state = 'TERMINAL', terminal_result = ?, terminal_at = ?, updated_at = ?
                    WHERE tactical_session_id = ? AND terminal_result IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, result.name)
                    statement.setString(2, now.toString())
                    statement.setString(3, now.toString())
                    statement.setString(4, session.tacticalSessionId().toString())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The tactical terminal state changed before it was recorded",
                        )
                    }
                }
            }
        } catch (exception: SQLException) {
            throw failure("mark tactical session terminal", exception)
        }
    }

    private fun unlockForDefense(
        defenseId: UUID,
        targetTier: Int,
        operationId: UUID,
        progressKey: String,
    ): TacticalUnlockResult {
        Objects.requireNonNull(defenseId, "defenseId")
        Objects.requireNonNull(operationId, "operationId")
        if (targetTier < 0 || targetTier > 6) {
            throw IllegalArgumentException("targetTier must be between 0 and 6")
        }
        val fingerprint = "$progressKey|$targetTier"
        return try {
            database.inImmediateTransaction { connection ->
                val session = requireSessionByDefense(connection, defenseId)
                val prior = loadOperation(connection, operationId)
                if (prior.isPresent) {
                    requireMatchingOperation(prior.orElseThrow(), session.tacticalSessionId(), UNLOCK, fingerprint)
                    return@inImmediateTransaction TacticalUnlockResult.unchanged(
                        session.highestUnlockedTier(),
                    )
                }
                if (session.state() == TacticalBuildSessionState.TERMINAL
                    || session.state() == TacticalBuildSessionState.CANCELLED
                ) {
                    insertOperation(
                        connection,
                        operationId,
                        session.tacticalSessionId(),
                        UNLOCK,
                        fingerprint,
                        Instant.now(),
                    )
                    return@inImmediateTransaction TacticalUnlockResult.unchanged(
                        session.highestUnlockedTier(),
                    )
                }
                if (session.state() != TacticalBuildSessionState.ACTIVE) {
                    throw PersistenceConflictException(
                        "Tactical unlock progress requires an active tactical session",
                    )
                }
                val currentTier = session.highestUnlockedTier()
                val nextTier = maxOf(currentTier, targetTier)
                val definition = session.selectedDefinition().orElseThrow {
                    PersistenceConflictException(
                        "Active tactical session has no selected definition",
                    )
                }
                val now = Instant.now()
                insertOperation(
                    connection,
                    operationId,
                    session.tacticalSessionId(),
                    UNLOCK,
                    fingerprint,
                    now,
                )
                val unlockedNodeIds = LinkedHashSet(loadUnlockedNodeIds(connection, session.tacticalSessionId()))
                val newlyUnlocked = ArrayList<String>()
                val eligibleNodes = definition.nodes()
                    .filter { node ->
                        node.tier() > currentTier
                            && node.tier() <= nextTier
                            && belongsToSelectedBranch(session, node)
                    }
                    .sortedWith(compareBy<TacticalSkillNodeDefinition> { it.tier() }.thenBy { it.id() })
                var progressed: Boolean
                do {
                    progressed = false
                    for (node in eligibleNodes) {
                        if (!unlockedNodeIds.contains(node.id())
                            && unlockedNodeIds.containsAll(node.prerequisiteNodeIds())
                        ) {
                            connection.prepareStatement(
                                """
                                INSERT INTO tactical_build_node_unlocks(
                                    tactical_session_id, node_id, operation_id, unlocked_at)
                                VALUES (?, ?, ?, ?)
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, session.tacticalSessionId().toString())
                                statement.setString(2, node.id())
                                statement.setString(3, operationId.toString())
                                statement.setString(4, now.toString())
                                statement.executeUpdate()
                            }
                            unlockedNodeIds.add(node.id())
                            newlyUnlocked.add(node.id())
                            progressed = true
                        }
                    }
                } while (progressed)
                if (nextTier != currentTier) {
                    connection.prepareStatement(
                        """
                        UPDATE tactical_build_sessions
                        SET highest_unlocked_tier = ?, updated_at = ?
                        WHERE tactical_session_id = ? AND highest_unlocked_tier = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, nextTier)
                        statement.setString(2, now.toString())
                        statement.setString(3, session.tacticalSessionId().toString())
                        statement.setInt(4, currentTier)
                        if (statement.executeUpdate() != 1) {
                            throw PersistenceConflictException(
                                "The tactical unlock tier changed before it was persisted",
                            )
                        }
                    }
                }
                TacticalUnlockResult(
                    if (nextTier == currentTier) OperationOutcome.ALREADY_APPLIED else OperationOutcome.APPLIED,
                    nextTier,
                    newlyUnlocked,
                )
            }
        } catch (exception: SQLException) {
            throw failure("advance tactical unlock progress", exception)
        }
    }

    private fun insertSession(
        connection: Connection,
        requested: TacticalCandidateSet,
        createdAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO tactical_build_sessions(
                tactical_session_id, start_operation_id, team_id, stage, seed,
                generator_version, state, highest_unlocked_tier, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'GENERATED', 0, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, requested.tacticalSessionId().toString())
            statement.setString(2, requested.startOperationId().toString())
            statement.setString(3, requested.teamId().toString())
            statement.setInt(4, requested.stage())
            statement.setLong(5, requested.seed())
            statement.setInt(6, requested.generatorVersion())
            statement.setString(7, requested.generatedAt().toString())
            statement.setString(8, createdAt.toString())
            statement.executeUpdate()
        }
    }

    private fun insertOperation(
        connection: Connection,
        operationId: UUID,
        tacticalSessionId: UUID,
        operationKind: String,
        fingerprint: String,
        appliedAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO tactical_build_operations(
                operation_id, tactical_session_id, operation_kind,
                payload_fingerprint, applied_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.setString(2, tacticalSessionId.toString())
            statement.setString(3, operationKind)
            statement.setString(4, fingerprint)
            statement.setString(5, appliedAt.toString())
            statement.executeUpdate()
        }
    }

    private fun loadSessionByStartOperation(
        connection: Connection,
        operationId: UUID,
    ): Optional<TacticalBuildSession> {
        connection.prepareStatement(
            "SELECT tactical_session_id FROM tactical_build_sessions WHERE start_operation_id = ?",
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    loadSession(connection, uuid(resultSet.getString(1)))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun loadSession(
        connection: Connection,
        tacticalSessionId: UUID,
    ): Optional<TacticalBuildSession> {
        connection.prepareStatement(
            """
            SELECT tactical_session_id, start_operation_id, defense_id, team_id, stage, seed,
                   generator_version, state, selected_snapshot, selected_branch_id,
                   highest_unlocked_tier,
                   terminal_result, created_at, updated_at, terminal_at
            FROM tactical_build_sessions WHERE tactical_session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tacticalSessionId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return Optional.empty()
                }
                val selectedSnapshot = resultSet.getString("selected_snapshot")
                val terminalValue = resultSet.getString("terminal_result")
                val terminalAt = resultSet.getString("terminal_at")
                val loaded = TacticalBuildSession(
                    uuid(resultSet.getString("tactical_session_id")),
                    uuid(resultSet.getString("start_operation_id")),
                    Optional.ofNullable(resultSet.getString("defense_id")).map { uuid(it) },
                    uuid(resultSet.getString("team_id")),
                    resultSet.getInt("stage"),
                    resultSet.getLong("seed"),
                    resultSet.getInt("generator_version"),
                    TacticalBuildSessionState.valueOf(resultSet.getString("state")),
                    Optional.ofNullable(selectedSnapshot).map { TacticalDefinitionCodec.decode(it) },
                    Optional.ofNullable(resultSet.getString("selected_branch_id")),
                    resultSet.getInt("highest_unlocked_tier"),
                    Optional.ofNullable(terminalValue).map { TacticalTerminalResult.valueOf(it) },
                    instant(resultSet.getString("created_at")),
                    instant(resultSet.getString("updated_at")),
                    Optional.ofNullable(terminalAt).map { instant(it) },
                )
                loaded.selectedDefinition().ifPresent { definition ->
                    validateSelectedBranch(definition, loaded.selectedBranchId().orElse(null))
                }
                return Optional.of(loaded)
            }
        }
    }

    private fun loadCandidates(
        connection: Connection,
        tacticalSessionId: UUID,
    ): TacticalCandidateSet {
        val session = requireSession(connection, tacticalSessionId)
        val candidates = ArrayList<TacticalCandidate>()
        connection.prepareStatement(
            """
            SELECT candidate_slot, snapshot
            FROM tactical_build_candidates
            WHERE tactical_session_id = ? ORDER BY candidate_slot
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tacticalSessionId.toString())
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    candidates.add(
                        TacticalCandidate(
                            resultSet.getInt("candidate_slot"),
                            TacticalDefinitionCodec.decode(resultSet.getString("snapshot")),
                        ),
                    )
                }
            }
        }
        return TacticalCandidateSet(
            session.tacticalSessionId(),
            session.startOperationId(),
            session.teamId(),
            session.stage(),
            session.seed(),
            session.generatorVersion(),
            java.util.List.copyOf(candidates),
            session.createdAt(),
        )
    }

    private fun loadCandidateDefinition(
        connection: Connection,
        tacticalSessionId: UUID,
        buildId: String,
    ): Optional<TacticalBuildDefinition> {
        connection.prepareStatement(
            """
            SELECT snapshot FROM tactical_build_candidates
            WHERE tactical_session_id = ? AND build_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tacticalSessionId.toString())
            statement.setString(2, buildId)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(TacticalDefinitionCodec.decode(resultSet.getString(1)))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun loadOperation(connection: Connection, operationId: UUID): Optional<OperationRow> {
        connection.prepareStatement(
            """
            SELECT operation_id, tactical_session_id, operation_kind, payload_fingerprint
            FROM tactical_build_operations WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(
                        OperationRow(
                            uuid(resultSet.getString("operation_id")),
                            uuid(resultSet.getString("tactical_session_id")),
                            resultSet.getString("operation_kind"),
                            resultSet.getString("payload_fingerprint"),
                        ),
                    )
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun requireSession(connection: Connection, sessionId: UUID): TacticalBuildSession =
        loadSession(connection, sessionId).orElseThrow {
            PersistenceConflictException("Tactical session does not exist: $sessionId")
        }

    private fun requireSessionByDefense(connection: Connection, defenseId: UUID): TacticalBuildSession {
        connection.prepareStatement(
            "SELECT tactical_session_id FROM tactical_build_sessions WHERE defense_id = ?",
        ).use { statement ->
            statement.setString(1, defenseId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    throw PersistenceConflictException("No tactical build is bound to defense $defenseId")
                }
                return requireSession(connection, uuid(resultSet.getString(1)))
            }
        }
    }

    private fun requireTeam(connection: Connection, teamId: UUID) {
        connection.prepareStatement("SELECT 1 FROM teams WHERE team_id = ?").use { statement ->
            statement.setString(1, teamId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    throw PersistenceConflictException("Team does not exist: $teamId")
                }
            }
        }
    }

    private fun requireTeamOwner(connection: Connection, teamId: UUID, actorId: UUID) {
        connection.prepareStatement(
            """
            SELECT role FROM team_members WHERE team_id = ? AND player_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, teamId.toString())
            statement.setString(2, actorId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next() || resultSet.getString(1) != "OWNER") {
                    throw PersistenceConflictException("Only the tactical team owner may select a build")
                }
            }
        }
    }

    private fun requireDefenseMatches(
        connection: Connection,
        defenseId: UUID,
        teamId: UUID,
        stage: Int,
    ) {
        connection.prepareStatement(
            """
            SELECT team_id, stage_level FROM defense_events WHERE event_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, defenseId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()
                    || teamId.toString() != resultSet.getString("team_id")
                    || resultSet.getLong("stage_level") != stage.toLong()
                ) {
                    throw PersistenceConflictException("The defense does not match the tactical session")
                }
            }
        }
    }

    private fun requireMatchingOperation(
        operation: OperationRow,
        tacticalSessionId: UUID,
        kind: String,
        fingerprint: String,
    ) {
        if (operation.tacticalSessionId != tacticalSessionId
            || operation.kind != kind
            || operation.fingerprint != fingerprint
        ) {
            throw PersistenceConflictException(
                "The tactical operation UUID is already assigned to another payload",
            )
        }
    }

    private fun selectionView(
        connection: Connection,
        session: TacticalBuildSession,
    ): TacticalBuildSelectionView {
        val definition = session.selectedDefinition().orElseThrow {
            PersistenceConflictException("Tactical session does not have a selected definition")
        }
        return definition.selectionView(
            session.tacticalSessionId(),
            session.teamId(),
            session.stage(),
            session.highestUnlockedTier(),
            session.selectedBranchId(),
            loadUnlockedNodeIds(connection, session.tacticalSessionId()),
        )
    }

    private fun validateSelectedBranch(definition: TacticalBuildDefinition, selectedBranchId: String?) {
        Objects.requireNonNull(definition, "definition")
        val branchIds = definition.branchIds()
        if (branchIds.isEmpty()) {
            if (selectedBranchId != null) {
                throw PersistenceConflictException(
                    "The selected tactical build does not have a branch choice",
                )
            }
            return
        }
        if (selectedBranchId == null) {
            throw PersistenceConflictException("A branch must be selected for this tactical build")
        }
        if (!branchIds.contains(selectedBranchId)) {
            throw PersistenceConflictException("The selected tactical branch is not available in this build")
        }
    }

    private fun belongsToSelectedBranch(
        session: TacticalBuildSession,
        node: TacticalSkillNodeDefinition,
    ): Boolean {
        if (node.branchId().isEmpty) {
            return true
        }
        return session.selectedBranchId().isPresent
            && session.selectedBranchId().orElseThrow() == node.branchId().orElseThrow()
    }

    private fun loadUnlockedNodeIds(connection: Connection, tacticalSessionId: UUID): Set<String> {
        val nodeIds = LinkedHashSet<String>()
        connection.prepareStatement(
            """
            SELECT node_id FROM tactical_build_node_unlocks
            WHERE tactical_session_id = ?
            UNION
            SELECT node_id FROM tactical_build_unlocked_nodes
            WHERE tactical_session_id = ?
            ORDER BY node_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tacticalSessionId.toString())
            statement.setString(2, tacticalSessionId.toString())
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    nodeIds.add(resultSet.getString(1))
                }
            }
        }
        return nodeIds
    }

    private fun generationFingerprint(candidates: TacticalCandidateSet): String =
        candidates.candidates().asSequence()
            .map { candidate ->
                "${candidate.slot}:${TacticalDefinitionCodec.encode(candidate.definition)}"
            }
            .fold(
                "${candidates.startOperationId()}|${candidates.teamId()}|${candidates.stage()}|${candidates.seed()}|${candidates.generatorVersion()}",
            ) { left, right -> "$left;$right" }

    private fun <T> read(operation: String, work: Database.SqlWork<T>): T {
        return try {
            database.openConnection().use { connection -> work.execute(connection) }
        } catch (exception: SQLException) {
            throw failure(operation, exception)
        }
    }

    private fun failure(operation: String, exception: SQLException): PersistenceException =
        PersistenceException("Could not $operation", exception)

    private fun uuid(value: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (invalidUuid: IllegalArgumentException) {
            throw PersistenceException("Invalid UUID in tactical persistence", invalidUuid)
        }
    }

    private fun instant(value: String): Instant {
        return try {
            Instant.parse(value)
        } catch (invalidInstant: RuntimeException) {
            throw PersistenceException("Invalid timestamp in tactical persistence", invalidInstant)
        }
    }

    private data class OperationRow(
        val operationId: UUID,
        val tacticalSessionId: UUID,
        val kind: String,
        val fingerprint: String,
    )

}
