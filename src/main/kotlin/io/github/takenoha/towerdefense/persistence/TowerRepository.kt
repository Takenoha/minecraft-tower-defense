package io.github.takenoha.towerdefense.persistence

import io.github.takenoha.towerdefense.config.TowerSettings
import io.github.takenoha.towerdefense.domain.DefensePhase
import io.github.takenoha.towerdefense.domain.TeamProgress
import io.github.takenoha.towerdefense.domain.TowerResearch
import io.github.takenoha.towerdefense.domain.TowerTargetPriority
import io.github.takenoha.towerdefense.domain.TowerType
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.ArrayList
import java.util.Objects
import java.util.Optional
import java.util.UUID

/** Transactional persistence boundary for installed towers and their placement stop window. */
class TowerRepository(database: Database) {
    private val database: Database = Objects.requireNonNull(database, "database")

    fun loadAllTowers(): List<TowerRecord> = read("load all towers") { connection ->
        val towers = ArrayList<TowerRecord>()
        connection.prepareStatement(
            """
            SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                   tower_type, individual_level, target_priority,
                   current_hp, max_hp, entity_id, created_at, updated_at
            FROM towers
            ORDER BY tower_id
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    towers.add(towerFromRow(resultSet))
                }
            }
        }
        java.util.List.copyOf(towers)
    }

    fun findTower(towerId: UUID): Optional<TowerRecord> {
        Objects.requireNonNull(towerId, "towerId")
        return read("load a tower") { connection -> loadTower(connection, towerId) }
    }

    /** Loads all per-type research caps for one team in stable type order. */
    fun loadTowerResearch(teamId: UUID): List<TowerResearch> {
        Objects.requireNonNull(teamId, "teamId")
        return read("load tower research") { connection -> loadTowerResearch(connection, teamId) }
    }

    fun findTowerResearch(teamId: UUID, towerType: TowerType): Optional<TowerResearch> {
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(towerType, "towerType")
        return read("load tower research") { connection ->
            loadTowerResearch(connection, teamId, towerType)
        }
    }

    /** Spends team research points for exactly one level of one tower type. */
    fun purchaseTowerResearch(
        teamId: UUID,
        actorId: UUID,
        towerType: TowerType,
        researchPointCost: Long,
        operationId: UUID,
        appliedAt: Instant,
    ): TowerResearchMutationResult {
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(actorId, "actorId")
        Objects.requireNonNull(towerType, "towerType")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        if (researchPointCost <= 0L) {
            throw IllegalArgumentException("researchPointCost must be positive")
        }
        val fingerprint = researchFingerprint(teamId, actorId, towerType, researchPointCost)
        return try {
            database.inImmediateTransaction { connection ->
                val existingFingerprint = loadResearchOperationFingerprint(connection, operationId)
                if (existingFingerprint.isPresent) {
                    if (existingFingerprint.orElseThrow() != fingerprint) {
                        throw PersistenceConflictException(
                            "The tower research operation UUID is already assigned to a different payload",
                        )
                    }
                    return@inImmediateTransaction TowerResearchMutationResult(
                        OperationOutcome.ALREADY_APPLIED,
                        requireTeamProgress(connection, teamId),
                        requireTowerResearch(connection, teamId, towerType),
                    )
                }

                requireNoActiveEvent(connection, "purchase tower research")
                requireTeamMember(connection, teamId, actorId)
                val progress = requireTeamProgress(connection, teamId)
                val current = requireTowerResearch(connection, teamId, towerType)
                if (progress.researchPoints < researchPointCost) {
                    throw PersistenceConflictException(
                        "The team does not have enough research points for this purchase",
                    )
                }
                if (current.researchLevel == Int.MAX_VALUE) {
                    throw PersistenceConflictException(
                        "The tower research level has reached its maximum value",
                    )
                }

                val nextLevel = current.researchLevel + 1
                val updatedProgress = TeamProgress(
                    progress.teamId,
                    progress.highestClearedLevel,
                    progress.unlockedLevel,
                    progress.researchPoints - researchPointCost,
                )
                val updatedResearch = TowerResearch(
                    current.teamId,
                    current.towerType,
                    nextLevel,
                    appliedAt,
                )
                updateTeamResearchPoints(connection, updatedProgress, appliedAt)
                updateTowerResearch(connection, updatedResearch)
                insertResearchOperation(
                    connection,
                    operationId,
                    teamId,
                    actorId,
                    towerType,
                    researchPointCost,
                    fingerprint,
                    appliedAt,
                )
                TowerResearchMutationResult(
                    OperationOutcome.APPLIED,
                    updatedProgress,
                    updatedResearch,
                )
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower research purchase conflicts with persisted data",
                    exception,
                )
            }
            throw failure("purchase tower research", exception)
        }
    }

    /** Reserves one individual level upgrade before the physical materials are removed. */
    fun prepareTowerUpgrade(upgrade: TowerUpgrade): TowerUpgrade {
        Objects.requireNonNull(upgrade, "upgrade")
        if (upgrade.state() != TowerUpgradeState.PREPARED) {
            throw IllegalArgumentException("A tower upgrade request must be PREPARED")
        }
        val fingerprint = upgradeFingerprint(upgrade)
        val normalized = withFingerprint(upgrade, fingerprint)
        return try {
            database.inImmediateTransaction { connection ->
                val existing = loadTowerUpgrade(connection, upgrade.operationId())
                if (existing.isPresent) {
                    requireMatchingUpgrade(existing.orElseThrow(), normalized)
                    return@inImmediateTransaction existing.orElseThrow()
                }
                requireTowerPlacementWindow(
                    connection,
                    upgrade.teamId(),
                    "prepare a tower upgrade",
                )
                requireTeamMember(connection, upgrade.teamId(), upgrade.actorId())
                val tower = loadTower(connection, upgrade.towerId()).orElseThrow {
                    PersistenceConflictException("The tower to upgrade does not exist")
                }
                if (tower.teamId() != upgrade.teamId()
                    || tower.individualLevel() != upgrade.fromLevel()
                ) {
                    throw PersistenceConflictException(
                        "The tower level changed before the upgrade was prepared",
                    )
                }
                requireTowerResearch(
                    connection,
                    tower.teamId(),
                    tower.type(),
                    upgrade.toLevel(),
                )
                insertTowerUpgrade(connection, normalized)
                normalized
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower upgrade conflicts with persisted tower data",
                    exception,
                )
            }
            throw failure("prepare a tower upgrade", exception)
        }
    }

    fun applyTowerUpgrade(operationId: UUID, appliedAt: Instant): TowerUpgradeResult =
        applyTowerUpgrade(operationId, appliedAt, PaymentMode.LEGACY_ITEMS)

    /** Applies a level mutation and debits the team point wallet atomically. */
    fun applyTowerUpgradeFromWallet(
        operationId: UUID,
        appliedAt: Instant,
    ): TowerUpgradeResult = applyTowerUpgrade(operationId, appliedAt, PaymentMode.POINT_WALLET)

    private fun applyTowerUpgrade(
        operationId: UUID,
        appliedAt: Instant,
        paymentMode: PaymentMode,
    ): TowerUpgradeResult {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val upgrade = loadTowerUpgrade(connection, operationId).orElseThrow {
                    PersistenceConflictException("The prepared tower upgrade does not exist")
                }
                if (upgrade.paymentMode() != paymentMode) {
                    throw PersistenceConflictException(
                        "The tower upgrade payment mode does not match the request",
                    )
                }
                if (upgrade.state() == TowerUpgradeState.APPLIED) {
                    return@inImmediateTransaction TowerUpgradeResult(
                        OperationOutcome.ALREADY_APPLIED,
                        loadTower(connection, upgrade.towerId()),
                    )
                }
                if (upgrade.state() == TowerUpgradeState.ROLLED_BACK) {
                    throw PersistenceConflictException(
                        "The prepared tower upgrade was already rolled back",
                    )
                }
                requireTowerPlacementWindow(
                    connection,
                    upgrade.teamId(),
                    "apply a tower upgrade",
                )
                requireTeamMember(connection, upgrade.teamId(), upgrade.actorId())
                val current = loadTower(connection, upgrade.towerId()).orElseThrow {
                    PersistenceConflictException("The tower to upgrade does not exist")
                }
                if (current.teamId() != upgrade.teamId()
                    || current.individualLevel() != upgrade.fromLevel()
                ) {
                    throw PersistenceConflictException(
                        "The tower level changed before the upgrade was applied",
                    )
                }
                if (paymentMode == PaymentMode.LEGACY_ITEMS) {
                    val receipts = loadTowerUpgradeReceipts(connection, operationId)
                    requireSecuredReceipt(receipts, "DEFENSE_SHARD", upgrade.defenseShardCost())
                    requireSecuredReceipt(
                        receipts,
                        "ENHANCEMENT_CORE",
                        upgrade.enhancementCoreCost(),
                    )
                }
                if (paymentMode == PaymentMode.POINT_WALLET) {
                    ResourceRepository.debitInTransaction(
                        connection,
                        upgrade.teamId(),
                        upgrade.actorId(),
                        ResourceType.DEFENSE_POINTS,
                        upgrade.defenseShardCost().toLong(),
                        UUID.nameUUIDFromBytes(
                            (operationId.toString() + "|DEFENSE_POINTS")
                                .toByteArray(StandardCharsets.UTF_8),
                        ),
                        operationId.toString(),
                        upgrade.payloadFingerprint() + "|DEFENSE_POINTS",
                        appliedAt,
                    )
                    ResourceRepository.debitInTransaction(
                        connection,
                        upgrade.teamId(),
                        upgrade.actorId(),
                        ResourceType.ENHANCEMENT_POINTS,
                        upgrade.enhancementCoreCost().toLong(),
                        UUID.nameUUIDFromBytes(
                            (operationId.toString() + "|ENHANCEMENT_POINTS")
                                .toByteArray(StandardCharsets.UTF_8),
                        ),
                        operationId.toString(),
                        upgrade.payloadFingerprint() + "|ENHANCEMENT_POINTS",
                        appliedAt,
                    )
                }
                connection.prepareStatement(
                    """
                    UPDATE towers
                    SET individual_level = ?, updated_at = ?
                    WHERE tower_id = ? AND individual_level = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, upgrade.toLevel())
                    statement.setString(2, appliedAt.toString())
                    statement.setString(3, upgrade.towerId().toString())
                    statement.setInt(4, upgrade.fromLevel())
                    if (statement.executeUpdate() != 1) {
                        throw PersistenceConflictException(
                            "The tower upgrade lost its compare-and-set race",
                        )
                    }
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_upgrade_operations
                    SET state = 'APPLIED', applied_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, appliedAt.toString())
                    statement.setString(2, operationId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower upgrade apply affected no rows")
                    }
                }
                TowerUpgradeResult(
                    OperationOutcome.APPLIED,
                    loadTower(connection, upgrade.towerId()),
                )
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower upgrade conflicts with persisted tower data",
                    exception,
                )
            }
            throw failure("apply a tower upgrade", exception)
        }
    }

    /** Rolls back a reservation when the Paper material handoff did not complete. */
    fun rollbackTowerUpgrade(
        operationId: UUID,
        rolledBackAt: Instant,
    ): Optional<TowerUpgrade> {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(rolledBackAt, "rolledBackAt")
        return try {
            database.inImmediateTransaction { connection ->
                val loaded = loadTowerUpgrade(connection, operationId)
                if (loaded.isEmpty() || loaded.orElseThrow().state() != TowerUpgradeState.PREPARED) {
                    return@inImmediateTransaction loaded
                }
                val receipts = loadTowerUpgradeReceipts(connection, operationId)
                if (receipts.isNotEmpty()) {
                    connection.prepareStatement(
                        """
                        UPDATE tower_upgrade_receipts
                        SET state = 'RETURN_PENDING', resolved_at = ?
                        WHERE operation_id = ? AND state IN ('RESERVED', 'SECURED')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, rolledBackAt.toString())
                        statement.setString(2, operationId.toString())
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_upgrade_operations
                    SET state = 'ROLLED_BACK', rolled_back_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, rolledBackAt.toString())
                    statement.setString(2, operationId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower upgrade rollback affected no rows")
                    }
                }
                val upgrade = loaded.orElseThrow()
                Optional.of(
                    TowerUpgrade(
                        upgrade.operationId(),
                        upgrade.towerId(),
                        upgrade.actorId(),
                        upgrade.teamId(),
                        upgrade.fromLevel(),
                        upgrade.toLevel(),
                        upgrade.defenseShardCost(),
                        upgrade.enhancementCoreCost(),
                        upgrade.payloadFingerprint(),
                        TowerUpgradeState.ROLLED_BACK,
                        upgrade.preparedAt(),
                        null,
                        rolledBackAt,
                        upgrade.paymentMode(),
                    ),
                )
            }
        } catch (exception: SQLException) {
            throw failure("roll back a tower upgrade", exception)
        }
    }

    /** Creates the two durable physical-material receipts for a legacy upgrade. */
    fun reserveTowerUpgradeReceipts(
        operationId: UUID,
        playerId: UUID,
        reservedAt: Instant,
    ): List<TowerUpgradeReceipt> {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(playerId, "playerId")
        Objects.requireNonNull(reservedAt, "reservedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val upgrade = loadTowerUpgrade(connection, operationId).orElseThrow {
                    PersistenceConflictException("The prepared tower upgrade does not exist")
                }
                if (upgrade.state() != TowerUpgradeState.PREPARED
                    || upgrade.paymentMode() != PaymentMode.LEGACY_ITEMS
                    || upgrade.actorId() != playerId
                ) {
                    throw PersistenceConflictException(
                        "The tower upgrade is not reservable for legacy materials",
                    )
                }
                val existing = loadTowerUpgradeReceipts(connection, operationId)
                if (existing.isNotEmpty()) {
                    requireMatchingTowerUpgradeReceipts(existing, upgrade, playerId)
                    return@inImmediateTransaction existing
                }
                val shards = TowerUpgradeReceipt(
                    operationId,
                    playerId,
                    "DEFENSE_SHARD",
                    upgrade.defenseShardCost().toLong(),
                    TowerUpgradeReceiptState.RESERVED,
                    reservedAt,
                    null,
                )
                val cores = TowerUpgradeReceipt(
                    operationId,
                    playerId,
                    "ENHANCEMENT_CORE",
                    upgrade.enhancementCoreCost().toLong(),
                    TowerUpgradeReceiptState.RESERVED,
                    reservedAt,
                    null,
                )
                insertTowerUpgradeReceipt(connection, shards)
                insertTowerUpgradeReceipt(connection, cores)
                java.util.List.of(shards, cores)
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower upgrade receipt conflicts with persisted data",
                    exception,
                )
            }
            throw failure("reserve tower upgrade receipts", exception)
        }
    }

    /** Records the exact physical material handoff as a durable SECURED state. */
    fun secureTowerUpgradeReceipts(operationId: UUID, securedAt: Instant): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(securedAt, "securedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val receipts = loadTowerUpgradeReceipts(connection, operationId)
                if (receipts.size != 2) {
                    throw PersistenceConflictException(
                        "The tower upgrade does not have both material receipts",
                    )
                }
                val alreadySecured = receipts.all {
                    it.state() == TowerUpgradeReceiptState.SECURED
                }
                if (alreadySecured) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (receipts.any { it.state() != TowerUpgradeReceiptState.RESERVED }) {
                    throw PersistenceConflictException(
                        "The tower upgrade receipts are not in the reservable state",
                    )
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_upgrade_receipts
                    SET state = 'SECURED'
                    WHERE operation_id = ? AND state = 'RESERVED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, operationId.toString())
                    if (statement.executeUpdate() != 2) {
                        throw PersistenceConflictException(
                            "The tower upgrade receipt handoff changed concurrently",
                        )
                    }
                }
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("secure tower upgrade receipts", exception)
        }
    }

    /** Marks receipts clear-pending before the applied level mutation removes physical stacks. */
    fun markTowerUpgradeReceiptsClearPending(
        operationId: UUID,
        pendingAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(pendingAt, "pendingAt")
        return try {
            database.inImmediateTransaction { connection ->
                val upgrade = loadTowerUpgrade(connection, operationId).orElseThrow {
                    PersistenceConflictException("The prepared tower upgrade does not exist")
                }
                if (upgrade.state() != TowerUpgradeState.APPLIED) {
                    throw PersistenceConflictException(
                        "Only an applied tower upgrade can clear receipts",
                    )
                }
                val receipts = loadTowerUpgradeReceipts(connection, operationId)
                if (receipts.isEmpty()
                    || receipts.all {
                        it.state() == TowerUpgradeReceiptState.CLEARED
                            || it.state() == TowerUpgradeReceiptState.CLEAR_PENDING
                    }
                ) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (receipts.any { it.state() != TowerUpgradeReceiptState.SECURED }) {
                    throw PersistenceConflictException(
                        "Only secured tower upgrade receipts can enter physical clear",
                    )
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_upgrade_receipts
                    SET state = 'CLEAR_PENDING', resolved_at = ?
                    WHERE operation_id = ? AND state = 'SECURED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, pendingAt.toString())
                    statement.setString(2, operationId.toString())
                    if (statement.executeUpdate() != receipts.size) {
                        throw PersistenceConflictException(
                            "The tower upgrade receipts changed concurrently",
                        )
                    }
                }
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("mark tower upgrade receipts clear-pending", exception)
        }
    }

    fun clearTowerUpgradeReceipts(operationId: UUID, clearedAt: Instant): OperationOutcome =
        resolveTowerUpgradeReceipts(operationId, TowerUpgradeReceiptState.CLEARED, clearedAt)

    /** Marks receipts restored after a prepared legacy upgrade is rolled back. */
    fun restoreTowerUpgradeReceipts(operationId: UUID, restoredAt: Instant): OperationOutcome =
        resolveTowerUpgradeReceipts(operationId, TowerUpgradeReceiptState.RESTORED, restoredAt)

    fun findTowerUpgradeReceipts(operationId: UUID): List<TowerUpgradeReceipt> {
        Objects.requireNonNull(operationId, "operationId")
        return read("load tower upgrade receipts") { connection ->
            loadTowerUpgradeReceipts(connection, operationId)
        }
    }

    /** Loads legacy upgrades whose physical receipt handoff may need join/startup recovery. */
    fun loadPreparedTowerUpgrades(): List<TowerUpgrade> =
        read("load prepared tower upgrades") { connection ->
            val upgrades = ArrayList<TowerUpgrade>()
            connection.prepareStatement(
                """
                SELECT operation_id, tower_id, actor_id, team_id, from_level, to_level,
                       defense_shard_cost, enhancement_core_cost, payload_fingerprint,
                       payment_mode, state, prepared_at, applied_at, rolled_back_at
                FROM tower_upgrade_operations
                WHERE payment_mode = 'LEGACY_ITEMS'
                  AND (state = 'PREPARED'
                       OR (state = 'APPLIED' AND EXISTS (
                           SELECT 1
                           FROM tower_upgrade_receipts r
                           WHERE r.operation_id = tower_upgrade_operations.operation_id
                             AND r.state IN ('RESERVED', 'SECURED', 'CLEAR_PENDING')
                       ))
                       OR (state = 'ROLLED_BACK' AND EXISTS (
                           SELECT 1
                           FROM tower_upgrade_receipts r
                           WHERE r.operation_id = tower_upgrade_operations.operation_id
                             AND r.state = 'RETURN_PENDING'
                       )))
                ORDER BY prepared_at, operation_id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        upgrades.add(towerUpgradeFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(upgrades)
        }

    /** Loads terminal legacy-upgrade receipt tombstones for idempotent join reconciliation. */
    fun loadTerminalTowerUpgradeReceipts(playerId: UUID): List<TowerUpgrade> {
        Objects.requireNonNull(playerId, "playerId")
        return read("load terminal tower upgrade receipt tombstones") { connection ->
            val upgrades = ArrayList<TowerUpgrade>()
            connection.prepareStatement(
                """
                SELECT operation_id, tower_id, actor_id, team_id, from_level, to_level,
                       defense_shard_cost, enhancement_core_cost, payload_fingerprint,
                       payment_mode, state, prepared_at, applied_at, rolled_back_at
                FROM tower_upgrade_operations
                WHERE actor_id = ?
                  AND payment_mode = 'LEGACY_ITEMS'
                  AND ((state = 'APPLIED' AND EXISTS (
                           SELECT 1 FROM tower_upgrade_receipts r
                           WHERE r.operation_id = tower_upgrade_operations.operation_id
                             AND r.state = 'CLEARED'
                       ))
                       OR (state = 'ROLLED_BACK' AND EXISTS (
                           SELECT 1 FROM tower_upgrade_receipts r
                           WHERE r.operation_id = tower_upgrade_operations.operation_id
                             AND r.state = 'RESTORED'
                       )))
                ORDER BY prepared_at, operation_id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        upgrades.add(towerUpgradeFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(upgrades)
        }
    }

    /** Updates one tower's target-selection mode after validating team membership. */
    fun updateTargetPriority(
        towerId: UUID,
        actorId: UUID,
        targetPriority: TowerTargetPriority,
        updatedAt: Instant,
    ): TowerRecord {
        Objects.requireNonNull(towerId, "towerId")
        Objects.requireNonNull(actorId, "actorId")
        Objects.requireNonNull(targetPriority, "targetPriority")
        Objects.requireNonNull(updatedAt, "updatedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val current = loadTower(connection, towerId).orElseThrow {
                    PersistenceConflictException("The tower to update does not exist")
                }
                requireTeamMember(connection, current.teamId(), actorId)
                if (current.targetPriority() == targetPriority) {
                    return@inImmediateTransaction current
                }
                connection.prepareStatement(
                    """
                    UPDATE towers
                    SET target_priority = ?, updated_at = ?
                    WHERE tower_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, targetPriority.id())
                    statement.setString(2, updatedAt.toString())
                    statement.setString(3, towerId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower target priority update affected no rows")
                    }
                }
                loadTower(connection, towerId).orElseThrow {
                    SQLException("The tower disappeared after target priority update")
                }
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower target priority is invalid",
                    exception,
                )
            }
            throw failure("update a tower target priority", exception)
        }
    }

    /** Loads physical tower identities whose item handoff may have been interrupted. */
    fun loadAppliedTowerIds(): List<UUID> = read("load applied tower identities") { connection ->
        val ids = ArrayList<UUID>()
        connection.prepareStatement(
            "SELECT tower_id FROM towers ORDER BY created_at, tower_id",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    ids.add(uuid(resultSet.getString("tower_id")))
                }
            }
        }
        java.util.List.copyOf(ids)
    }

    fun loadPendingTowerPlacements(): List<TowerPlacement> =
        read("load pending tower placements") { connection ->
            val placements = ArrayList<TowerPlacement>()
            connection.prepareStatement(
                """
                SELECT operation_id, tower_id, actor_id, team_id, world_id,
                       block_x, block_y, block_z, tower_type, individual_level,
                       target_priority,
                       state, prepared_at, applied_at, rolled_back_at
                FROM tower_placement_operations
                WHERE state = 'PREPARED'
                ORDER BY prepared_at, operation_id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        placements.add(placementFromRow(resultSet))
                    }
                }
            }
            java.util.List.copyOf(placements)
        }

    /** Loads removal operations whose physical stop window was not completed. */
    fun loadPendingTowerRemovals(): List<TowerRemoval> =
        read("load pending tower removals") { connection ->
            loadRemovals(connection, "PREPARED")
        }

    /** Loads applied removals so a restart can finish deleting stale physical entities. */
    fun loadAppliedTowerRemovals(): List<TowerRemoval> =
        read("load applied tower removals") { connection ->
            loadRemovals(connection, "APPLIED")
        }

    /** Reserves one installed tower for removal after checking the global defense lock. */
    fun prepareTowerRemoval(removal: TowerRemoval): TowerRemoval {
        Objects.requireNonNull(removal, "removal")
        if (removal.state() != TowerRemovalState.PREPARED) {
            throw IllegalArgumentException("A tower removal request must be PREPARED")
        }
        return try {
            database.inImmediateTransaction { connection ->
                val existing = loadRemoval(connection, removal.operationId())
                if (existing.isPresent) {
                    requireMatchingRemoval(existing.orElseThrow(), removal)
                    return@inImmediateTransaction existing.orElseThrow()
                }
                requireNoActiveEvent(connection, "prepare a tower removal")
                requireTeamMember(connection, removal.teamId(), removal.actorId())
                val tower = loadTower(connection, removal.towerId()).orElseThrow {
                    PersistenceConflictException("The tower to remove does not exist")
                }
                requireMatchingTower(tower, removal)
                insertRemoval(connection, removal)
                removal
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower removal conflicts with persisted tower data",
                    exception,
                )
            }
            throw failure("prepare a tower removal", exception)
        }
    }

    /** Deletes the durable tower row after the returned item has been secured physically. */
    fun applyTowerRemoval(operationId: UUID, appliedAt: Instant): TowerRemoval {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(appliedAt, "appliedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val removal = loadRemoval(connection, operationId).orElseThrow {
                    PersistenceConflictException("The prepared tower removal does not exist")
                }
                if (removal.state() == TowerRemovalState.APPLIED) {
                    return@inImmediateTransaction removal
                }
                if (removal.state() == TowerRemovalState.ROLLED_BACK) {
                    throw PersistenceConflictException(
                        "The prepared tower removal was already rolled back",
                    )
                }
                requireNoActiveEvent(connection, "apply a tower removal")
                requireTeamMember(connection, removal.teamId(), removal.actorId())
                val tower = loadTower(connection, removal.towerId()).orElseThrow {
                    PersistenceConflictException("The tower to remove does not exist")
                }
                requireMatchingTower(tower, removal)
                connection.prepareStatement(
                    "DELETE FROM towers WHERE tower_id = ? AND entity_id = ?",
                ).use { statement ->
                    statement.setString(1, removal.towerId().toString())
                    statement.setString(2, removal.entityId().toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower removal deleted no rows")
                    }
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_removal_operations
                    SET state = 'APPLIED', applied_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, appliedAt.toString())
                    statement.setString(2, operationId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower removal apply affected no rows")
                    }
                }
                TowerRemoval(
                    removal.operationId(),
                    removal.towerId(),
                    removal.actorId(),
                    removal.teamId(),
                    removal.worldId(),
                    removal.blockX(),
                    removal.blockY(),
                    removal.blockZ(),
                    removal.type(),
                    removal.individualLevel(),
                    removal.entityId(),
                    TowerRemovalState.APPLIED,
                    removal.preparedAt(),
                    appliedAt,
                    null,
                )
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower removal conflicts with persisted tower data",
                    exception,
                )
            }
            throw failure("apply a tower removal", exception)
        }
    }

    /** Rolls back a prepared removal when its physical stop window did not complete. */
    fun rollbackTowerRemoval(
        operationId: UUID,
        rolledBackAt: Instant,
    ): Optional<TowerRemoval> {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(rolledBackAt, "rolledBackAt")
        return try {
            database.inImmediateTransaction { connection ->
                val loaded = loadRemoval(connection, operationId)
                if (loaded.isEmpty() || loaded.orElseThrow().state() != TowerRemovalState.PREPARED) {
                    return@inImmediateTransaction loaded
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_removal_operations
                    SET state = 'ROLLED_BACK', rolled_back_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, rolledBackAt.toString())
                    statement.setString(2, operationId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower removal rollback affected no rows")
                    }
                }
                val removal = loaded.orElseThrow()
                Optional.of(
                    TowerRemoval(
                        removal.operationId(),
                        removal.towerId(),
                        removal.actorId(),
                        removal.teamId(),
                        removal.worldId(),
                        removal.blockX(),
                        removal.blockY(),
                        removal.blockZ(),
                        removal.type(),
                        removal.individualLevel(),
                        removal.entityId(),
                        TowerRemovalState.ROLLED_BACK,
                        removal.preparedAt(),
                        null,
                        rolledBackAt,
                    ),
                )
            }
        } catch (exception: SQLException) {
            throw failure("roll back a prepared tower removal", exception)
        }
    }

    /** Reserves an item identity and validates team membership, event lock, and tower capacity. */
    fun prepareTowerPlacement(
        placement: TowerPlacement,
        settings: TowerSettings,
    ): TowerPlacement {
        Objects.requireNonNull(placement, "placement")
        Objects.requireNonNull(settings, "settings")
        if (placement.state() != TowerPlacementState.PREPARED) {
            throw IllegalArgumentException("A tower placement request must be PREPARED")
        }
        return try {
            database.inImmediateTransaction { connection ->
                val existing = loadPlacement(connection, placement.operationId())
                if (existing.isPresent) {
                    requireMatchingPlacement(existing.orElseThrow(), placement)
                    return@inImmediateTransaction existing.orElseThrow()
                }
                requireTowerPlacementWindow(
                    connection,
                    placement.teamId(),
                    "prepare a tower placement",
                )
                requireTeamMember(connection, placement.teamId(), placement.actorId())
                requireTowerCapacity(connection, placement.teamId(), settings)
                requireTowerResearch(
                    connection,
                    placement.teamId(),
                    placement.type(),
                    placement.individualLevel(),
                )
                if (loadTower(connection, placement.towerId()).isPresent) {
                    throw PersistenceConflictException(
                        "The tower item identity has already been used",
                    )
                }
                if (towerAt(
                        connection,
                        placement.worldId(),
                        placement.blockX(),
                        placement.blockY(),
                        placement.blockZ(),
                    ).isPresent
                ) {
                    throw PersistenceConflictException(
                        "Another tower already occupies that position",
                    )
                }
                insertPlacement(connection, placement)
                placement
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower placement conflicts with persisted tower data",
                    exception,
                )
            }
            throw failure("prepare a tower placement", exception)
        }
    }

    /** Applies the database side after the Paper entity has been spawned and tagged. */
    fun applyTowerPlacement(
        operationId: UUID,
        entityId: UUID,
        settings: TowerSettings,
        appliedAt: Instant,
    ): TowerRecord {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(entityId, "entityId")
        Objects.requireNonNull(settings, "settings")
        Objects.requireNonNull(appliedAt, "appliedAt")
        return try {
            database.inImmediateTransaction { connection ->
                val placement = loadPlacement(connection, operationId).orElseThrow {
                    PersistenceConflictException("The prepared tower placement does not exist")
                }
                if (placement.state() == TowerPlacementState.APPLIED) {
                    val tower = loadTower(connection, placement.towerId()).orElseThrow {
                        PersistenceConflictException(
                            "An applied tower placement has no tower row",
                        )
                    }
                    val appliedEntity = loadPlacementEntityId(connection, operationId).orElseThrow {
                        PersistenceConflictException(
                            "An applied tower placement has no entity identity",
                        )
                    }
                    if (appliedEntity != entityId) {
                        throw PersistenceConflictException(
                            "The tower placement entity identity does not match",
                        )
                    }
                    return@inImmediateTransaction tower
                }
                if (placement.state() == TowerPlacementState.ROLLED_BACK) {
                    throw PersistenceConflictException(
                        "The prepared tower placement was already rolled back",
                    )
                }
                requireTowerPlacementWindow(
                    connection,
                    placement.teamId(),
                    "apply a tower placement",
                )
                requireTeamMember(connection, placement.teamId(), placement.actorId())
                requireTowerCapacity(connection, placement.teamId(), settings)
                requireTowerResearch(
                    connection,
                    placement.teamId(),
                    placement.type(),
                    placement.individualLevel(),
                )
                if (loadTower(connection, placement.towerId()).isPresent) {
                    throw PersistenceConflictException(
                        "The tower item identity has already been applied",
                    )
                }
                if (towerAt(
                        connection,
                        placement.worldId(),
                        placement.blockX(),
                        placement.blockY(),
                        placement.blockZ(),
                    ).isPresent
                ) {
                    throw PersistenceConflictException(
                        "Another tower already occupies that position",
                    )
                }
                val tower = TowerRecord(
                    placement.towerId(),
                    placement.teamId(),
                    placement.worldId(),
                    placement.blockX(),
                    placement.blockY(),
                    placement.blockZ(),
                    placement.type(),
                    placement.individualLevel(),
                    placement.targetPriority(),
                    settings.towerMaximumHitPoints().toLong(),
                    settings.towerMaximumHitPoints().toLong(),
                    entityId,
                    appliedAt,
                    appliedAt,
                )
                insertTower(connection, tower)
                connection.prepareStatement(
                    """
                    UPDATE tower_placement_operations
                    SET entity_id = ?, state = 'APPLIED', applied_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, entityId.toString())
                    statement.setString(2, appliedAt.toString())
                    statement.setString(3, operationId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower placement apply affected no rows")
                    }
                }
                tower
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                throw PersistenceConflictException(
                    "The tower placement conflicts with persisted tower data",
                    exception,
                )
            }
            throw failure("apply a tower placement", exception)
        }
    }

    /** Marks a prepared operation rolled back after the physical entity was removed. */
    fun rollbackTowerPlacement(
        operationId: UUID,
        rolledBackAt: Instant,
    ): Optional<TowerPlacement> {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(rolledBackAt, "rolledBackAt")
        return try {
            database.inImmediateTransaction { connection ->
                val loaded = loadPlacement(connection, operationId)
                if (loaded.isEmpty() || loaded.orElseThrow().state() != TowerPlacementState.PREPARED) {
                    return@inImmediateTransaction loaded
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_placement_operations
                    SET state = 'ROLLED_BACK', rolled_back_at = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, rolledBackAt.toString())
                    statement.setString(2, operationId.toString())
                    if (statement.executeUpdate() != 1) {
                        throw SQLException("The tower placement rollback affected no rows")
                    }
                }
                val placement = loaded.orElseThrow()
                Optional.of(
                    TowerPlacement(
                        placement.operationId(),
                        placement.towerId(),
                        placement.actorId(),
                        placement.teamId(),
                        placement.worldId(),
                        placement.blockX(),
                        placement.blockY(),
                        placement.blockZ(),
                        placement.type(),
                        placement.individualLevel(),
                        placement.targetPriority(),
                        TowerPlacementState.ROLLED_BACK,
                        placement.preparedAt(),
                        null,
                        rolledBackAt,
                    ),
                )
            }
        } catch (exception: SQLException) {
            throw failure("roll back a prepared tower placement", exception)
        }
    }

    private fun requireTowerCapacity(
        connection: Connection,
        teamId: UUID,
        settings: TowerSettings,
    ) {
        val progress = loadTeamProgress(connection, teamId).orElseThrow {
            PersistenceConflictException("Team $teamId has no progression row")
        }
        val count = countTowers(connection, teamId)
        if (count >= settings.limitFor(progress.highestClearedLevel)) {
            throw PersistenceConflictException(
                "The team's tower limit has been reached ($count)",
            )
        }
    }

    private fun requireTowerResearch(
        connection: Connection,
        teamId: UUID,
        towerType: TowerType,
        individualLevel: Int,
    ) {
        val research = requireTowerResearch(connection, teamId, towerType)
        if (individualLevel > research.researchLevel) {
            throw PersistenceConflictException(
                "The team's ${towerType.id()} research only permits tower level ${research.researchLevel}",
            )
        }
    }

    private fun countTowers(connection: Connection, teamId: UUID): Int {
        connection.prepareStatement("SELECT COUNT(*) FROM towers WHERE team_id = ?").use { statement ->
            statement.setString(1, teamId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return 0
                }
                return Math.toIntExact(resultSet.getLong(1))
            }
        }
    }

    private fun towerAt(
        connection: Connection,
        worldId: UUID,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
    ): Optional<TowerRecord> {
        connection.prepareStatement(
            """
            SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                   tower_type, individual_level, target_priority,
                   current_hp, max_hp, entity_id, created_at, updated_at
            FROM towers
            WHERE world_id = ? AND block_x = ? AND block_y = ? AND block_z = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, worldId.toString())
            statement.setInt(2, blockX)
            statement.setInt(3, blockY)
            statement.setInt(4, blockZ)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(towerFromRow(resultSet))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun loadTower(connection: Connection, towerId: UUID): Optional<TowerRecord> {
        connection.prepareStatement(
            """
            SELECT tower_id, team_id, world_id, block_x, block_y, block_z,
                   tower_type, individual_level, target_priority,
                   current_hp, max_hp, entity_id, created_at, updated_at
            FROM towers WHERE tower_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, towerId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(towerFromRow(resultSet))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun loadRemovals(
        connection: Connection,
        state: String,
    ): List<TowerRemoval> {
        val removals = ArrayList<TowerRemoval>()
        connection.prepareStatement(
            """
            SELECT operation_id, tower_id, actor_id, team_id, world_id,
                   block_x, block_y, block_z, tower_type, individual_level,
                   entity_id, state, prepared_at, applied_at, rolled_back_at
            FROM tower_removal_operations
            WHERE state = ?
            ORDER BY prepared_at, operation_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, state)
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    removals.add(removalFromRow(resultSet))
                }
            }
        }
        return java.util.List.copyOf(removals)
    }

    private fun loadRemoval(
        connection: Connection,
        operationId: UUID,
    ): Optional<TowerRemoval> {
        connection.prepareStatement(
            """
            SELECT operation_id, tower_id, actor_id, team_id, world_id,
                   block_x, block_y, block_z, tower_type, individual_level,
                   entity_id, state, prepared_at, applied_at, rolled_back_at
            FROM tower_removal_operations WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(removalFromRow(resultSet))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun loadPlacement(
        connection: Connection,
        operationId: UUID,
    ): Optional<TowerPlacement> {
        connection.prepareStatement(
            """
            SELECT operation_id, tower_id, actor_id, team_id, world_id,
                   block_x, block_y, block_z, tower_type, individual_level,
                   target_priority,
                   state, prepared_at, applied_at, rolled_back_at
            FROM tower_placement_operations WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(placementFromRow(resultSet))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun loadTowerUpgrade(
        connection: Connection,
        operationId: UUID,
    ): Optional<TowerUpgrade> {
        connection.prepareStatement(
            """
            SELECT operation_id, tower_id, actor_id, team_id, from_level, to_level,
                   defense_shard_cost, enhancement_core_cost, payload_fingerprint,
                   payment_mode, state, prepared_at, applied_at, rolled_back_at
            FROM tower_upgrade_operations WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(towerUpgradeFromRow(resultSet))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun towerUpgradeFromRow(resultSet: ResultSet): TowerUpgrade = TowerUpgrade(
        uuid(resultSet.getString("operation_id")),
        uuid(resultSet.getString("tower_id")),
        uuid(resultSet.getString("actor_id")),
        uuid(resultSet.getString("team_id")),
        resultSet.getInt("from_level"),
        resultSet.getInt("to_level"),
        resultSet.getInt("defense_shard_cost"),
        resultSet.getInt("enhancement_core_cost"),
        resultSet.getString("payload_fingerprint"),
        TowerUpgradeState.valueOf(resultSet.getString("state")),
        instant(resultSet.getString("prepared_at")),
        nullableInstant(resultSet.getString("applied_at")),
        nullableInstant(resultSet.getString("rolled_back_at")),
        PaymentMode.valueOf(resultSet.getString("payment_mode")),
    )

    private fun loadTowerUpgradeReceipts(
        connection: Connection,
        operationId: UUID,
    ): List<TowerUpgradeReceipt> {
        val receipts = ArrayList<TowerUpgradeReceipt>()
        connection.prepareStatement(
            """
            SELECT operation_id, player_id, material, quantity, state, reserved_at, resolved_at
            FROM tower_upgrade_receipts
            WHERE operation_id = ?
            ORDER BY material
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    receipts.add(
                        TowerUpgradeReceipt(
                            uuid(resultSet.getString("operation_id")),
                            uuid(resultSet.getString("player_id")),
                            resultSet.getString("material"),
                            resultSet.getLong("quantity"),
                            TowerUpgradeReceiptState.valueOf(resultSet.getString("state")),
                            instant(resultSet.getString("reserved_at")),
                            nullableInstant(resultSet.getString("resolved_at")),
                        ),
                    )
                }
            }
        }
        return java.util.List.copyOf(receipts)
    }

    private fun insertTowerUpgradeReceipt(
        connection: Connection,
        receipt: TowerUpgradeReceipt,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO tower_upgrade_receipts(
                operation_id, player_id, material, quantity, state, reserved_at)
            VALUES (?, ?, ?, ?, 'RESERVED', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, receipt.operationId().toString())
            statement.setString(2, receipt.playerId().toString())
            statement.setString(3, receipt.material())
            statement.setLong(4, receipt.quantity())
            statement.setString(5, receipt.reservedAt().toString())
            statement.executeUpdate()
        }
    }

    private fun requireMatchingTowerUpgradeReceipts(
        receipts: List<TowerUpgradeReceipt>,
        upgrade: TowerUpgrade,
        playerId: UUID,
    ) {
        if (receipts.size != 2
            || receipts.any { it.playerId() != playerId }
            || receipts.none {
                it.material() == "DEFENSE_SHARD"
                    && it.quantity() == upgrade.defenseShardCost().toLong()
            }
            || receipts.none {
                it.material() == "ENHANCEMENT_CORE"
                    && it.quantity() == upgrade.enhancementCoreCost().toLong()
            }
        ) {
            throw PersistenceConflictException(
                "The tower upgrade receipt UUID is already assigned to another payload",
            )
        }
    }

    private fun requireSecuredReceipt(
        receipts: List<TowerUpgradeReceipt>,
        material: String,
        quantity: Int,
    ) {
        if (receipts.none {
                it.material() == material
                    && it.quantity() == quantity.toLong()
                    && it.state() == TowerUpgradeReceiptState.SECURED
            }
        ) {
            throw PersistenceConflictException(
                "The tower upgrade material receipt is not secured: $material",
            )
        }
    }

    private fun resolveTowerUpgradeReceipts(
        operationId: UUID,
        state: TowerUpgradeReceiptState,
        resolvedAt: Instant,
    ): OperationOutcome {
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(state, "state")
        Objects.requireNonNull(resolvedAt, "resolvedAt")
        if (state != TowerUpgradeReceiptState.CLEARED
            && state != TowerUpgradeReceiptState.RESTORED
        ) {
            throw IllegalArgumentException("receipt state is not terminal")
        }
        return try {
            database.inImmediateTransaction { connection ->
                val receipts = loadTowerUpgradeReceipts(connection, operationId)
                if (receipts.isEmpty()) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                if (receipts.all { it.state() == state }) {
                    return@inImmediateTransaction OperationOutcome.ALREADY_APPLIED
                }
                connection.prepareStatement(
                    """
                    UPDATE tower_upgrade_receipts
                    SET state = ?, resolved_at = ?
                    WHERE operation_id = ? AND state IN (
                        'RESERVED', 'SECURED', 'RETURN_PENDING', 'CLEAR_PENDING')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, state.name)
                    statement.setString(2, resolvedAt.toString())
                    statement.setString(3, operationId.toString())
                    statement.executeUpdate()
                }
                OperationOutcome.APPLIED
            }
        } catch (exception: SQLException) {
            throw failure("resolve tower upgrade receipts", exception)
        }
    }

    private fun insertTowerUpgrade(connection: Connection, upgrade: TowerUpgrade) {
        connection.prepareStatement(
            """
            INSERT INTO tower_upgrade_operations(
                operation_id, tower_id, actor_id, team_id, from_level, to_level,
                defense_shard_cost, enhancement_core_cost, payload_fingerprint,
                payment_mode, state, prepared_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, upgrade.operationId().toString())
            statement.setString(2, upgrade.towerId().toString())
            statement.setString(3, upgrade.actorId().toString())
            statement.setString(4, upgrade.teamId().toString())
            statement.setInt(5, upgrade.fromLevel())
            statement.setInt(6, upgrade.toLevel())
            statement.setInt(7, upgrade.defenseShardCost())
            statement.setInt(8, upgrade.enhancementCoreCost())
            statement.setString(9, upgrade.payloadFingerprint())
            statement.setString(10, upgrade.paymentMode().name)
            statement.setString(11, upgrade.preparedAt().toString())
            statement.executeUpdate()
        }
    }

    private fun withFingerprint(upgrade: TowerUpgrade, fingerprint: String): TowerUpgrade =
        TowerUpgrade(
            upgrade.operationId(),
            upgrade.towerId(),
            upgrade.actorId(),
            upgrade.teamId(),
            upgrade.fromLevel(),
            upgrade.toLevel(),
            upgrade.defenseShardCost(),
            upgrade.enhancementCoreCost(),
            fingerprint,
            upgrade.state(),
            upgrade.preparedAt(),
            upgrade.appliedAt(),
            upgrade.rolledBackAt(),
            upgrade.paymentMode(),
        )

    private fun upgradeFingerprint(upgrade: TowerUpgrade): String = (
        "${upgrade.towerId()}|${upgrade.actorId()}|${upgrade.teamId()}"
            + "|${upgrade.fromLevel()}|${upgrade.toLevel()}"
            + "|${upgrade.defenseShardCost()}|${upgrade.enhancementCoreCost()}"
            + "|${upgrade.paymentMode()}"
        )

    private fun requireMatchingUpgrade(existing: TowerUpgrade, requested: TowerUpgrade) {
        if (existing.towerId() != requested.towerId()
            || existing.actorId() != requested.actorId()
            || existing.teamId() != requested.teamId()
            || existing.fromLevel() != requested.fromLevel()
            || existing.toLevel() != requested.toLevel()
            || existing.defenseShardCost() != requested.defenseShardCost()
            || existing.enhancementCoreCost() != requested.enhancementCoreCost()
            || existing.paymentMode() != requested.paymentMode()
            || existing.payloadFingerprint() != requested.payloadFingerprint()
        ) {
            throw PersistenceConflictException(
                "The tower upgrade operation UUID is already assigned to another payload",
            )
        }
    }

    private fun loadPlacementEntityId(
        connection: Connection,
        operationId: UUID,
    ): Optional<UUID> {
        connection.prepareStatement(
            "SELECT entity_id FROM tower_placement_operations WHERE operation_id = ?",
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next() || resultSet.getString("entity_id") == null) {
                    return Optional.empty()
                }
                return Optional.of(uuid(resultSet.getString("entity_id")))
            }
        }
    }

    private fun loadTeamProgress(
        connection: Connection,
        teamId: UUID,
    ): Optional<TeamProgress> {
        connection.prepareStatement(
            """
            SELECT team_id, highest_cleared_level, unlocked_level, research_points
            FROM team_progress WHERE team_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, teamId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(
                        TeamProgress(
                            uuid(resultSet.getString("team_id")),
                            resultSet.getLong("highest_cleared_level"),
                            resultSet.getLong("unlocked_level"),
                            resultSet.getLong("research_points"),
                        ),
                    )
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun loadTowerResearch(
        connection: Connection,
        teamId: UUID,
    ): List<TowerResearch> {
        val research = ArrayList<TowerResearch>()
        connection.prepareStatement(
            """
            SELECT team_id, tower_type, research_level, updated_at
            FROM tower_research
            WHERE team_id = ?
            ORDER BY tower_type
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, teamId.toString())
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    research.add(towerResearchFromRow(resultSet))
                }
            }
        }
        return java.util.List.copyOf(research)
    }

    private fun loadTowerResearch(
        connection: Connection,
        teamId: UUID,
        towerType: TowerType,
    ): Optional<TowerResearch> {
        connection.prepareStatement(
            """
            SELECT team_id, tower_type, research_level, updated_at
            FROM tower_research
            WHERE team_id = ? AND tower_type = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, teamId.toString())
            statement.setString(2, towerType.id())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(towerResearchFromRow(resultSet))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun requireTowerResearch(
        connection: Connection,
        teamId: UUID,
        towerType: TowerType,
    ): TowerResearch = loadTowerResearch(connection, teamId, towerType).orElseThrow {
        PersistenceConflictException(
            "Team $teamId has no research row for ${towerType.id()}",
        )
    }

    private fun requireTeamProgress(connection: Connection, teamId: UUID): TeamProgress =
        loadTeamProgress(connection, teamId).orElseThrow {
            PersistenceConflictException("Team $teamId has no progression row")
        }

    private fun loadResearchOperationFingerprint(
        connection: Connection,
        operationId: UUID,
    ): Optional<String> {
        connection.prepareStatement(
            """
            SELECT payload_fingerprint
            FROM tower_research_operations
            WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) {
                    Optional.of(resultSet.getString("payload_fingerprint"))
                } else {
                    Optional.empty()
                }
            }
        }
    }

    private fun updateTeamResearchPoints(
        connection: Connection,
        progress: TeamProgress,
        updatedAt: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE team_progress
            SET research_points = ?, updated_at = ?
            WHERE team_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, progress.researchPoints)
            statement.setString(2, updatedAt.toString())
            statement.setString(3, progress.teamId.toString())
            if (statement.executeUpdate() != 1) {
                throw SQLException("The research point update affected no rows")
            }
        }
    }

    private fun updateTowerResearch(connection: Connection, research: TowerResearch) {
        connection.prepareStatement(
            """
            UPDATE tower_research
            SET research_level = ?, updated_at = ?
            WHERE team_id = ? AND tower_type = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, research.researchLevel)
            statement.setString(2, research.updatedAt.toString())
            statement.setString(3, research.teamId.toString())
            statement.setString(4, research.towerType.id())
            if (statement.executeUpdate() != 1) {
                throw SQLException("The tower research update affected no rows")
            }
        }
    }

    private fun insertResearchOperation(
        connection: Connection,
        operationId: UUID,
        teamId: UUID,
        actorId: UUID,
        towerType: TowerType,
        researchPointCost: Long,
        fingerprint: String,
        appliedAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO tower_research_operations(
                operation_id, team_id, actor_id, tower_type,
                research_point_cost, payload_fingerprint, applied_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.setString(2, teamId.toString())
            statement.setString(3, actorId.toString())
            statement.setString(4, towerType.id())
            statement.setLong(5, researchPointCost)
            statement.setString(6, fingerprint)
            statement.setString(7, appliedAt.toString())
            statement.executeUpdate()
        }
    }

    private fun requireTeamMember(
        connection: Connection,
        teamId: UUID,
        playerId: UUID,
    ) {
        connection.prepareStatement(
            "SELECT 1 FROM team_members WHERE team_id = ? AND player_id = ?",
        ).use { statement ->
            statement.setString(1, teamId.toString())
            statement.setString(2, playerId.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    throw PersistenceConflictException(
                        "Player $playerId is not a member of team $teamId",
                    )
                }
            }
        }
    }

    private fun requireTowerPlacementWindow(
        connection: Connection,
        teamId: UUID,
        operation: String,
    ) {
        connection.prepareStatement(
            """
            SELECT e.team_id, e.state
            FROM event_lock lock
            JOIN defense_events e ON e.event_id = lock.event_id
            WHERE lock.singleton = 1
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    val phase = DefensePhase.valueOf(resultSet.getString("state"))
                    val eventTeamId = uuid(resultSet.getString("team_id"))
                    if (eventTeamId != teamId
                        || (phase != DefensePhase.PREPARATION
                            && phase != DefensePhase.INTERMISSION)
                    ) {
                        throw PersistenceConflictException(
                            "Cannot $operation outside the team's PREPARATION or INTERMISSION window",
                        )
                    }
                }
            }
        }
    }

    private fun insertPlacement(connection: Connection, placement: TowerPlacement) {
        connection.prepareStatement(
            """
            INSERT INTO tower_placement_operations(
                operation_id, tower_id, actor_id, team_id, world_id,
                block_x, block_y, block_z, tower_type, individual_level,
                target_priority, state, prepared_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, placement.operationId().toString())
            statement.setString(2, placement.towerId().toString())
            statement.setString(3, placement.actorId().toString())
            statement.setString(4, placement.teamId().toString())
            statement.setString(5, placement.worldId().toString())
            statement.setInt(6, placement.blockX())
            statement.setInt(7, placement.blockY())
            statement.setInt(8, placement.blockZ())
            statement.setString(9, placement.type().id())
            statement.setInt(10, placement.individualLevel())
            statement.setString(11, placement.targetPriority().id())
            statement.setString(12, placement.preparedAt().toString())
            statement.executeUpdate()
        }
    }

    private fun insertTower(connection: Connection, tower: TowerRecord) {
        connection.prepareStatement(
            """
            INSERT INTO towers(
                tower_id, team_id, world_id, block_x, block_y, block_z,
                tower_type, individual_level, target_priority,
                current_hp, max_hp, entity_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tower.id().toString())
            statement.setString(2, tower.teamId().toString())
            statement.setString(3, tower.worldId().toString())
            statement.setInt(4, tower.blockX())
            statement.setInt(5, tower.blockY())
            statement.setInt(6, tower.blockZ())
            statement.setString(7, tower.type().id())
            statement.setInt(8, tower.individualLevel())
            statement.setString(9, tower.targetPriority().id())
            statement.setLong(10, tower.currentHitPoints())
            statement.setLong(11, tower.maximumHitPoints())
            statement.setString(12, tower.entityId().toString())
            statement.setString(13, tower.createdAt().toString())
            statement.setString(14, tower.updatedAt().toString())
            statement.executeUpdate()
        }
    }

    private fun insertRemoval(connection: Connection, removal: TowerRemoval) {
        connection.prepareStatement(
            """
            INSERT INTO tower_removal_operations(
                operation_id, tower_id, actor_id, team_id, world_id,
                block_x, block_y, block_z, tower_type, individual_level,
                entity_id, state, prepared_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, removal.operationId().toString())
            statement.setString(2, removal.towerId().toString())
            statement.setString(3, removal.actorId().toString())
            statement.setString(4, removal.teamId().toString())
            statement.setString(5, removal.worldId().toString())
            statement.setInt(6, removal.blockX())
            statement.setInt(7, removal.blockY())
            statement.setInt(8, removal.blockZ())
            statement.setString(9, removal.type().id())
            statement.setInt(10, removal.individualLevel())
            statement.setString(11, removal.entityId().toString())
            statement.setString(12, removal.preparedAt().toString())
            statement.executeUpdate()
        }
    }

    private fun towerFromRow(resultSet: ResultSet): TowerRecord = TowerRecord(
        uuid(resultSet.getString("tower_id")),
        uuid(resultSet.getString("team_id")),
        uuid(resultSet.getString("world_id")),
        resultSet.getInt("block_x"),
        resultSet.getInt("block_y"),
        resultSet.getInt("block_z"),
        TowerType.fromId(resultSet.getString("tower_type")),
        resultSet.getInt("individual_level"),
        TowerTargetPriority.fromId(resultSet.getString("target_priority")),
        resultSet.getLong("current_hp"),
        resultSet.getLong("max_hp"),
        uuid(resultSet.getString("entity_id")),
        instant(resultSet.getString("created_at")),
        instant(resultSet.getString("updated_at")),
    )

    private fun towerResearchFromRow(resultSet: ResultSet): TowerResearch = TowerResearch(
        uuid(resultSet.getString("team_id")),
        TowerType.fromId(resultSet.getString("tower_type")),
        resultSet.getInt("research_level"),
        instant(resultSet.getString("updated_at")),
    )

    private fun placementFromRow(resultSet: ResultSet): TowerPlacement = TowerPlacement(
        uuid(resultSet.getString("operation_id")),
        uuid(resultSet.getString("tower_id")),
        uuid(resultSet.getString("actor_id")),
        uuid(resultSet.getString("team_id")),
        uuid(resultSet.getString("world_id")),
        resultSet.getInt("block_x"),
        resultSet.getInt("block_y"),
        resultSet.getInt("block_z"),
        TowerType.fromId(resultSet.getString("tower_type")),
        resultSet.getInt("individual_level"),
        TowerTargetPriority.fromId(resultSet.getString("target_priority")),
        TowerPlacementState.valueOf(resultSet.getString("state")),
        instant(resultSet.getString("prepared_at")),
        nullableInstant(resultSet.getString("applied_at")),
        nullableInstant(resultSet.getString("rolled_back_at")),
    )

    private fun removalFromRow(resultSet: ResultSet): TowerRemoval = TowerRemoval(
        uuid(resultSet.getString("operation_id")),
        uuid(resultSet.getString("tower_id")),
        uuid(resultSet.getString("actor_id")),
        uuid(resultSet.getString("team_id")),
        uuid(resultSet.getString("world_id")),
        resultSet.getInt("block_x"),
        resultSet.getInt("block_y"),
        resultSet.getInt("block_z"),
        TowerType.fromId(resultSet.getString("tower_type")),
        resultSet.getInt("individual_level"),
        uuid(resultSet.getString("entity_id")),
        TowerRemovalState.valueOf(resultSet.getString("state")),
        instant(resultSet.getString("prepared_at")),
        nullableInstant(resultSet.getString("applied_at")),
        nullableInstant(resultSet.getString("rolled_back_at")),
    )

    private fun requireMatchingRemoval(existing: TowerRemoval, requested: TowerRemoval) {
        if (existing.towerId() != requested.towerId()
            || existing.actorId() != requested.actorId()
            || existing.teamId() != requested.teamId()
            || existing.worldId() != requested.worldId()
            || existing.blockX() != requested.blockX()
            || existing.blockY() != requested.blockY()
            || existing.blockZ() != requested.blockZ()
            || existing.type() != requested.type()
            || existing.individualLevel() != requested.individualLevel()
            || existing.entityId() != requested.entityId()
        ) {
            throw PersistenceConflictException(
                "The tower removal operation UUID is already assigned to another payload",
            )
        }
    }

    private fun requireMatchingTower(tower: TowerRecord, removal: TowerRemoval) {
        if (tower.id() != removal.towerId()
            || tower.teamId() != removal.teamId()
            || tower.worldId() != removal.worldId()
            || tower.blockX() != removal.blockX()
            || tower.blockY() != removal.blockY()
            || tower.blockZ() != removal.blockZ()
            || tower.type() != removal.type()
            || tower.individualLevel() != removal.individualLevel()
            || tower.entityId() != removal.entityId()
        ) {
            throw PersistenceConflictException(
                "The installed tower does not match the removal request",
            )
        }
    }

    private fun requireNoActiveEvent(connection: Connection, operation: String) {
        connection.prepareStatement("SELECT 1 FROM event_lock WHERE singleton = 1").use { statement ->
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    throw PersistenceConflictException(
                        "Cannot $operation while a defense event is active",
                    )
                }
            }
        }
    }

    private fun requireMatchingPlacement(existing: TowerPlacement, requested: TowerPlacement) {
        if (existing.towerId() != requested.towerId()
            || existing.actorId() != requested.actorId()
            || existing.teamId() != requested.teamId()
            || existing.worldId() != requested.worldId()
            || existing.blockX() != requested.blockX()
            || existing.blockY() != requested.blockY()
            || existing.blockZ() != requested.blockZ()
            || existing.type() != requested.type()
            || existing.individualLevel() != requested.individualLevel()
            || existing.targetPriority() != requested.targetPriority()
        ) {
            throw PersistenceConflictException(
                "The tower placement operation UUID is already assigned to another payload",
            )
        }
    }

    private fun <T> read(operation: String, work: Database.SqlWork<T>): T {
        return try {
            database.openConnection().use { connection ->
                work.execute(connection)
            }
        } catch (exception: SQLException) {
            throw failure(operation, exception)
        }
    }

    private fun failure(operation: String, exception: SQLException): PersistenceException =
        PersistenceException("Could not $operation", exception)

    private fun isConstraintViolation(exception: SQLException): Boolean {
        val message = exception.message
        return message != null
            && (message.contains("constraint")
                || message.contains("UNIQUE")
                || message.contains("CHECK"))
    }

    private fun researchFingerprint(
        teamId: UUID,
        actorId: UUID,
        towerType: TowerType,
        researchPointCost: Long,
    ): String = "$teamId|$actorId|${towerType.id()}|$researchPointCost"

    private fun uuid(value: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (invalidUuid: IllegalArgumentException) {
            throw PersistenceException("Invalid UUID in tower persistence", invalidUuid)
        }
    }

    private fun instant(value: String): Instant {
        return try {
            Instant.parse(value)
        } catch (invalidInstant: RuntimeException) {
            throw PersistenceException("Invalid timestamp in tower persistence", invalidInstant)
        }
    }

    private fun nullableInstant(value: String?): Instant? =
        if (value == null) null else instant(value)
}
