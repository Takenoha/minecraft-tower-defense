package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.EnemyRole
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy
import io.github.takenoha.towerdefense.runtime.EnemyLifecycleSink
import java.util.Objects
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.world.EntitiesLoadEvent

/** Applies the no-loot/no-portal safety boundary to event enemies. */
class EventEnemyListener(
    tagger: EventEnemyTagger,
    lifecycleSink: EnemyLifecycleSink,
    accessPolicy: EnemyAccessPolicy,
    terrainAction: PaperEnemyTerrainAction,
    towerTagger: TowerEntityTagger,
) : Listener {
    private companion object {
        @JvmStatic
        private fun responsiblePlayer(event: EntityDamageByEntityEvent): Player? {
            if (event.damager is Player) {
                return event.damager as Player
            }
            if (event.damager is Projectile &&
                (event.damager as Projectile).shooter is Player
            ) {
                return (event.damager as Projectile).shooter as Player
            }
            return null
        }
    }

    private val taggerValue = Objects.requireNonNull(tagger, "tagger")
    private val lifecycleSinkValue = Objects.requireNonNull(lifecycleSink, "lifecycleSink")
    private val accessPolicyValue = Objects.requireNonNull(accessPolicy, "accessPolicy")
    private val terrainActionValue = Objects.requireNonNull(terrainAction, "terrainAction")
    private val towerTaggerValue = Objects.requireNonNull(towerTagger, "towerTagger")

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        taggerValue.read(event.entity).ifPresent { taggedEnemy ->
            val player = if (event is EntityDamageByEntityEvent) responsiblePlayer(event) else null
            val towerAllowed = event is EntityDamageByEntityEvent &&
                towerTaggerValue.read(event.damager)
                    .map { identity ->
                        accessPolicyValue.mayAffectFromTower(taggedEnemy, identity.teamId)
                    }
                    .orElse(false)
            if (!accessPolicyValue.mayRemain(taggedEnemy, event.entity.uniqueId) ||
                (!towerAllowed &&
                    (player == null ||
                        !accessPolicyValue.mayAffect(taggedEnemy, player.uniqueId)))
            ) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        taggerValue.read(event.entity).ifPresent { taggedEnemy ->
            if (taggedEnemy.role == EnemyRole.SUPPORT ||
                !accessPolicyValue.mayRemain(taggedEnemy, event.entity.uniqueId) ||
                event.target !is Player ||
                !accessPolicyValue.mayAffect(taggedEnemy, (event.target as Player).uniqueId)
            ) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Entity ?: return
        taggerValue.read(shooter).ifPresent { taggedEnemy ->
            if (taggedEnemy.role == EnemyRole.SUPPORT) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            taggerValue.read(entity).ifPresent { taggedEnemy ->
                if (!accessPolicyValue.mayRemain(taggedEnemy, entity.uniqueId)) {
                    entity.remove()
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!accessPolicyValue.mayModifyCombatArea(
                event.player.uniqueId,
                event.block.location,
            )
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!accessPolicyValue.mayModifyCombatArea(
                event.player.uniqueId,
                event.blockPlaced.location,
            )
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (!accessPolicyValue.mayModifyCombatArea(
                event.player.uniqueId,
                event.block.location,
            )
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (!accessPolicyValue.mayModifyCombatArea(
                event.player.uniqueId,
                event.block.location,
            )
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        val player = event.player
        if (accessPolicyValue.isCombatAreaProtected(event.block.location) &&
            (player == null ||
                !accessPolicyValue.mayModifyCombatArea(
                    player.uniqueId,
                    event.block.location,
                ))
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (pistonTouchesCombatArea(event.block, event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (pistonTouchesCombatArea(event.block, event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: EntityDeathEvent) {
        taggerValue.read(event.entity).ifPresent { taggedEnemy ->
            event.drops.clear()
            event.droppedExp = 0
            lifecycleSinkValue.onDefeated(event.entity, taggedEnemy)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDropItem(event: EntityDropItemEvent) {
        if (taggerValue.read(event.entity).isPresent) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChangeBlock(event: EntityChangeBlockEvent) {
        taggerValue.read(event.entity).ifPresent { taggedEnemy ->
            event.isCancelled = true
            terrainActionValue.tryApply(event, taggedEnemy)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onExplosion(event: EntityExplodeEvent) {
        if (taggerValue.read(event.entity).isPresent) {
            event.blockList().clear()
            event.yield = 0.0f
            return
        }
        event.blockList().removeIf { block ->
            accessPolicyValue.isCombatAreaProtected(block.location)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().removeIf { block ->
            accessPolicyValue.isCombatAreaProtected(block.location)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        if (taggerValue.read(event.entity).isPresent) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortal(event: EntityPortalEvent) {
        if (taggerValue.read(event.entity).isPresent) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        if (taggerValue.read(event.entity).isPresent) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCombust(event: EntityCombustEvent) {
        if (taggerValue.read(event.entity).isPresent) {
            event.isCancelled = true
        }
    }

    private fun pistonTouchesCombatArea(
        piston: Block,
        movedBlocks: List<Block>,
        direction: BlockFace,
    ): Boolean {
        if (accessPolicyValue.isCombatAreaProtected(piston.location)) {
            return true
        }
        for (moved in movedBlocks) {
            val source: Location = moved.location
            val destination: Location = moved.getRelative(direction).location
            if (accessPolicyValue.isCombatAreaProtected(source) ||
                accessPolicyValue.isCombatAreaProtected(destination)
            ) {
                return true
            }
        }
        return false
    }

}
