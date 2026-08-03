package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.EnemyObstacleClassifier;
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;

/** Captures the live Paper block facts needed by the role-aware obstacle classifier. */
public final class PaperEnemyObstacleClassifier {
    private PaperEnemyObstacleClassifier() {
    }

    /**
     * Reads the candidate and its support on the Paper main thread without mutating either.
     * Unknown, protected, or out-of-area state is returned as a non-actionable classification.
     */
    public static EnemyObstacleFacts classify(
            Block candidate,
            BlockData target,
            CoreRegistry cores,
            EnemyAccessPolicy accessPolicy) {
        requireMainThread();
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cores, "cores");
        Objects.requireNonNull(accessPolicy, "accessPolicy");

        BlockState currentState = candidate.getState();
        BlockState targetState = target.createBlockState();
        Block support = candidate.getRelative(BlockFace.DOWN);
        BlockState supportState = support.getState();
        boolean withinCombatArea = accessPolicy.isCombatAreaProtected(
                candidate.getLocation());
        boolean supportAvailable = withinCombatArea
                && support.isSolid()
                && accessPolicy.isCombatAreaProtected(support.getLocation())
                && !isProtectedSupport(support, supportState, cores);
        return EnemyObstacleClassifier.classify(
                candidate.getType().getKey().toString(),
                candidate.isReplaceable(),
                currentState instanceof InventoryHolder,
                cores.isCore(candidate),
                currentState instanceof TileState,
                target.getMaterial().getKey().toString(),
                targetState instanceof TileState,
                supportAvailable,
                withinCombatArea);
    }

    private static boolean isProtectedSupport(
            Block support,
            BlockState supportState,
            CoreRegistry cores) {
        return cores.isCore(support)
                || supportState instanceof InventoryHolder
                || supportState instanceof TileState
                || TerrainMutationPolicy.isRequiredMaterial(
                        support.getType().getKey().toString());
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Enemy obstacle classification must run on the main thread");
        }
    }
}
