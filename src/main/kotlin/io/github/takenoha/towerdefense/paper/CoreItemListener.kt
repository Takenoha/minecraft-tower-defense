package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.config.PluginSettings
import io.github.takenoha.towerdefense.domain.CombatArea
import io.github.takenoha.towerdefense.persistence.CorePlacement
import io.github.takenoha.towerdefense.persistence.CorePlacementResult
import io.github.takenoha.towerdefense.persistence.CoreRecord
import io.github.takenoha.towerdefense.persistence.DefenseRepository
import io.github.takenoha.towerdefense.persistence.TeamRecord
import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.HashSet
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.logging.Level
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.plugin.java.JavaPlugin

/** Public core recipe and the durable main-thread physical placement bridge. */
class CoreItemListener(
    plugin: JavaPlugin,
    settings: PluginSettings,
    repository: DefenseRepository,
    databaseExecutor: DatabaseExecutor,
    sessions: DefenseSessionManager,
    cores: CoreRegistry,
    regionProtection: ThirdPartyRegionProtectionAdapter,
    itemTagger: CoreItemTagger,
) : Listener {
    private val pluginValue = Objects.requireNonNull(plugin, "plugin")
    private val settingsValue = Objects.requireNonNull(settings, "settings")
    private val repositoryValue = Objects.requireNonNull(repository, "repository")
    private val databaseExecutorValue = Objects.requireNonNull(databaseExecutor, "databaseExecutor")
    private val sessionsValue = Objects.requireNonNull(sessions, "sessions")
    private val coresValue = Objects.requireNonNull(cores, "cores")
    private val regionProtectionValue = Objects.requireNonNull(regionProtection, "regionProtection")
    private val itemTaggerValue = Objects.requireNonNull(itemTagger, "itemTagger")
    private val blockTagger = CoreBlockTagger(pluginValue)
    private val placementInFlight = HashSet<UUID>()
    private val appliedItemIds = HashSet(repositoryValue.loadAppliedCorePlacementItemIds())
    private val combatArea = CombatAreaContext(settingsValue)

    /** Migrates only database-registered legacy beacon coordinates. */
    fun reconcileRegisteredCoreBlocks() {
        val durableCores: List<CoreRecord>
        try {
            durableCores = repositoryValue.loadAllCores()
        } catch (failure: RuntimeException) {
            pluginValue.logger.log(
                Level.SEVERE,
                "Cannot reconcile registered core blocks because the durable core read failed",
                failure,
            )
            return
        }
        for (core in durableCores) {
            val world = Bukkit.getWorld(core.worldId())
            if (world == null) {
                pluginValue.logger.warning("Cannot reconcile core ${core.id()}: world is not loaded")
                continue
            }
            val block = world.getBlockAt(core.blockX(), core.blockY(), core.blockZ())
            if (block.type == CoreMaterialPolicy.LEGACY_BLOCK) {
                block.setType(CORE_BLOCK, false)
            } else if (!CoreMaterialPolicy.isCurrentBlock(block.type)) {
                pluginValue.logger.warning(
                    "Registered core ${core.id()} has unexpected block ${block.type} " +
                        "at its durable coordinate; leaving it intact",
                )
            }
        }
    }

    /** Registers the compact vanilla recipe for an unbound core item. */
    fun registerRecipe() {
        val key = NamespacedKey(pluginValue, "core")
        val recipe = configureRecipe(ShapedRecipe(key, itemTaggerValue.recipeTemplate()))
        Bukkit.removeRecipe(key)
        Bukkit.addRecipe(recipe)
    }

    /** Restores prepared physical placement intents before normal gameplay begins. */
    fun recoverPreparedPlacements() {
        for (placement in repositoryValue.loadPendingCorePlacements()) {
            if (recoverPhysicalPlacement(placement)) {
                databaseExecutorValue.execute {
                    repositoryValue.rollbackCorePlacement(placement.operationId(), Instant.now())
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val result = event.currentItem
        if (itemTaggerValue.isRecipeTemplate(result)) {
            if (event.isShiftClick) {
                event.isCancelled = true
                event.whoClicked.sendMessage(
                    Component.text("コアは1個ずつクラフトしてください。", NamedTextColor.YELLOW),
                )
                return
            }
            event.currentItem = itemTaggerValue.createUnbound(UUID.randomUUID())
            val player = event.whoClicked as? Player ?: return
            val discovered = TowerRecipeCatalog.discoverAll(pluginValue, player)
            if (discovered > 0) {
                player.sendMessage(
                    Component.text("タワー7種のレシピを解放しました。", NamedTextColor.GREEN),
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onJoin(event: PlayerJoinEvent) {
        reconcileRegisteredCoreBlocks()
        reconcileAppliedItems(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }
        val identity = itemTaggerValue.read(event.item).orElse(null) ?: return
        event.isCancelled = true
        val target = event.clickedBlock ?: return
        beginPlacement(event.player, target, identity, true)
    }

    /** Starts a GUI-confirmed relocation without exposing a transient duplicate item. */
    fun beginGuiRelocation(player: Player, target: Block, core: CoreRecord) {
        val identity = CoreItemIdentity(
            UUID.randomUUID(),
            Optional.of(core.teamId()),
            Optional.of(core.id()),
        )
        beginPlacement(player, target, identity, false)
    }

    private fun beginPlacement(
        player: Player,
        target: Block,
        identity: CoreItemIdentity,
        requiresHeldItem: Boolean,
    ) {
        if (sessionsValue.hasActiveSession()) {
            player.sendMessage(Component.text("防衛戦中はコアを設置できません。", NamedTextColor.RED))
            return
        }
        if (!placementInFlight.add(player.uniqueId)) {
            player.sendMessage(Component.text("コア設置を処理中です。", NamedTextColor.YELLOW))
            return
        }
        val previousBlockData = target.blockData.asString
        val worldId = target.world.uid
        val blockX = target.x
        val blockY = target.y
        val blockZ = target.z
        if (!isValidTarget(player, target)) {
            placementInFlight.remove(player.uniqueId)
            return
        }
        player.sendMessage(Component.text("コア設置位置を検証しています…", NamedTextColor.GRAY))
        val actorId = player.uniqueId
        databaseExecutorValue.submit {
            preparePlan(
                actorId,
                identity,
                worldId,
                blockX,
                blockY,
                blockZ,
                previousBlockData,
            )
        }.whenComplete { placement, failure ->
            runOnMainThread {
                if (failure != null) {
                    placementInFlight.remove(actorId)
                    player.sendMessage(
                        Component.text(
                            "コアを設置できません: ${rootMessage(failure)}",
                            NamedTextColor.RED,
                        ),
                    )
                    return@runOnMainThread
                }
                applyPhysicalBlock(
                    player,
                    target,
                    placement.orElseThrow(),
                    identity.itemId,
                    requiresHeldItem,
                )
            }
        }
    }

    private fun preparePlan(
        actorId: UUID,
        identity: CoreItemIdentity,
        worldId: UUID,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        previousBlockData: String,
    ): Optional<CorePlacement> {
        val relocating = identity.isBound()
        val team: TeamRecord
        val current: Optional<CoreRecord>
        if (relocating) {
            val teamId = identity.teamId.orElseThrow {
                IllegalStateException("移設用コアにチームIDがありません")
            }
            team = repositoryValue.findTeam(teamId).orElseThrow {
                IllegalStateException("移設用コアのチームが存在しません")
            }
            if (!team.members().contains(actorId)) {
                throw IllegalStateException("このチームのコアへアクセスできません")
            }
            val coreId = identity.coreId.orElseGet {
                repositoryValue.findCoreByTeam(team.id()).map(CoreRecord::id).orElseThrow {
                    IllegalStateException("移設用コアのコアIDがありません")
                }
            }
            current = repositoryValue.findCore(coreId)
            if (current.isEmpty || !current.orElseThrow().teamId().equals(team.id())) {
                throw IllegalStateException("移設対象のコアが存在しません")
            }
            val existing = current.orElseThrow()
            if (existing.currentHitPoints() != existing.maximumHitPoints()) {
                throw IllegalStateException("コアHPが満タンのときだけ移設できます")
            }
        } else {
            team = repositoryValue.findTeamByMember(actorId).orElseGet {
                repositoryValue.createSoloTeam(soloTeamId(actorId), actorId, Instant.now())
            }
            if (!team.ownerId().equals(actorId)) {
                throw IllegalStateException("コアの設置者はチームオーナーである必要があります")
            }
            current = repositoryValue.findCoreByTeam(team.id())
        }
        val rebuilding = !relocating && current.isPresent && current.orElseThrow().currentHitPoints() == 0L
        val coreId = if (relocating) {
            identity.coreId.orElseThrow()
        } else if (rebuilding) {
            current.orElseThrow().id()
        } else {
            identity.itemId
        }
        val maximumHitPoints = if (relocating) {
            current.orElseThrow().maximumHitPoints()
        } else {
            settingsValue.core().maxHealth.toLong()
        }
        val placement = CorePlacement.prepared(
            UUID.randomUUID(),
            identity.itemId,
            coreId,
            actorId,
            team.id(),
            worldId,
            blockX,
            blockY,
            blockZ,
            maximumHitPoints,
            settingsValue.combat().minimumCoreDistance(),
            rebuilding,
            relocating,
            previousBlockData,
            Instant.now(),
        )
        return Optional.of(repositoryValue.prepareCorePlacement(placement))
    }

    private fun applyPhysicalBlock(
        player: Player,
        target: Block,
        placement: CorePlacement,
        expectedItemId: UUID,
        requiresHeldItem: Boolean,
    ) {
        val actorId = player.uniqueId
        if ((requiresHeldItem && !itemTaggerValue.hasItemId(player.inventory.itemInMainHand, expectedItemId)) ||
            !isStillOriginal(target, placement.previousBlockData()) ||
            sessionsValue.hasActiveSession() ||
            !isValidTarget(player, target)
        ) {
            rollbackPrepared(
                player,
                target,
                placement,
                null,
                "設置前に対象ブロックまたはインベントリが変更されました。",
            )
            return
        }
        var relocationState: RelocationPhysicalState? = null
        if (placement.relocatingExistingCore()) {
            relocationState = detachSourceCore(placement)
            if (relocationState == null) {
                rollbackPrepared(
                    player,
                    target,
                    placement,
                    null,
                    "移設元のコア状態を確認できないため、移設を取り消しました。",
                )
                return
            }
        }
        target.setType(CORE_BLOCK, false)
        if (!blockTagger.tag(target, placement)) {
            restore(target, placement.previousBlockData())
            rollbackPrepared(
                player,
                target,
                placement,
                relocationState,
                "このPaperブロックはコア情報を保持できません。",
            )
            return
        }
        val physicalState = relocationState
        databaseExecutorValue.submit {
            repositoryValue.applyCorePlacement(placement.operationId(), Instant.now())
        }.whenComplete { result, failure ->
            runOnMainThread {
                if (failure != null) {
                    restore(target, placement.previousBlockData())
                    rollbackPrepared(
                        player,
                        target,
                        placement,
                        physicalState,
                        "永続化に失敗したため設置を取り消しました。",
                    )
                    return@runOnMainThread
                }
                finishPlacement(player, target, placement, result, expectedItemId)
            }
        }
    }

    private fun finishPlacement(
        player: Player,
        @Suppress("UNUSED_PARAMETER") target: Block,
        placement: CorePlacement,
        result: CorePlacementResult,
        itemId: UUID,
    ) {
        placementInFlight.remove(player.uniqueId)
        try {
            coresValue.replace(result.core)
        } catch (registryFailure: RuntimeException) {
            pluginValue.logger.severe(
                "Core placement applied but could not refresh the main-thread registry: " +
                    registryFailure.message,
            )
        }
        appliedItemIds.add(itemId)
        removeMatchingItems(itemId)
        player.sendMessage(
            Component.text(
                if (placement.relocatingExistingCore()) {
                    "コアを移設しました。チームの防衛戦拠点を更新しました。"
                } else {
                    "コアを設置しました。チームの防衛戦拠点として登録されています。"
                },
                NamedTextColor.GREEN,
            ),
        )
    }

    private fun rollbackPrepared(
        player: Player,
        target: Block,
        placement: CorePlacement,
        relocationState: RelocationPhysicalState?,
        message: String,
    ) {
        var targetRestored = true
        try {
            if (blockTagger.matches(target, placement)) {
                restore(target, placement.previousBlockData())
            }
            targetRestored = placement.previousBlockData() == target.blockData.asString
        } catch (failure: RuntimeException) {
            targetRestored = false
            pluginValue.logger.log(
                Level.SEVERE,
                "Could not restore the target block for failed core placement",
                failure,
            )
        }
        val sourceRestored = relocationState?.let { restoreSourceCore(it) } ?: true
        placementInFlight.remove(player.uniqueId)
        if (targetRestored && sourceRestored) {
            databaseExecutorValue.execute {
                repositoryValue.rollbackCorePlacement(placement.operationId(), Instant.now())
            }
        } else {
            pluginValue.logger.severe(
                "Keeping failed core placement ${placement.operationId()} PREPARED " +
                    "because its physical state could not be restored",
            )
        }
        player.sendMessage(Component.text(message, NamedTextColor.RED))
    }

    private fun isValidTarget(player: Player, target: Block): Boolean {
        if (target.world != player.world || target.world.environment != World.Environment.NORMAL) {
            player.sendMessage(
                Component.text(
                    "コアはプレイヤーと同じOverworldへ設置してください。",
                    NamedTextColor.RED,
                ),
            )
            return false
        }
        if (!target.type.isSolid || target.state is TileState || coresValue.isCore(target) ||
            TerrainMutationPolicy.isRequiredMaterial(target.type.key.toString())
        ) {
            player.sendMessage(
                Component.text(
                    "そのブロックはコア設置先にできません。通常の固体ブロックを選んでください。",
                    NamedTextColor.RED,
                ),
            )
            return false
        }
        val violations = PaperCombatAreaSafetyValidator.violations(
            target.world,
            target.x + 0.5,
            target.z + 0.5,
            combatArea.value,
            settingsValue.protection(),
            regionProtectionValue,
        )
        if (violations.isNotEmpty()) {
            player.sendMessage(
                Component.text(
                    "コア周辺が保護境界を満たしません: ${violations.joinToString("; ")}",
                    NamedTextColor.RED,
                ),
            )
            return false
        }
        return true
    }

    private fun reconcileAppliedItems(player: Player) {
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot)
            if (itemTaggerValue.read(item).map { identity -> appliedItemIds.contains(identity.itemId) }
                    .orElse(false)
            ) {
                player.inventory.setItem(slot, null)
            }
        }
    }

    private fun removeMatchingItems(itemId: UUID) {
        for (player in Bukkit.getOnlinePlayers()) {
            for (slot in 0 until player.inventory.size) {
                val item = player.inventory.getItem(slot)
                if (itemTaggerValue.hasItemId(item, itemId)) {
                    player.inventory.setItem(slot, null)
                }
            }
        }
        for (world in Bukkit.getWorlds()) {
            for (item in world.getEntitiesByClass(Item::class.java)) {
                if (itemTaggerValue.hasItemId(item.itemStack, itemId)) {
                    item.remove()
                }
            }
        }
    }

    private fun runOnMainThread(action: Runnable) {
        if (pluginValue.isEnabled) {
            Bukkit.getScheduler().runTask(pluginValue, action)
        }
    }

    private fun recoverPhysicalPlacement(placement: CorePlacement): Boolean {
        if (placement.relocatingExistingCore()) {
            return recoverPreparedRelocation(placement)
        }
        val world = Bukkit.getWorld(placement.worldId())
        if (world == null) {
            pluginValue.logger.severe(
                "Cannot recover prepared core placement ${placement.operationId()}: " +
                    "world ${placement.worldId()} is not loaded",
            )
            return false
        }
        val block = world.getBlockAt(placement.blockX(), placement.blockY(), placement.blockZ())
        if (blockTagger.matches(block, placement)) {
            try {
                restore(block, placement.previousBlockData())
                if (placement.previousBlockData() == block.blockData.asString) {
                    return true
                }
            } catch (recoveryFailure: RuntimeException) {
                pluginValue.logger.log(
                    Level.SEVERE,
                    "Cannot restore prepared core placement ${placement.operationId()}",
                    recoveryFailure,
                )
                return false
            }
            pluginValue.logger.severe(
                "Prepared core placement ${placement.operationId()} did not restore its original block data",
            )
            return false
        }
        if (placement.previousBlockData() == block.blockData.asString) {
            return true
        }
        pluginValue.logger.severe(
            "Prepared core placement ${placement.operationId()} has an unknown physical block state; " +
                "leaving it PREPARED",
        )
        return false
    }

    private fun detachSourceCore(placement: CorePlacement): RelocationPhysicalState? {
        val core = repositoryValue.findCore(placement.coreId()).orElse(null) ?: return null
        val original = repositoryValue.findAppliedCorePlacementByCore(placement.coreId()).orElse(null)
            ?: return null
        val world = Bukkit.getWorld(core.worldId()) ?: return null
        val source = world.getBlockAt(core.blockX(), core.blockY(), core.blockZ())
        if (!coresValue.at(source).map { value -> value.id().equals(core.id()) }.orElse(false) ||
            !blockTagger.matches(source, original)
        ) {
            return null
        }
        try {
            restore(source, original.previousBlockData())
        } catch (failure: RuntimeException) {
            pluginValue.logger.log(
                Level.WARNING,
                "Could not restore the source block for core relocation",
                failure,
            )
            return null
        }
        if (original.previousBlockData() != source.blockData.asString) {
            return null
        }
        coresValue.unregister(core.id())
        return RelocationPhysicalState(source, original)
    }

    private fun restoreSourceCore(relocationState: RelocationPhysicalState): Boolean {
        return try {
            relocationState.source.setType(CORE_BLOCK, false)
            if (!blockTagger.tag(relocationState.source, relocationState.originalPlacement)) {
                throw IllegalStateException("the source beacon could not be tagged")
            }
            if (!blockTagger.matches(relocationState.source, relocationState.originalPlacement)) {
                throw IllegalStateException("the source beacon tag could not be verified")
            }
            coresValue.register(
                repositoryValue.findCore(relocationState.originalPlacement.coreId()).orElseThrow {
                    IllegalStateException("the source core row is missing")
                },
            )
            true
        } catch (failure: RuntimeException) {
            pluginValue.logger.log(
                Level.SEVERE,
                "Could not restore the source core after relocation failure",
                failure,
            )
            false
        }
    }

    private fun recoverPreparedRelocation(placement: CorePlacement): Boolean {
        return try {
            val core = repositoryValue.findCore(placement.coreId()).orElse(null)
            val original = repositoryValue.findAppliedCorePlacementByCore(placement.coreId()).orElse(null)
            val targetWorld = Bukkit.getWorld(placement.worldId())
            if (core == null || original == null || targetWorld == null) {
                pluginValue.logger.severe(
                    "Cannot recover prepared core relocation ${placement.operationId()}: " +
                        "durable source state is incomplete",
                )
                return false
            }
            val target = targetWorld.getBlockAt(
                placement.blockX(),
                placement.blockY(),
                placement.blockZ(),
            )
            if (blockTagger.matches(target, placement)) {
                restore(target, placement.previousBlockData())
            } else if (placement.previousBlockData() != target.blockData.asString) {
                pluginValue.logger.severe(
                    "Prepared core relocation ${placement.operationId()} has an unknown target block state",
                )
                return false
            }
            val sourceWorld = Bukkit.getWorld(core.worldId()) ?: return false
            val source = sourceWorld.getBlockAt(core.blockX(), core.blockY(), core.blockZ())
            if (blockTagger.matches(source, original)) {
                return true
            }
            if (original.previousBlockData() != source.blockData.asString) {
                pluginValue.logger.severe(
                    "Prepared core relocation ${placement.operationId()} has an unknown source block state",
                )
                return false
            }
            source.setType(CORE_BLOCK, false)
            blockTagger.tag(source, original)
        } catch (recoveryFailure: RuntimeException) {
            pluginValue.logger.log(
                Level.SEVERE,
                "Could not recover prepared core relocation ${placement.operationId()}",
                recoveryFailure,
            )
            false
        }
    }

    private data class RelocationPhysicalState(
        val source: Block,
        val originalPlacement: CorePlacement,
    )

    private data class CombatAreaContext(val value: CombatArea) {
        constructor(settings: PluginSettings) : this(
            CombatArea(
                settings.combat().radius(),
                settings.combat().spawnInner(),
                settings.combat().spawnOuter(),
                settings.combat().minimumCoreDistance(),
                settings.combat().coreGap(),
            ),
        )
    }

    private companion object {
        private val CORE_BLOCK: Material = CoreMaterialPolicy.CURRENT_BLOCK

        @JvmStatic
        fun configureRecipe(recipe: ShapedRecipe): ShapedRecipe {
            Objects.requireNonNull(recipe, "recipe")
            recipe.shape(*CoreRecipeDefinition.shape().toTypedArray())
            recipe.setIngredient(
                'D',
                Material.valueOf(CoreRecipeDefinition.DIAMOND_BLOCK_MATERIAL),
            )
            recipe.setIngredient(
                'I',
                Material.valueOf(CoreRecipeDefinition.IRON_INGOT_MATERIAL),
            )
            return recipe
        }

        @JvmStatic
        private fun isStillOriginal(block: Block, previousBlockData: String): Boolean =
            block.type.isSolid && block.state !is TileState &&
                previousBlockData == block.blockData.asString

        @JvmStatic
        private fun restore(block: Block, previousBlockData: String) {
            block.setBlockData(Bukkit.createBlockData(previousBlockData), false)
        }

        @JvmStatic
        private fun soloTeamId(ownerId: UUID): UUID =
            UUID.nameUUIDFromBytes(
                ("minecraft-tower-defense:solo:$ownerId").toByteArray(StandardCharsets.UTF_8),
            )

        @JvmStatic
        private fun rootMessage(failure: Throwable): String {
            var root = failure
            if (root is CompletionException && root.cause != null) {
                root = root.cause!!
            }
            while (root.cause != null) {
                root = root.cause!!
            }
            return root.message ?: root.javaClass.simpleName
        }
    }
}
