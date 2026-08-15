package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.RaidSeal
import io.github.takenoha.towerdefense.persistence.RaidSealRepository
import io.github.takenoha.towerdefense.persistence.RaidSealStatus
import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor
import java.time.Instant
import java.util.HashMap
import java.util.HashSet
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionException
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Crafter
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.CraftingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.plugin.java.JavaPlugin

/** Paper-facing craft, reconciliation, and player start flow for raid seals. */
class RaidSealListener private constructor(
    plugin: JavaPlugin,
    repository: RaidSealRepository,
    databaseExecutor: DatabaseExecutor,
    cores: CoreRegistry,
    command: TowerDefenseCommand,
    tagger: RaidSealTagger,
    tacticalSelections: Optional<TacticalBuildSelectionListener>,
) : Listener {
    constructor(
        plugin: JavaPlugin,
        repository: RaidSealRepository,
        databaseExecutor: DatabaseExecutor,
        cores: CoreRegistry,
        command: TowerDefenseCommand,
        tagger: RaidSealTagger,
    ) : this(
        plugin,
        repository,
        databaseExecutor,
        cores,
        command,
        tagger,
        Optional.empty(),
    )

    constructor(
        plugin: JavaPlugin,
        repository: RaidSealRepository,
        databaseExecutor: DatabaseExecutor,
        cores: CoreRegistry,
        command: TowerDefenseCommand,
        tagger: RaidSealTagger,
        tacticalSelections: TacticalBuildSelectionListener,
    ) : this(
        plugin,
        repository,
        databaseExecutor,
        cores,
        command,
        tagger,
        Optional.of(Objects.requireNonNull(tacticalSelections, "tacticalSelections")),
    )

    private companion object {
        @JvmStatic
        private fun rootMessage(failure: Throwable): String {
            var root = failure
            if (root is CompletionException && root.cause != null) {
                root = root.cause ?: root
            }
            while (root.cause != null) {
                root = root.cause ?: break
            }
            return root.message ?: root.javaClass.simpleName
        }

        /** Java package callers used this helper before the migration. */
        @JvmStatic
        fun configureRecipe(recipe: ShapedRecipe, stageMaterial: Material): ShapedRecipe {
            Objects.requireNonNull(recipe, "recipe")
            Objects.requireNonNull(stageMaterial, "stageMaterial")
            recipe.shape(*RaidSealRecipeDefinition.shape().toTypedArray())
            recipe.setIngredient('P', Material.valueOf(RaidSealRecipeDefinition.PAPER_MATERIAL))
            recipe.setIngredient('S', stageMaterial)
            return recipe
        }
    }

    private val pluginValue = Objects.requireNonNull(plugin, "plugin")
    private val repositoryValue = Objects.requireNonNull(repository, "repository")
    private val databaseExecutorValue = Objects.requireNonNull(databaseExecutor, "databaseExecutor")
    private val coresValue = Objects.requireNonNull(cores, "cores")
    private val commandValue = Objects.requireNonNull(command, "command")
    private val taggerValue = Objects.requireNonNull(tagger, "tagger")
    private val tacticalSelectionsValue = Objects.requireNonNull(
        tacticalSelections,
        "tacticalSelections",
    )

    /** Registers one vanilla-material recipe for each of the first ten stages. */
    fun registerRecipe() {
        for (stageLevel in RaidSealCatalog.recipeStages()) {
            val key = NamespacedKey(pluginValue, "raid_seal_stage_$stageLevel")
            val recipe = configureRecipe(
                ShapedRecipe(key, taggerValue.recipeTemplate(stageLevel)),
                Material.valueOf(RaidSealCatalog.ingredientNameFor(stageLevel)),
            )
            Bukkit.removeRecipe(key)
            Bukkit.addRecipe(recipe)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        if (containsValidSeal(event.inventory.matrix)) {
            event.isCancelled = true
            event.whoClicked.sendMessage(
                Component.text("有効な襲撃の印はクラフト材料に使えません。", NamedTextColor.RED),
            )
            return
        }
        if (!taggerValue.isRecipeTemplate(event.currentItem)) {
            return
        }
        if (event.isShiftClick) {
            event.isCancelled = true
            event.whoClicked.sendMessage(
                Component.text("襲撃の印は1個ずつクラフトしてください。", NamedTextColor.YELLOW),
            )
            return
        }
        val player = event.whoClicked as? Player
        if (player == null) {
            event.isCancelled = true
            return
        }
        val sealId = UUID.randomUUID()
        val stageLevel = taggerValue.templateStage(event.currentItem).orElseThrow {
            IllegalStateException("raid seal recipe has no valid stage")
        }
        event.currentItem = taggerValue.create(sealId, stageLevel)
        databaseExecutorValue.submit {
            repositoryValue.register(sealId, player.uniqueId, stageLevel, Instant.now())
        }.whenComplete { _, failure ->
            if (failure != null) {
                runOnMainThread {
                    removeMatchingItems(sealId)
                    player.sendMessage(
                        Component.text(
                            "襲撃の印を永続化できなかったため作成を取り消しました: ${rootMessage(failure)}",
                            NamedTextColor.RED,
                        ),
                    )
                }
            }
        }
    }

    /** Vanilla Crafter has no player inventory matrix callback; keep plugin and seal paths closed. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCrafterCraft(event: CrafterCraftEvent) {
        if (shouldCancelCrafter(event)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onJoin(event: PlayerJoinEvent) {
        reconcile(event.player)
    }

    /** Uses a physical seal directly on the registered core. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCoreInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) {
            return
        }
        val identity = taggerValue.read(event.item)
        if (identity.isEmpty) {
            return
        }
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }
        val clicked = event.clickedBlock ?: return
        val core = coresValue.at(clicked)
        if (core.isEmpty) {
            return
        }
        val value = identity.orElseThrow()
        startWithSelectedTacticalBuild(
            event.player,
            core.orElseThrow().id(),
            value.stageLevel,
            value.sealId,
        )
    }

    /** Starts the selected stage while the physical item remains the authority for payment. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onCoreGuiStart(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder
        if (holder !is CoreManagementInventoryHolder ||
            (event.rawSlot != CoreManagementGui.START_SLOT &&
                CoreManagementGui.stageLevelAt(event.rawSlot).isEmpty) ||
            event.whoClicked !is Player
        ) {
            return
        }
        val player = event.whoClicked as Player
        event.isCancelled = true
        val requestedStage = CoreManagementGui.stageLevelAt(event.rawSlot).orElse(0L)
        val seal = if (requestedStage > 0L) {
            findSeal(player, requestedStage)
        } else {
            findHighestSeal(player)
        }
        if (seal.isEmpty) {
            player.sendMessage(
                Component.text(
                    if (requestedStage > 0L) {
                        "選択したステージの襲撃の印を持っていません。"
                    } else {
                        "使用可能な襲撃の印を持っていません。"
                    },
                    NamedTextColor.RED,
                ),
            )
            return
        }
        player.closeInventory()
        val value = seal.orElseThrow()
        startWithSelectedTacticalBuild(
            player,
            holder.coreId(),
            value.stageLevel,
            value.sealId,
        )
    }

    private fun startWithSelectedTacticalBuild(
        player: Player,
        coreId: UUID,
        stage: Long,
        sealId: UUID,
    ) {
        if (tacticalSelectionsValue.isPresent) {
            tacticalSelectionsValue.orElseThrow().beginSelection(player, coreId, stage, sealId)
        } else {
            commandValue.startWithSeal(player, coreId, stage, sealId)
        }
    }

    private fun findSeal(player: Player, stageLevel: Long): Optional<RaidSealItemIdentity> {
        for (slot in 0 until player.inventory.size) {
            val identity = taggerValue.read(player.inventory.getItem(slot))
            if (identity.isPresent && identity.orElseThrow().stageLevel == stageLevel) {
                return identity
            }
        }
        return Optional.empty()
    }

    private fun findHighestSeal(player: Player): Optional<RaidSealItemIdentity> {
        var highest: RaidSealItemIdentity? = null
        for (slot in 0 until player.inventory.size) {
            val identity = taggerValue.read(player.inventory.getItem(slot))
            if (identity.isPresent) {
                val value = identity.orElseThrow()
                if (highest == null || value.stageLevel > highest.stageLevel) {
                    highest = value
                }
            }
        }
        return Optional.ofNullable(highest)
    }

    private fun reconcile(player: Player) {
        val playerId = player.uniqueId
        databaseExecutorValue.submit {
            val owned = HashMap<UUID, RaidSeal>()
            for (seal in repositoryValue.loadForOwner(playerId)) {
                owned[seal.sealId()] = seal
            }
            val refunds = repositoryValue.loadAvailableRefunds(playerId)
                .map { seal -> seal.sealId() }
                .toSet()
            Reconciliation(owned, refunds)
        }.whenComplete { data, failure ->
            runOnMainThread {
                if (failure != null) {
                    pluginValue.logger.warning(
                        "Could not reconcile raid seals for $playerId: ${rootMessage(failure)}",
                    )
                    return@runOnMainThread
                }
                val reconciliation = Objects.requireNonNull(data, "data")
                reconcileOwnedItems(player, reconciliation.owned)
                for (refundId in reconciliation.refundIds) {
                    if (!hasPhysicalItem(refundId)) {
                        give(player, reconciliation.owned[refundId])
                    }
                }
            }
        }
    }

    private fun reconcileOwnedItems(player: Player, owned: Map<UUID, RaidSeal>) {
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot)
            val identity = taggerValue.read(item)
            if (identity.isEmpty) {
                continue
            }
            val seal = owned[identity.orElseThrow().sealId]
            if (seal == null ||
                seal.status() != RaidSealStatus.AVAILABLE ||
                seal.stageLevel() != identity.orElseThrow().stageLevel
            ) {
                player.inventory.setItem(slot, null)
            } else if (taggerValue.isLegacyMaterial(item)) {
                val value = identity.orElseThrow()
                player.inventory.setItem(slot, taggerValue.create(value.sealId, value.stageLevel))
            }
        }
    }

    private fun containsValidSeal(matrix: Array<ItemStack?>): Boolean {
        for (item in matrix) {
            if (taggerValue.read(item).isPresent) {
                return true
            }
        }
        return false
    }

    private fun shouldCancelCrafter(event: CrafterCraftEvent): Boolean {
        val recipe = event.recipe
        val pluginRecipe = recipe is ShapedRecipe &&
            recipe.key.namespace == pluginValue.name.lowercase() &&
            (recipe.key.key == "core" || recipe.key.key.startsWith("raid_seal_stage_"))
        val resultTemplate = taggerValue.isRecipeTemplate(event.result)
        if (pluginRecipe || resultTemplate) {
            return true
        }
        val crafter = event.block.state as? Crafter ?: return false
        var currentSealIngredient = false
        var legacySealIngredient = false
        for (item in crafter.inventory.contents) {
            if (taggerValue.read(item).isPresent) {
                if (taggerValue.isLegacyMaterial(item)) {
                    legacySealIngredient = true
                } else {
                    currentSealIngredient = true
                }
            }
        }
        return RaidSealAutomationPolicy.cancelCrafter(
            false,
            false,
            currentSealIngredient,
            legacySealIngredient,
        )
    }

    private fun give(player: Player, seal: RaidSeal?) {
        if (seal == null) {
            return
        }
        val item = taggerValue.create(seal.sealId(), seal.stageLevel())
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { value ->
            player.world.dropItemNaturally(player.location, value)
        }
        player.sendMessage(
            Component.text("技術的復旧で新しい襲撃の印が返却されました。", NamedTextColor.GREEN),
        )
    }

    /** Kept public for Java package callers that used the old package-private helper. */
    fun hasPhysicalItem(sealId: UUID): Boolean {
        for (player in Bukkit.getOnlinePlayers()) {
            for (slot in 0 until player.inventory.size) {
                if (taggerValue.hasSealId(player.inventory.getItem(slot), sealId)) {
                    return true
                }
            }
        }
        for (world in Bukkit.getWorlds()) {
            for (item in world.getEntitiesByClass(Item::class.java)) {
                if (taggerValue.hasSealId(item.itemStack, sealId)) {
                    return true
                }
            }
        }
        return false
    }

    /** Kept public for Java package callers that used the old package-private helper. */
    fun removeMatchingItems(sealId: UUID) {
        for (player in Bukkit.getOnlinePlayers()) {
            for (slot in 0 until player.inventory.size) {
                if (taggerValue.hasSealId(player.inventory.getItem(slot), sealId)) {
                    player.inventory.setItem(slot, null)
                }
            }
        }
        for (world in Bukkit.getWorlds()) {
            for (item in world.getEntitiesByClass(Item::class.java)) {
                if (taggerValue.hasSealId(item.itemStack, sealId)) {
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

    private class Reconciliation(
        owned: Map<UUID, RaidSeal>,
        refundIds: Set<UUID>,
    ) {
        val owned: Map<UUID, RaidSeal> = java.util.Map.copyOf(owned)
        val refundIds: Set<UUID> = java.util.Set.copyOf(HashSet(refundIds))
    }
}
