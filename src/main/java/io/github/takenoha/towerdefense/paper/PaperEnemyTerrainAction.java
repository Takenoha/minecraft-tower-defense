package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.BlockChangeKind;
import io.github.takenoha.towerdefense.persistence.BlockStateSnapshot;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.TaggedEnemy;
import io.github.takenoha.towerdefense.runtime.TerrainMutationDecision;
import io.github.takenoha.towerdefense.runtime.TerrainMutationInput;
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.InventoryHolder;

/** Applies one guarded enemy block action through the PR4 write-ahead adapter. */
public final class PaperEnemyTerrainAction {
    private final TerrainMutationPolicy policy;
    private final PaperBlockMutationAdapter blockMutations;
    private final CoreRegistry cores;
    private final EnemyAccessPolicy accessPolicy;

    public PaperEnemyTerrainAction(
            TerrainMutationPolicy policy,
            PaperBlockMutationAdapter blockMutations,
            CoreRegistry cores,
            EnemyAccessPolicy accessPolicy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.blockMutations = Objects.requireNonNull(blockMutations, "blockMutations");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    /**
     * Handles an EntityChangeBlockEvent that belongs to an event enemy.
     *
     * <p>The caller remains responsible for cancelling every tagged enemy event. This method only
     * applies an action when the experimental policy, live event identity, area, and block policy
     * all allow it. The production plugin currently constructs this policy disabled.</p>
     */
    public boolean tryApply(EntityChangeBlockEvent event, TaggedEnemy taggedEnemy) {
        requireMainThread();
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(taggedEnemy, "taggedEnemy");
        if (!policy.enabled()) {
            return false;
        }
        Entity entity = event.getEntity();
        if (!accessPolicy.mayRemain(taggedEnemy, entity.getUniqueId())) {
            return false;
        }
        Block block = event.getBlock();
        if (!accessPolicy.isCombatAreaProtected(block.getLocation())) {
            return false;
        }
        BlockState state = block.getState();
        TerrainMutationInput input = new TerrainMutationInput(
                block.getType().getKey().toString(),
                state instanceof InventoryHolder,
                cores.isCore(block),
                event.getTo().getKey().toString());
        if (policy.decide(input) != TerrainMutationDecision.ALLOW) {
            return false;
        }

        BlockStateSnapshot expectedAfter = PaperBlockStateCodec.snapshotForBlockData(
                Bukkit.createBlockData(event.getTo()).getAsString());
        if (PaperBlockStateCodec.captureComparable(block).equals(expectedAfter)) {
            return false;
        }
        BlockChangeKind kind = event.getTo().isAir()
                ? BlockChangeKind.EVENT_BLOCK
                : BlockChangeKind.TEMPORARY_BLOCK;
        UUID changeId = UUID.randomUUID();
        UUID prepareOperationId = UUID.randomUUID();
        UUID applyOperationId = UUID.randomUUID();
        event.setCancelled(true);
        blockMutations.apply(
                taggedEnemy.eventId(),
                blockMutations.nextGeneration(taggedEnemy.eventId(), block),
                kind,
                block,
                expectedAfter,
                changeId,
                prepareOperationId,
                applyOperationId,
                Instant.now());
        return true;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Enemy terrain action must run on the main thread");
        }
    }
}
