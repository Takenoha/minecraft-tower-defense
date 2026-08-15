package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.DefensePhase
import io.github.takenoha.towerdefense.persistence.BlockChange
import io.github.takenoha.towerdefense.persistence.BlockChangeKind
import io.github.takenoha.towerdefense.persistence.BlockChangeRepository
import io.github.takenoha.towerdefense.persistence.BlockChangeStatus
import io.github.takenoha.towerdefense.persistence.BlockRollbackDecision
import io.github.takenoha.towerdefense.persistence.BlockRollbackPlanner
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot
import io.github.takenoha.towerdefense.persistence.OperationOutcome
import io.github.takenoha.towerdefense.persistence.PersistenceConflictException
import io.github.takenoha.towerdefense.persistence.PreparedRollback
import io.github.takenoha.towerdefense.persistence.StoredBlockChange
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block

/** Main-thread Paper adapter for two-phase block changes and conflict-safe recovery. */
class PaperBlockMutationAdapter(
    ledger: BlockChangeRepository,
    planner: BlockRollbackPlanner,
) {
    constructor(ledger: BlockChangeRepository) : this(ledger, BlockRollbackPlanner())

    private val ledgerValue = Objects.requireNonNull(ledger, "ledger")
    private val plannerValue = Objects.requireNonNull(planner, "planner")

    /** Calculates the next durable generation for one block coordinate. */
    fun nextGeneration(eventId: UUID, block: Block): Long {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(block, "block")
        return ledgerValue.nextGeneration(
            eventId,
            block.world.uid,
            block.x,
            block.y,
            block.z,
        )
    }

    /** Returns the durable count used by the per-event temporary bridge cap. */
    fun countUnresolvedTemporaryBlocks(eventId: UUID): Long {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        return ledgerValue.countUnresolvedTemporaryBlocks(eventId)
    }

    /**
     * Persists a before/after row, applies the block, and only then marks the physical operation
     * complete. The caller must keep operation UUIDs stable when retrying in the same process.
     */
    fun apply(
        eventId: UUID,
        generation: Long,
        kind: BlockChangeKind,
        block: Block,
        expectedAfter: BlockStateSnapshot,
        changeId: UUID,
        prepareOperationId: UUID,
        applyOperationId: UUID,
        occurredAt: Instant,
    ): OperationOutcome {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(kind, "kind")
        Objects.requireNonNull(block, "block")
        Objects.requireNonNull(expectedAfter, "expectedAfter")
        Objects.requireNonNull(changeId, "changeId")
        Objects.requireNonNull(prepareOperationId, "prepareOperationId")
        Objects.requireNonNull(applyOperationId, "applyOperationId")
        Objects.requireNonNull(occurredAt, "occurredAt")

        val existing = findChangeIfPresent(eventId, changeId)
        val requested: BlockChange
        if (existing.isPresent) {
            val durable = existing.orElseThrow().change
            requested = BlockChange(
                eventId,
                changeId,
                block.world.uid,
                block.x,
                block.y,
                block.z,
                kind,
                durable.generation(),
                durable.beforeBlockData(),
                durable.beforeBlockState(),
                durable.beforeTileNbt(),
                expectedAfter.blockData,
                expectedAfter.blockState,
                expectedAfter.tileNbt,
            )
        } else {
            val before = PaperBlockStateCodec.captureBefore(block)
            requested = BlockChange(
                eventId,
                changeId,
                block.world.uid,
                block.x,
                block.y,
                block.z,
                kind,
                generation,
                before.blockData,
                before.blockState,
                before.tileNbt,
                expectedAfter.blockData,
                expectedAfter.blockState,
                expectedAfter.tileNbt,
            )
        }
        ledgerValue.prepare(requested, prepareOperationId, occurredAt)
        val durable = findChange(eventId, changeId)
        if (durable.status == BlockChangeStatus.CONFLICT) {
            throw PersistenceConflictException(
                "A block change was already recorded as a recovery conflict",
            )
        }
        if (durable.status == BlockChangeStatus.ROLLED_BACK) {
            val durableBeforeState = BlockStateSnapshot(
                durable.change.beforeBlockData(),
                durable.change.beforeBlockState(),
                durable.change.beforeTileNbt(),
            )
            if (PaperBlockStateCodec.captureComparable(block) != durableBeforeState) {
                throw PersistenceConflictException(
                    "A rolled-back block change no longer matches its before-state",
                )
            }
            return OperationOutcome.ALREADY_APPLIED
        }

        val current = PaperBlockStateCodec.captureComparable(block)
        val expected = BlockStateSnapshot(
            durable.change.expectedAfterBlockData(),
            durable.change.expectedAfterBlockState(),
            durable.change.expectedAfterTileNbt(),
        )
        val durableBefore = BlockStateSnapshot(
            durable.change.beforeBlockData(),
            durable.change.beforeBlockState(),
            durable.change.beforeTileNbt(),
        )
        if (current != durableBefore && current != expected) {
            throw PersistenceConflictException(
                "The live block changed after the event ledger was prepared",
            )
        }
        if (durable.status == BlockChangeStatus.APPLIED) {
            if (current != expected) {
                throw PersistenceConflictException(
                    "An applied block change is missing its expected after-state",
                )
            }
            return OperationOutcome.ALREADY_APPLIED
        }
        ledgerValue.prepareApply(eventId, changeId, applyOperationId, occurredAt)
        if (current == durableBefore) {
            PaperBlockStateCodec.applySnapshot(block, expected)
            if (PaperBlockStateCodec.captureComparable(block) != expected) {
                throw PersistenceConflictException(
                    "The Paper block did not reach its expected after-state",
                )
            }
        }
        return ledgerValue.apply(eventId, changeId, applyOperationId, occurredAt)
    }

    /**
     * Resolves every outstanding mutation in reverse generation order. A player edit becomes a
     * durable conflict and is never overwritten. A prepared rollback reuses its saved decision.
     */
    fun recoverEvent(eventId: UUID, recoveredAt: Instant) {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(recoveredAt, "recoveredAt")
        val changes = ledgerValue.loadUnresolvedChanges(eventId)
        for (change in changes) {
            rollbackOne(eventId, change, recoveredAt)
        }
    }

    /**
     * Settles a normal terminal event without restoring enemy destruction. Temporary blocks are
     * removed in reverse generation order, while event-owned destruction rows are only marked
     * settled after their physical apply acknowledgement exists.
     */
    fun settleEvent(eventId: UUID, terminalPhase: DefensePhase, settledAt: Instant) {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(terminalPhase, "terminalPhase")
        Objects.requireNonNull(settledAt, "settledAt")
        if (terminalPhase != DefensePhase.VICTORY &&
            terminalPhase != DefensePhase.DEFEAT &&
            terminalPhase != DefensePhase.ABORTED
        ) {
            throw IllegalArgumentException("settleEvent requires a normal terminal phase")
        }
        for (change in ledgerValue.loadUnresolvedChanges(eventId)) {
            if (change.change.kind() == BlockChangeKind.TEMPORARY_BLOCK) {
                rollbackOne(eventId, change, settledAt)
            } else {
                validateEventBlock(change)
            }
        }
    }

    private fun rollbackOne(
        eventId: UUID,
        change: StoredBlockChange,
        recoveredAt: Instant,
    ) {
        val block = loadBlock(change)
        val prepared = ledgerValue.loadPreparedRollback(
            eventId,
            change.change.changeId(),
        )
        val decision: BlockRollbackDecision
        val operationId: UUID
        if (prepared.isPresent) {
            val value: PreparedRollback = prepared.orElseThrow()
            decision = value.decision
            operationId = value.operationId
        } else {
            decision = plannerValue.decide(change, PaperBlockStateCodec.captureComparable(block))
            operationId = deterministicRollbackOperation(eventId, change.change.changeId())
            ledgerValue.prepareRollback(
                eventId,
                change.change.changeId(),
                operationId,
                decision,
                recoveredAt,
            )
        }

        if (decision == BlockRollbackDecision.RESTORE) {
            val current = PaperBlockStateCodec.captureComparable(block)
            val expected = BlockStateSnapshot(
                change.change.expectedAfterBlockData(),
                change.change.expectedAfterBlockState(),
                change.change.expectedAfterTileNbt(),
            )
            val before = BlockStateSnapshot(
                change.change.beforeBlockData(),
                change.change.beforeBlockState(),
                change.change.beforeTileNbt(),
            )
            if (current == expected) {
                PaperBlockStateCodec.applySnapshot(block, before)
                if (PaperBlockStateCodec.captureComparable(block) != before) {
                    throw PersistenceConflictException(
                        "The Paper block did not reach its rollback before-state",
                    )
                }
            } else if (current != before) {
                throw PersistenceConflictException(
                    "The live block changed while a rollback was pending",
                )
            }
        } else if (decision == BlockRollbackDecision.SKIP_ALREADY_BEFORE &&
            PaperBlockStateCodec.captureComparable(block) != BlockStateSnapshot(
                change.change.beforeBlockData(),
                change.change.beforeBlockState(),
                change.change.beforeTileNbt(),
            )
        ) {
            throw PersistenceConflictException(
                "A skip rollback no longer matches the before-state",
            )
        }
        ledgerValue.applyRollback(
            eventId,
            change.change.changeId(),
            operationId,
            decision,
            recoveredAt,
        )
    }

    private fun findChange(eventId: UUID, changeId: UUID): StoredBlockChange =
        findChangeIfPresent(eventId, changeId).orElseThrow {
            PersistenceConflictException("The prepared block change disappeared: $changeId")
        }

    private fun findChangeIfPresent(
        eventId: UUID,
        changeId: UUID,
    ): Optional<StoredBlockChange> =
        ledgerValue.loadChanges(eventId).stream()
            .filter { value -> value.change.changeId() == changeId }
            .findFirst()

    private companion object {
        @JvmStatic
        private fun validateEventBlock(change: StoredBlockChange) {
            if (change.status != BlockChangeStatus.APPLIED) {
                throw PersistenceConflictException(
                    "An event-owned destruction has not reached the applied ledger state: " +
                        change.change.changeId(),
                )
            }
            // The SETTLED row is committed atomically with DefenseRepository.finishEvent. Until
            // then, a crash must leave this APPLIED row visible to technical recovery.
        }

        @JvmStatic
        private fun loadBlock(change: StoredBlockChange): Block {
            val world: World = Bukkit.getWorld(change.change.worldId())
                ?: throw PersistenceConflictException(
                    "The world for block recovery is not loaded: ${change.change.worldId()}",
                )
            return world.getBlockAt(
                change.change.blockX(),
                change.change.blockY(),
                change.change.blockZ(),
            )
        }

        @JvmStatic
        private fun deterministicRollbackOperation(eventId: UUID, changeId: UUID): UUID =
            UUID.nameUUIDFromBytes(
                ("tower-defense:block-rollback:$eventId:$changeId")
                    .toByteArray(StandardCharsets.UTF_8),
            )

        @JvmStatic
        private fun requireMainThread() {
            if (!Bukkit.isPrimaryThread()) {
                throw IllegalStateException("Paper block mutation must run on the main thread")
            }
        }
    }
}
