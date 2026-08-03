package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.persistence.BlockChange;
import io.github.takenoha.towerdefense.persistence.BlockChangeKind;
import io.github.takenoha.towerdefense.persistence.BlockChangeRepository;
import io.github.takenoha.towerdefense.persistence.BlockChangeStatus;
import io.github.takenoha.towerdefense.persistence.BlockRollbackDecision;
import io.github.takenoha.towerdefense.persistence.BlockRollbackPlanner;
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.PersistenceConflictException;
import io.github.takenoha.towerdefense.persistence.PreparedRollback;
import io.github.takenoha.towerdefense.persistence.StoredBlockChange;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Main-thread Paper adapter for two-phase block changes and conflict-safe recovery. */
public final class PaperBlockMutationAdapter {
    private final BlockChangeRepository ledger;
    private final BlockRollbackPlanner planner;

    public PaperBlockMutationAdapter(BlockChangeRepository ledger) {
        this(ledger, new BlockRollbackPlanner());
    }

    /** Calculates the next durable generation for one block coordinate. */
    public long nextGeneration(UUID eventId, Block block) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(block, "block");
        return ledger.nextGeneration(
                eventId,
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ());
    }

    PaperBlockMutationAdapter(
            BlockChangeRepository ledger,
            BlockRollbackPlanner planner) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /**
     * Persists a before/after row, applies the block, and only then marks the physical operation
     * complete. The caller must keep operation UUIDs stable when retrying in the same process.
     */
    public OperationOutcome apply(
            UUID eventId,
            long generation,
            BlockChangeKind kind,
            Block block,
            BlockStateSnapshot expectedAfter,
            UUID changeId,
            UUID prepareOperationId,
            UUID applyOperationId,
            Instant occurredAt) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(expectedAfter, "expectedAfter");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(prepareOperationId, "prepareOperationId");
        Objects.requireNonNull(applyOperationId, "applyOperationId");
        Objects.requireNonNull(occurredAt, "occurredAt");

        Optional<StoredBlockChange> existing = findChangeIfPresent(eventId, changeId);
        BlockChange requested;
        if (existing.isPresent()) {
            BlockChange durable = existing.orElseThrow().change();
            requested = new BlockChange(
                    eventId,
                    changeId,
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    kind,
                    generation,
                    durable.beforeBlockData(),
                    durable.beforeBlockState(),
                    expectedAfter.blockData(),
                    expectedAfter.blockState());
        } else {
            BlockStateSnapshot before = PaperBlockStateCodec.captureBefore(block);
            requested = new BlockChange(
                    eventId,
                    changeId,
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    kind,
                    generation,
                    before.blockData(),
                    before.blockState(),
                    expectedAfter.blockData(),
                    expectedAfter.blockState());
        }
        ledger.prepare(requested, prepareOperationId, occurredAt);
        StoredBlockChange durable = findChange(eventId, changeId);
        if (durable.status() == BlockChangeStatus.CONFLICT) {
            throw new PersistenceConflictException(
                    "A block change was already recorded as a recovery conflict");
        }
        if (durable.status() == BlockChangeStatus.ROLLED_BACK) {
            BlockStateSnapshot durableBeforeState = new BlockStateSnapshot(
                    durable.change().beforeBlockData(),
                    durable.change().beforeBlockState());
            if (!PaperBlockStateCodec.captureComparable(block).equals(durableBeforeState)) {
                throw new PersistenceConflictException(
                        "A rolled-back block change no longer matches its before-state");
            }
            return OperationOutcome.ALREADY_APPLIED;
        }

        BlockStateSnapshot current = PaperBlockStateCodec.captureComparable(block);
        BlockStateSnapshot expected = new BlockStateSnapshot(
                durable.change().expectedAfterBlockData(),
                durable.change().expectedAfterBlockState());
        BlockStateSnapshot durableBefore = new BlockStateSnapshot(
                durable.change().beforeBlockData(),
                durable.change().beforeBlockState());
        if (!current.equals(durableBefore) && !current.equals(expected)) {
            throw new PersistenceConflictException(
                    "The live block changed after the event ledger was prepared");
        }
        if (durable.status() == BlockChangeStatus.APPLIED) {
            if (!current.equals(expected)) {
                throw new PersistenceConflictException(
                        "An applied block change is missing its expected after-state");
            }
            return OperationOutcome.ALREADY_APPLIED;
        }
        ledger.prepareApply(eventId, changeId, applyOperationId, occurredAt);
        if (current.equals(durableBefore)) {
            PaperBlockStateCodec.applyBlockData(block, expected.blockData());
            if (!PaperBlockStateCodec.captureComparable(block).equals(expected)) {
                throw new PersistenceConflictException(
                        "The Paper block did not reach its expected after-state");
            }
        }
        return ledger.apply(eventId, changeId, applyOperationId, occurredAt);
    }

    /**
     * Resolves every outstanding mutation in reverse generation order. A player edit becomes a
     * durable conflict and is never overwritten. A prepared rollback reuses its saved decision.
     */
    public void recoverEvent(UUID eventId, Instant recoveredAt) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        List<StoredBlockChange> changes = ledger.loadUnresolvedChanges(eventId);
        for (StoredBlockChange change : changes) {
            rollbackOne(eventId, change, recoveredAt);
        }
    }

    /**
     * Settles a normal terminal event without restoring enemy destruction. Temporary blocks are
     * removed in reverse generation order, while event-owned destruction rows are only marked
     * settled after their physical apply acknowledgement exists.
     */
    public void settleEvent(UUID eventId, DefensePhase terminalPhase, Instant settledAt) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(terminalPhase, "terminalPhase");
        Objects.requireNonNull(settledAt, "settledAt");
        if (terminalPhase != DefensePhase.VICTORY
                && terminalPhase != DefensePhase.DEFEAT
                && terminalPhase != DefensePhase.ABORTED) {
            throw new IllegalArgumentException("settleEvent requires a normal terminal phase");
        }
        for (StoredBlockChange change : ledger.loadUnresolvedChanges(eventId)) {
            if (change.change().kind() == BlockChangeKind.TEMPORARY_BLOCK) {
                rollbackOne(eventId, change, settledAt);
            } else {
                validateEventBlock(change);
            }
        }
    }

    private static void validateEventBlock(StoredBlockChange change) {
        if (change.status() != BlockChangeStatus.APPLIED) {
            throw new PersistenceConflictException(
                    "An event-owned destruction has not reached the applied ledger state: "
                            + change.change().changeId());
        }
        // The SETTLED row is committed atomically with DefenseRepository.finishEvent. Until then,
        // a crash must leave this APPLIED row visible to technical recovery.
    }

    private void rollbackOne(
            UUID eventId,
            StoredBlockChange change,
            Instant recoveredAt) {
        Block block = loadBlock(change);
        Optional<PreparedRollback> prepared = ledger.loadPreparedRollback(
                eventId, change.change().changeId());
        BlockRollbackDecision decision;
        UUID operationId;
        if (prepared.isPresent()) {
            PreparedRollback value = prepared.orElseThrow();
            decision = value.decision();
            operationId = value.operationId();
        } else {
            decision = planner.decide(change, PaperBlockStateCodec.captureComparable(block));
            operationId = deterministicRollbackOperation(eventId, change.change().changeId());
            ledger.prepareRollback(
                    eventId,
                    change.change().changeId(),
                    operationId,
                    decision,
                    recoveredAt);
        }

        if (decision == BlockRollbackDecision.RESTORE) {
            BlockStateSnapshot current = PaperBlockStateCodec.captureComparable(block);
            BlockStateSnapshot expected = new BlockStateSnapshot(
                    change.change().expectedAfterBlockData(),
                    change.change().expectedAfterBlockState());
            BlockStateSnapshot before = new BlockStateSnapshot(
                    change.change().beforeBlockData(),
                    change.change().beforeBlockState());
            if (current.equals(expected)) {
                PaperBlockStateCodec.applyBlockData(block, before.blockData());
                if (!PaperBlockStateCodec.captureComparable(block).equals(before)) {
                    throw new PersistenceConflictException(
                            "The Paper block did not reach its rollback before-state");
                }
            } else if (!current.equals(before)) {
                throw new PersistenceConflictException(
                        "The live block changed while a rollback was pending");
            }
        } else if (decision == BlockRollbackDecision.SKIP_ALREADY_BEFORE
                && !PaperBlockStateCodec.captureComparable(block).equals(
                        new BlockStateSnapshot(
                                change.change().beforeBlockData(),
                                change.change().beforeBlockState()))) {
            throw new PersistenceConflictException(
                    "A skip rollback no longer matches the before-state");
        }
        ledger.applyRollback(
                eventId,
                change.change().changeId(),
                operationId,
                decision,
                recoveredAt);
    }

    private StoredBlockChange findChange(UUID eventId, UUID changeId) {
        return findChangeIfPresent(eventId, changeId)
                .orElseThrow(() -> new PersistenceConflictException(
                        "The prepared block change disappeared: " + changeId));
    }

    private Optional<StoredBlockChange> findChangeIfPresent(UUID eventId, UUID changeId) {
        return ledger.loadChanges(eventId).stream()
                .filter(value -> value.change().changeId().equals(changeId))
                .findFirst();
    }

    private static Block loadBlock(StoredBlockChange change) {
        World world = Bukkit.getWorld(change.change().worldId());
        if (world == null) {
            throw new PersistenceConflictException(
                    "The world for block recovery is not loaded: " + change.change().worldId());
        }
        return world.getBlockAt(
                change.change().blockX(),
                change.change().blockY(),
                change.change().blockZ());
    }

    private static UUID deterministicRollbackOperation(UUID eventId, UUID changeId) {
        return UUID.nameUUIDFromBytes(("tower-defense:block-rollback:" + eventId + ":" + changeId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Paper block mutation must run on the main thread");
        }
    }
}
