package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.EnemyLifecycleSink;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/** Applies the no-loot/no-portal safety boundary to event enemies. */
public final class EventEnemyListener implements Listener {
    private final EventEnemyTagger tagger;
    private final EnemyLifecycleSink lifecycleSink;
    private final EnemyAccessPolicy accessPolicy;
    private final PaperEnemyTerrainAction terrainAction;

    public EventEnemyListener(
            EventEnemyTagger tagger,
            EnemyLifecycleSink lifecycleSink,
            EnemyAccessPolicy accessPolicy,
            PaperEnemyTerrainAction terrainAction) {
        this.tagger = Objects.requireNonNull(tagger, "tagger");
        this.lifecycleSink = Objects.requireNonNull(lifecycleSink, "lifecycleSink");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.terrainAction = Objects.requireNonNull(terrainAction, "terrainAction");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        tagger.read(event.getEntity()).ifPresent(taggedEnemy -> {
            Player player = event instanceof EntityDamageByEntityEvent byEntity
                    ? responsiblePlayer(byEntity)
                    : null;
            if (!accessPolicy.mayRemain(taggedEnemy, event.getEntity().getUniqueId())
                    || player == null
                    || !accessPolicy.mayAffect(taggedEnemy, player.getUniqueId())) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        tagger.read(event.getEntity()).ifPresent(taggedEnemy -> {
            if (!accessPolicy.mayRemain(taggedEnemy, event.getEntity().getUniqueId())
                    || !(event.getTarget() instanceof Player player)
                    || !accessPolicy.mayAffect(taggedEnemy, player.getUniqueId())) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            tagger.read(entity).ifPresent(taggedEnemy -> {
                if (!accessPolicy.mayRemain(taggedEnemy, entity.getUniqueId())) {
                    entity.remove();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!accessPolicy.mayModifyCombatArea(
                event.getPlayer().getUniqueId(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!accessPolicy.mayModifyCombatArea(
                event.getPlayer().getUniqueId(), event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!accessPolicy.mayModifyCombatArea(
                event.getPlayer().getUniqueId(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!accessPolicy.mayModifyCombatArea(
                event.getPlayer().getUniqueId(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (accessPolicy.isCombatAreaProtected(event.getBlock().getLocation())
                && (player == null || !accessPolicy.mayModifyCombatArea(
                        player.getUniqueId(), event.getBlock().getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonTouchesCombatArea(
                event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonTouchesCombatArea(
                event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        tagger.read(event.getEntity()).ifPresent(taggedEnemy -> {
            event.getDrops().clear();
            event.setDroppedExp(0);
            lifecycleSink.onDefeated(event.getEntity(), taggedEnemy);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropItem(EntityDropItemEvent event) {
        if (tagger.read(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        tagger.read(event.getEntity()).ifPresent(taggedEnemy -> {
            event.setCancelled(true);
            terrainAction.tryApply(event, taggedEnemy);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (tagger.read(event.getEntity()).isPresent()) {
            event.blockList().clear();
            event.setYield(0.0f);
            return;
        }
        event.blockList().removeIf(
                block -> accessPolicy.isCombatAreaProtected(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(
                block -> accessPolicy.isCombatAreaProtected(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (tagger.read(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (tagger.read(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (tagger.read(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    private static Player responsiblePlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private boolean pistonTouchesCombatArea(
            Block piston,
            List<Block> movedBlocks,
            org.bukkit.block.BlockFace direction) {
        if (accessPolicy.isCombatAreaProtected(piston.getLocation())) {
            return true;
        }
        for (Block moved : movedBlocks) {
            Location source = moved.getLocation();
            Location destination = moved.getRelative(direction).getLocation();
            if (accessPolicy.isCombatAreaProtected(source)
                    || accessPolicy.isCombatAreaProtected(destination)) {
                return true;
            }
        }
        return false;
    }
}
