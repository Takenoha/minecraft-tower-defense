package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/** Keeps protected targets physically synchronized while a combat area is active. */
public final class ProtectedBlockListener implements Listener {
    private final CoreRegistry cores;
    private final EnemyAccessPolicy accessPolicy;

    public ProtectedBlockListener(CoreRegistry cores, EnemyAccessPolicy accessPolicy) {
        this.cores = Objects.requireNonNull(cores, "cores");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isProtected(event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesProtected(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesProtected(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLiquid(BlockFromToEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (isProtected(event.getBlock()) || isProtectedTarget(event.getBlock(), event.getNewState())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (isProtected(event.getBlock()) || isProtectedTarget(event.getBlock(), event.getNewState())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (isProtected(event.getBlock())
                || isProtectedTarget(event.getBlock(), event.getChangedBlockData().createBlockState())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isProtected(event.getBlock(), event.getTo().getKey().toString(), false)) {
            event.setCancelled(true);
        }
    }

    private boolean isProtected(Block block) {
        Objects.requireNonNull(block, "block");
        return isProtected(
                block,
                block.getType().getKey().toString(),
                block.getState() instanceof TileState);
    }

    private boolean isProtectedTarget(Block block, BlockState target) {
        Objects.requireNonNull(target, "target");
        return isProtected(
                block,
                target.getType().getKey().toString(),
                target instanceof TileState);
    }

    private boolean isProtected(Block block, String materialKey, boolean tileState) {
        if (cores.isCore(block)) {
            return true;
        }
        return isProtectedAt(block.getLocation(), materialKey, tileState);
    }

    private boolean isProtectedAt(Location location, String materialKey, boolean tileState) {
        return accessPolicy.isCombatAreaProtected(location)
                && (tileState || TerrainMutationPolicy.isRequiredMaterial(materialKey));
    }

    private boolean movesProtected(Block piston, List<Block> blocks, BlockFace direction) {
        if (isProtected(piston)) {
            return true;
        }
        for (Block block : blocks) {
            Block destination = block.getRelative(direction);
            if (isProtected(block)
                    || isProtected(destination)
                    || isProtectedAt(
                            destination.getLocation(),
                            block.getType().getKey().toString(),
                            block.getState() instanceof TileState)) {
                return true;
            }
        }
        return false;
    }
}
