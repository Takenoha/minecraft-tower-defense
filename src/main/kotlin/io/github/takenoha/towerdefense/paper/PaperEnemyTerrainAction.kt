package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts
import io.github.takenoha.towerdefense.domain.EnemyRole
import io.github.takenoha.towerdefense.domain.EnemyTerrainActionKind
import io.github.takenoha.towerdefense.persistence.BlockChangeKind
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot
import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy
import io.github.takenoha.towerdefense.runtime.EnemyBridgePlanner
import io.github.takenoha.towerdefense.runtime.TaggedEnemy
import io.github.takenoha.towerdefense.runtime.TerrainMutationDecision
import io.github.takenoha.towerdefense.runtime.TerrainMutationInput
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.inventory.InventoryHolder

/** Applies one guarded enemy block action through the PR4 write-ahead adapter. */
class PaperEnemyTerrainAction(
    policy: TerrainMutationPolicy,
    blockMutations: PaperBlockMutationAdapter,
    escrowDrops: PaperEscrowDropManager,
    cores: CoreRegistry,
    accessPolicy: EnemyAccessPolicy,
) {
    private val policy: TerrainMutationPolicy = Objects.requireNonNull(policy, "policy")
    private val blockMutations: PaperBlockMutationAdapter =
        Objects.requireNonNull(blockMutations, "blockMutations")
    private val escrowDrops: PaperEscrowDropManager =
        Objects.requireNonNull(escrowDrops, "escrowDrops")
    private val cores: CoreRegistry = Objects.requireNonNull(cores, "cores")
    private val accessPolicy: EnemyAccessPolicy =
        Objects.requireNonNull(accessPolicy, "accessPolicy")

    /**
     * Handles an EntityChangeBlockEvent that belongs to an event enemy.
     *
     * The caller remains responsible for cancelling every tagged enemy event. This method only
     * applies an action when the experimental policy, live event identity, area, and block policy
     * all allow it. The production plugin currently constructs this policy disabled.
     */
    fun tryApply(event: EntityChangeBlockEvent, taggedEnemy: TaggedEnemy): Boolean {
        requireMainThread()
        Objects.requireNonNull(event, "event")
        Objects.requireNonNull(taggedEnemy, "taggedEnemy")
        if (!policy.enabled()) {
            return false
        }
        val entity = event.getEntity()
        if (!accessPolicy.mayRemain(taggedEnemy, entity.getUniqueId())) {
            return false
        }
        val block = event.getBlock()
        if (!accessPolicy.isCombatAreaProtected(block.getLocation())) {
            return false
        }
        val action = if (event.getTo().isAir) {
            EnemyTerrainActionKind.BREAK
        } else {
            EnemyTerrainActionKind.BUILD
        }
        val obstacle = PaperEnemyObstacleClassifier.classify(
            block,
            Bukkit.createBlockData(event.getTo()),
            cores,
            accessPolicy,
        )
        if (!obstacle.permits(action)) {
            return false
        }
        val state: BlockState = block.getState()
        val input = TerrainMutationInput(
            block.getType().getKey().toString(),
            state is InventoryHolder,
            cores.isCore(block),
            state is org.bukkit.block.TileState,
            event.getTo().getKey().toString(),
        )
        if (policy.decide(taggedEnemy.role, action, false, input) != TerrainMutationDecision.ALLOW) {
            return false
        }

        val expectedAfter = PaperBlockStateCodec.snapshotForBlockData(
            Bukkit.createBlockData(event.getTo()).getAsString(),
        )
        if (PaperBlockStateCodec.captureComparable(block).equals(expectedAfter)) {
            return false
        }
        val kind = if (event.getTo().isAir) {
            BlockChangeKind.EVENT_BLOCK
        } else {
            BlockChangeKind.TEMPORARY_BLOCK
        }
        if (kind == BlockChangeKind.TEMPORARY_BLOCK &&
            blockMutations.countUnresolvedTemporaryBlocks(taggedEnemy.eventId) >=
            EnemyBridgePlanner.MAX_ACTIVE_TEMPORARY_BLOCKS
        ) {
            return false
        }
        val generation = blockMutations.nextGeneration(taggedEnemy.eventId, block)
        val actionKey = block.getWorld().getUID().toString() +
            "|" + block.getX() +
            "|" + block.getY() +
            "|" + block.getZ() +
            "|" + kind +
            "|" + expectedAfter.blockData() +
            "|" + expectedAfter.blockState()
        val changeId = deterministic(taggedEnemy.eventId, "BLOCK_CHANGE", actionKey)
        val prepareOperationId = deterministic(changeId, "BLOCK_PREPARE", actionKey)
        val applyOperationId = deterministic(changeId, "BLOCK_APPLY", actionKey)
        event.setCancelled(true)
        val preparedDrops: List<PaperEscrowDropManager.PreparedDrop> = if (kind == BlockChangeKind.EVENT_BLOCK) {
            escrowDrops.prepareBlockDrops(
                taggedEnemy.eventId,
                changeId,
                block,
                Instant.now(),
            )
        } else {
            emptyList()
        }
        try {
            blockMutations.apply(
                taggedEnemy.eventId,
                generation,
                kind,
                block,
                expectedAfter,
                changeId,
                prepareOperationId,
                applyOperationId,
                Instant.now(),
            )
        } catch (applyFailure: RuntimeException) {
            if (preparedDrops.isNotEmpty()) {
                try {
                    escrowDrops.discardPreparedDrops(preparedDrops, Instant.now())
                } catch (discardFailure: RuntimeException) {
                    applyFailure.addSuppressed(discardFailure)
                }
            }
            throw applyFailure
        }
        if (preparedDrops.isNotEmpty()) {
            escrowDrops.spawnPreparedDrops(block, preparedDrops)
        }
        return true
    }

    /**
     * Applies one path-driven destroyer break through the event-owned WAL and drop escrow.
     * Classification and the observed before-state are both rechecked at this boundary; a
     * player edit or protected block therefore fails closed. The production policy remains
     * disabled, so this method is currently an activation-ready boundary rather than a live
     * terrain mutation.
     */
    fun tryBreakObstacle(
        entity: Entity,
        destination: Location,
        taggedEnemy: TaggedEnemy,
    ): Boolean {
        requireMainThread()
        Objects.requireNonNull(entity, "entity")
        Objects.requireNonNull(destination, "destination")
        Objects.requireNonNull(taggedEnemy, "taggedEnemy")
        if (!policy.enabled() || taggedEnemy.role != EnemyRole.DESTROYER) {
            return false
        }
        if (!accessPolicy.mayRemain(taggedEnemy, entity.getUniqueId())) {
            return false
        }

        val candidate: Optional<PaperEnemyPathController.BreakCandidate>
        try {
            candidate = PaperEnemyPathController.planBreak(
                entity,
                destination,
                taggedEnemy.role,
                cores,
                accessPolicy,
            )
        } catch (_: RuntimeException) {
            // A transient Paper read failure must not become a terrain mutation or a retry that
            // overwrites an unknown block state.
            return false
        }
        if (candidate.isEmpty) {
            return false
        }
        val value = candidate.orElseThrow()
        if (!value.facts.permits(EnemyTerrainActionKind.BREAK)) {
            return false
        }
        val block = value.block
        if (!value.observedBefore.equals(PaperBlockStateCodec.captureComparable(block))) {
            // The candidate was observed before this action boundary and the world changed in
            // between. Returning false preserves the player edit and lets the next path tick
            // classify the new state again.
            return false
        }
        val target: BlockData = PaperBlockStateCodec.parseBlockData(value.targetBlockData)
        if (!target.getMaterial().isAir) {
            return false
        }
        val state: BlockState = block.getState()
        val input = TerrainMutationInput(
            block.getType().getKey().toString(),
            state is InventoryHolder,
            cores.isCore(block),
            state is org.bukkit.block.TileState,
            target.getMaterial().getKey().toString(),
        )
        if (policy.decide(
                taggedEnemy.role,
                EnemyTerrainActionKind.BREAK,
                false,
                input,
            ) != TerrainMutationDecision.ALLOW
        ) {
            return false
        }

        val expectedAfter = PaperBlockStateCodec.snapshotForBlockData(value.targetBlockData)
        val current = PaperBlockStateCodec.captureComparable(block)
        if (current.equals(expectedAfter)) {
            return false
        }
        val generation = blockMutations.nextGeneration(taggedEnemy.eventId, block)
        val actionKey = "DESTROYER_BREAK|" +
            block.getWorld().getUID() +
            "|" + block.getX() +
            "|" + block.getY() +
            "|" + block.getZ() +
            "|" + expectedAfter.blockData() +
            "|" + expectedAfter.blockState()
        val changeId = deterministic(taggedEnemy.eventId, "DESTROYER_BREAK_CHANGE", actionKey)
        val prepareOperationId = deterministic(changeId, "BLOCK_PREPARE", actionKey)
        val applyOperationId = deterministic(changeId, "BLOCK_APPLY", actionKey)
        val preparedDrops = escrowDrops.prepareBlockDrops(
            taggedEnemy.eventId,
            changeId,
            block,
            Instant.now(),
        )
        try {
            blockMutations.apply(
                taggedEnemy.eventId,
                generation,
                BlockChangeKind.EVENT_BLOCK,
                block,
                expectedAfter,
                changeId,
                prepareOperationId,
                applyOperationId,
                Instant.now(),
            )
        } catch (applyFailure: RuntimeException) {
            if (preparedDrops.isNotEmpty()) {
                try {
                    escrowDrops.discardPreparedDrops(preparedDrops, Instant.now())
                } catch (discardFailure: RuntimeException) {
                    applyFailure.addSuppressed(discardFailure)
                }
            }
            throw applyFailure
        }
        if (preparedDrops.isNotEmpty()) {
            escrowDrops.spawnPreparedDrops(block, preparedDrops)
        }
        return true
    }

    /**
     * Applies one path-driven builder bridge block through the same temporary-block WAL as event
     * block changes. The production policy is disabled, so the path controller currently remains
     * read-only while this complete action boundary is exercised by future activation tests.
     */
    fun tryBuildBridge(
        entity: Entity,
        destination: Location,
        taggedEnemy: TaggedEnemy,
    ): Boolean {
        requireMainThread()
        Objects.requireNonNull(entity, "entity")
        Objects.requireNonNull(destination, "destination")
        Objects.requireNonNull(taggedEnemy, "taggedEnemy")
        if (!policy.enabled() || taggedEnemy.role != EnemyRole.BUILDER) {
            return false
        }
        if (!accessPolicy.mayRemain(taggedEnemy, entity.getUniqueId())) {
            return false
        }

        val activeTemporaryBlocks = blockMutations.countUnresolvedTemporaryBlocks(
            taggedEnemy.eventId,
        )
        val candidate = PaperEnemyPathController.planBridge(
            entity,
            destination,
            taggedEnemy.role,
            cores,
            accessPolicy,
            activeTemporaryBlocks,
        )
        if (candidate.isEmpty) {
            return false
        }
        val value = candidate.orElseThrow()
        if (!value.facts.permits(EnemyTerrainActionKind.BUILD)) {
            return false
        }
        val block = value.block
        if (!value.observedBefore.equals(PaperBlockStateCodec.captureComparable(block))) {
            // The candidate was observed before this action boundary and the world changed in
            // between. Returning false preserves the player edit and lets the next path tick
            // classify the new state again.
            return false
        }
        val target: BlockData = PaperBlockStateCodec.parseBlockData(value.targetBlockData)
        if (target.getMaterial().getKey().toString() != value.plan.targetMaterialKey) {
            return false
        }
        val state: BlockState = block.getState()
        val input = TerrainMutationInput(
            block.getType().getKey().toString(),
            state is InventoryHolder,
            cores.isCore(block),
            state is org.bukkit.block.TileState,
            target.getMaterial().getKey().toString(),
        )
        if (policy.decide(
                taggedEnemy.role,
                EnemyTerrainActionKind.BUILD,
                false,
                input,
            ) != TerrainMutationDecision.ALLOW
        ) {
            return false
        }

        val expectedAfter = PaperBlockStateCodec.snapshotForBlockData(value.targetBlockData)
        val current = PaperBlockStateCodec.captureComparable(block)
        if (current.equals(expectedAfter)) {
            return false
        }
        val generation = blockMutations.nextGeneration(taggedEnemy.eventId, block)
        val actionKey = "BRIDGE|" +
            block.getWorld().getUID() +
            "|" + block.getX() +
            "|" + block.getY() +
            "|" + block.getZ() +
            "|" + expectedAfter.blockData() +
            "|" + expectedAfter.blockState()
        val changeId = deterministic(taggedEnemy.eventId, "BRIDGE_CHANGE", actionKey)
        val prepareOperationId = deterministic(changeId, "BRIDGE_PREPARE", actionKey)
        val applyOperationId = deterministic(changeId, "BRIDGE_APPLY", actionKey)
        blockMutations.apply(
            taggedEnemy.eventId,
            generation,
            BlockChangeKind.TEMPORARY_BLOCK,
            block,
            expectedAfter,
            changeId,
            prepareOperationId,
            applyOperationId,
            Instant.now(),
        )
        return true
    }

    private fun deterministic(base: UUID, namespace: String, value: String): UUID =
        UUID.nameUUIDFromBytes(
            (base.toString() + "|" + namespace + "|" + value)
                .toByteArray(StandardCharsets.UTF_8),
        )

    private fun requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw IllegalStateException("Enemy terrain action must run on the main thread")
        }
    }
}
