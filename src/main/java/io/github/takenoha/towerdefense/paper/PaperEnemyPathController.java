package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyBridgePlan;
import io.github.takenoha.towerdefense.runtime.EnemyBridgePlanner;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;

/** Captures one read-only forward obstacle snapshot for the role-aware path controller. */
public final class PaperEnemyPathController {
    private static final String AIR = "minecraft:air";

    private PaperEnemyPathController() {
    }

    /**
     * Reads the next horizontal candidate without changing the entity, pathfinder, or world.
     * Destroyers and normal enemies inspect an air target for break decisions. Builders inspect
     * the verified support block as a planning target; the target is never placed here.
     */
    public static EnemyObstacleFacts inspect(
            Entity entity,
            Location destination,
            EnemyRole role,
            CoreRegistry cores,
            EnemyAccessPolicy accessPolicy) {
        requireMainThread();
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(cores, "cores");
        Objects.requireNonNull(accessPolicy, "accessPolicy");

        Location current = entity.getLocation();
        if (current.getWorld() == null
                || destination.getWorld() == null
                || !current.getWorld().equals(destination.getWorld())) {
            return unavailable();
        }

        Block candidate = nextHorizontalBlock(current, destination);
        BlockData target = targetFor(candidate, role);
        return PaperEnemyObstacleClassifier.classify(candidate, target, cores, accessPolicy);
    }

    /**
     * Builds a one-block bridge proposal from the same read-only snapshot used by path control.
     * The proposal records the observed before-state so a later action cannot silently overwrite
     * a player edit. No Paper block is changed by this method.
     */
    public static Optional<BridgeCandidate> planBridge(
            Entity entity,
            Location destination,
            EnemyRole role,
            CoreRegistry cores,
            EnemyAccessPolicy accessPolicy,
            long activeTemporaryBlocks) {
        requireMainThread();
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(cores, "cores");
        Objects.requireNonNull(accessPolicy, "accessPolicy");
        if (role != EnemyRole.BUILDER) {
            return Optional.empty();
        }

        Location current = entity.getLocation();
        if (current.getWorld() == null
                || destination.getWorld() == null
                || !current.getWorld().equals(destination.getWorld())) {
            return Optional.empty();
        }

        Block candidate = nextHorizontalBlock(current, destination);
        Block support = candidate.getRelative(BlockFace.DOWN);
        if (!support.getType().isSolid()) {
            return Optional.empty();
        }
        BlockData target = support.getBlockData();
        EnemyObstacleFacts facts = PaperEnemyObstacleClassifier.classify(
                candidate,
                target,
                cores,
                accessPolicy);
        Optional<EnemyBridgePlan> plan = EnemyBridgePlanner.plan(
                facts,
                activeTemporaryBlocks);
        if (plan.isEmpty()
                || !target.getMaterial().getKey().toString()
                        .equals(plan.orElseThrow().targetMaterialKey())) {
            return Optional.empty();
        }
        return Optional.of(new BridgeCandidate(
                candidate,
                target.getAsString(),
                plan.orElseThrow(),
                facts,
                PaperBlockStateCodec.captureComparable(candidate)));
    }

    private static Block nextHorizontalBlock(Location current, Location destination) {
        double deltaX = destination.getX() - current.getX();
        double deltaZ = destination.getZ() - current.getZ();
        int stepX = 0;
        int stepZ = 0;
        if (Math.abs(deltaX) >= Math.abs(deltaZ) && Math.abs(deltaX) > 0.001d) {
            stepX = deltaX > 0.0d ? 1 : -1;
        } else if (Math.abs(deltaZ) > 0.001d) {
            stepZ = deltaZ > 0.0d ? 1 : -1;
        }
        return current.getBlock().getRelative(stepX, 0, stepZ);
    }

    private static BlockData targetFor(Block candidate, EnemyRole role) {
        if (role != EnemyRole.BUILDER) {
            return Bukkit.createBlockData(Material.AIR);
        }
        Block support = candidate.getRelative(BlockFace.DOWN);
        if (!support.getType().isSolid()) {
            return Bukkit.createBlockData(Material.AIR);
        }
        return support.getBlockData();
    }

    private static EnemyObstacleFacts unavailable() {
        return new EnemyObstacleFacts(
                EnemyObstacleClassification.UNAVAILABLE,
                AIR,
                AIR,
                false,
                false);
    }

    /** Read-only candidate passed to the main-thread mutation boundary. */
    public record BridgeCandidate(
            Block block,
            String targetBlockData,
            EnemyBridgePlan plan,
            EnemyObstacleFacts facts,
            BlockStateSnapshot observedBefore) {
        public BridgeCandidate {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(targetBlockData, "targetBlockData");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(facts, "facts");
            Objects.requireNonNull(observedBefore, "observedBefore");
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Enemy path inspection must run on the main thread");
        }
    }
}
