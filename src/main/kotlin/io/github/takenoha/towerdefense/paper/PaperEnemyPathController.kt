package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts
import io.github.takenoha.towerdefense.domain.EnemyRole
import io.github.takenoha.towerdefense.domain.EnemyTerrainActionKind
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot
import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy
import io.github.takenoha.towerdefense.runtime.EnemyBridgePlan
import io.github.takenoha.towerdefense.runtime.EnemyBridgePlanner
import java.util.Objects
import java.util.Optional
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import kotlin.jvm.JvmRecord

/** Captures one read-only forward obstacle snapshot for the role-aware path controller. */
class PaperEnemyPathController private constructor() {
    companion object {
        /**
         * Reads the next horizontal candidate without changing the entity, pathfinder, or world.
         * Destroyers and normal enemies inspect an air target for break decisions. Builders inspect
         * the verified support block as a planning target; the target is never placed here.
         */
        @JvmStatic
        fun inspect(
            entity: Entity,
            destination: Location,
            role: EnemyRole,
            cores: CoreRegistry,
            accessPolicy: EnemyAccessPolicy,
        ): EnemyObstacleFacts {
            requireMainThread()
            Objects.requireNonNull(entity, "entity")
            Objects.requireNonNull(destination, "destination")
            Objects.requireNonNull(role, "role")
            Objects.requireNonNull(cores, "cores")
            Objects.requireNonNull(accessPolicy, "accessPolicy")

            val current = entity.location
            if (current.world == null || destination.world == null ||
                current.world != destination.world
            ) {
                return unavailable()
            }

            val candidate = nextHorizontalBlock(current, destination)
            val target = targetFor(candidate, role)
            return PaperEnemyObstacleClassifier.classify(candidate, target, cores, accessPolicy)
        }

        /**
         * Plans one destroyer break from the same main-thread candidate snapshot used by path control.
         * The returned candidate is read-only; the mutation boundary must re-check its before-state
         * immediately before preparing the event-owned WAL row.
         */
        @JvmStatic
        fun planBreak(
            entity: Entity,
            destination: Location,
            role: EnemyRole,
            cores: CoreRegistry,
            accessPolicy: EnemyAccessPolicy,
        ): Optional<BreakCandidate> {
            requireMainThread()
            Objects.requireNonNull(entity, "entity")
            Objects.requireNonNull(destination, "destination")
            Objects.requireNonNull(role, "role")
            Objects.requireNonNull(cores, "cores")
            Objects.requireNonNull(accessPolicy, "accessPolicy")
            if (role != EnemyRole.DESTROYER) {
                return Optional.empty()
            }

            val current = entity.location
            if (current.world == null || destination.world == null ||
                current.world != destination.world
            ) {
                return Optional.empty()
            }

            val candidate = nextHorizontalBlock(current, destination)
            val target = Bukkit.createBlockData(Material.AIR)
            val facts = PaperEnemyObstacleClassifier.classify(
                candidate,
                target,
                cores,
                accessPolicy,
            )
            if (!facts.permits(EnemyTerrainActionKind.BREAK)) {
                return Optional.empty()
            }
            return Optional.of(
                BreakCandidate(
                    candidate,
                    target.asString,
                    facts,
                    PaperBlockStateCodec.captureComparable(candidate),
                ),
            )
        }

        /**
         * Builds a one-block bridge proposal from the same read-only snapshot used by path control.
         * The proposal records the observed before-state so a later action cannot silently overwrite
         * a player edit. No Paper block is changed by this method.
         */
        @JvmStatic
        fun planBridge(
            entity: Entity,
            destination: Location,
            role: EnemyRole,
            cores: CoreRegistry,
            accessPolicy: EnemyAccessPolicy,
            activeTemporaryBlocks: Long,
        ): Optional<BridgeCandidate> {
            requireMainThread()
            Objects.requireNonNull(entity, "entity")
            Objects.requireNonNull(destination, "destination")
            Objects.requireNonNull(role, "role")
            Objects.requireNonNull(cores, "cores")
            Objects.requireNonNull(accessPolicy, "accessPolicy")
            if (role != EnemyRole.BUILDER) {
                return Optional.empty()
            }

            val current = entity.location
            if (current.world == null || destination.world == null ||
                current.world != destination.world
            ) {
                return Optional.empty()
            }

            val candidate = nextHorizontalBlock(current, destination)
            val support = candidate.getRelative(BlockFace.DOWN)
            if (!support.type.isSolid) {
                return Optional.empty()
            }
            val target = support.blockData
            val facts = PaperEnemyObstacleClassifier.classify(
                candidate,
                target,
                cores,
                accessPolicy,
            )
            val plan = EnemyBridgePlanner.plan(facts, activeTemporaryBlocks)
            if (plan.isEmpty ||
                target.material.key.toString() != plan.orElseThrow().targetMaterialKey
            ) {
                return Optional.empty()
            }
            return Optional.of(
                BridgeCandidate(
                    candidate,
                    target.asString,
                    plan.orElseThrow(),
                    facts,
                    PaperBlockStateCodec.captureComparable(candidate),
                ),
            )
        }

        private fun nextHorizontalBlock(current: Location, destination: Location): Block {
            val deltaX = destination.x - current.x
            val deltaZ = destination.z - current.z
            var stepX = 0
            var stepZ = 0
            if (kotlin.math.abs(deltaX) >= kotlin.math.abs(deltaZ) &&
                kotlin.math.abs(deltaX) > 0.001
            ) {
                stepX = if (deltaX > 0.0) 1 else -1
            } else if (kotlin.math.abs(deltaZ) > 0.001) {
                stepZ = if (deltaZ > 0.0) 1 else -1
            }
            return current.block.getRelative(stepX, 0, stepZ)
        }

        private fun targetFor(candidate: Block, role: EnemyRole): BlockData {
            if (role != EnemyRole.BUILDER) {
                return Bukkit.createBlockData(Material.AIR)
            }
            val support = candidate.getRelative(BlockFace.DOWN)
            if (!support.type.isSolid) {
                return Bukkit.createBlockData(Material.AIR)
            }
            return support.blockData
        }

        private fun unavailable(): EnemyObstacleFacts = EnemyObstacleFacts.unavailable()

        private fun requireMainThread() {
            if (!Bukkit.isPrimaryThread()) {
                throw IllegalStateException(
                    "Enemy path inspection must run on the main thread",
                )
            }
        }
    }

    /** Read-only candidate passed to the main-thread mutation boundary. */
    @JvmRecord
    data class BridgeCandidate(
        val block: Block,
        val targetBlockData: String,
        val plan: EnemyBridgePlan,
        val facts: EnemyObstacleFacts,
        val observedBefore: BlockStateSnapshot,
    ) {
        init {
            Objects.requireNonNull(block, "block")
            Objects.requireNonNull(targetBlockData, "targetBlockData")
            Objects.requireNonNull(plan, "plan")
            Objects.requireNonNull(facts, "facts")
            Objects.requireNonNull(observedBefore, "observedBefore")
        }
    }

    /** Read-only destroyer candidate passed to the main-thread mutation boundary. */
    @JvmRecord
    data class BreakCandidate(
        val block: Block,
        val targetBlockData: String,
        val facts: EnemyObstacleFacts,
        val observedBefore: BlockStateSnapshot,
    ) {
        init {
            Objects.requireNonNull(block, "block")
            Objects.requireNonNull(targetBlockData, "targetBlockData")
            Objects.requireNonNull(facts, "facts")
            Objects.requireNonNull(observedBefore, "observedBefore")
        }
    }
}
