package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.CoreRecord
import io.github.takenoha.towerdefense.persistence.DefenseRepository
import io.github.takenoha.towerdefense.persistence.OperationOutcome
import io.github.takenoha.towerdefense.persistence.ResourceRepository
import io.github.takenoha.towerdefense.persistence.ResourceType
import io.github.takenoha.towerdefense.persistence.ResourceVoucher
import io.github.takenoha.towerdefense.persistence.ResourceVoucherRepository
import io.github.takenoha.towerdefense.persistence.ResourceVoucherState
import io.github.takenoha.towerdefense.persistence.TeamRecord
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryOperation
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryOutcome
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryResult
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryState
import io.github.takenoha.towerdefense.persistence.VoucherRedeemOperation
import io.github.takenoha.towerdefense.persistence.VoucherRedeemResult
import io.github.takenoha.towerdefense.persistence.VoucherRedeemState
import io.github.takenoha.towerdefense.runtime.CoreRegistry
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayList
import java.util.HashMap
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionException
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.Crafter
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.event.inventory.SmithItemEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** Paper bridge for withdrawing, delivering, and redeeming team-bound point vouchers. */
class ResourceVoucherListener(
    plugin: JavaPlugin,
    repository: DefenseRepository,
    databaseExecutor: DatabaseExecutor,
    sessions: DefenseSessionManager,
    cores: CoreRegistry,
    resources: ResourceRepository,
    vouchers: ResourceVoucherRepository,
    tagger: ResourceVoucherTagger,
) : Listener {
    private val pluginValue = Objects.requireNonNull(plugin, "plugin")
    private val repositoryValue = Objects.requireNonNull(repository, "repository")
    private val databaseExecutorValue = Objects.requireNonNull(databaseExecutor, "databaseExecutor")
    private val sessionsValue = Objects.requireNonNull(sessions, "sessions")
    private val coresValue = Objects.requireNonNull(cores, "cores")
    private val resourcesValue = Objects.requireNonNull(resources, "resources")
    private val vouchersValue = Objects.requireNonNull(vouchers, "vouchers")
    private val taggerValue = Objects.requireNonNull(tagger, "tagger")
    /** Main-thread source binding until a RESERVED voucher receives its redeem receipt. */
    private val pendingRedeemVouchers = HashMap<UUID, UUID>()
    /** Keeps the source binding across quit/rejoin until the DB operation is reconciled. */
    private val offlineRedeemHolds = HashMap<UUID, UUID>()
    /** Blocks player inventory actions while join/restart recovery is still reading the DB. */
    private val voucherRecoveryGuards = PlayerRecoveryGuard()

    /** The core-specific path must run before the generic cancellation guard. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVoucherCoreInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }
        val data = taggerValue.read(event.item).orElse(null) ?: return
        if (isRecoveryGuarded(event.player.uniqueId)) {
            event.isCancelled = true
            return
        }
        event.isCancelled = true
        if (data.hasReceipt()) {
            event.player.sendMessage(
                Component.text("処理中の証票は移動・預け入れできません。", NamedTextColor.YELLOW),
            )
            return
        }
        val clicked: Block = event.clickedBlock ?: return
        val core = coresValue.at(clicked)
        if (core.isPresent) {
            openVault(event.player, core.orElseThrow().id())
        } else {
            event.player.sendMessage(
                Component.text("証票は発行元チームの登録コアへ預け入れてください。", NamedTextColor.YELLOW),
            )
        }
    }

    /** CoreManagementListener owns cancellation of the vault inventory; this handler still sees it. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    fun onVaultClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ResourceVaultInventoryHolder ?: return
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot < 0 || event.rawSlot >= event.view.topInventory.size) {
            return
        }
        if (isRecoveryGuarded(player.uniqueId)) {
            event.isCancelled = true
            return
        }
        if (event.rawSlot == ResourceVaultGui.CLOSE_SLOT) {
            return
        }
        val redeemType = when (event.rawSlot) {
            ResourceVaultGui.DEFENSE_SLOT -> ResourceType.DEFENSE_POINTS
            ResourceVaultGui.ENHANCEMENT_SLOT -> ResourceType.ENHANCEMENT_POINTS
            else -> null
        }
        if (redeemType != null) {
            val held = player.inventory.itemInMainHand
            val data = taggerValue.read(held).orElse(null)
            if (data == null || data.hasReceipt() || data.resourceType != redeemType ||
                held.amount != 1 || held.maxStackSize != 1
            ) {
                player.sendMessage(
                    Component.text(
                        redeemType.displayName() + "の証票をメインハンドに持ってください。",
                        NamedTextColor.YELLOW,
                    ),
                )
                return
            }
            beginRedeem(player, holder.coreId(), data)
            return
        }
        val request = withdrawalRequest(event.rawSlot)
        if (request != null) {
            beginWithdrawal(player, holder.coreId(), request.resourceType, request.quantity)
        }
    }

    @EventHandler
    fun onVoucherJoin(event: PlayerJoinEvent) {
        voucherRecoveryGuards.begin(event.player.uniqueId)
        reconcile(event.player.uniqueId)
    }

    @EventHandler
    fun onVoucherRespawn(event: PlayerRespawnEvent) {
        val playerId = event.player.uniqueId
        voucherRecoveryGuards.begin(playerId)
        Bukkit.getScheduler().runTaskLater(pluginValue, Runnable { reconcile(playerId) }, 1L)
    }

    @EventHandler
    fun onVoucherQuit(event: PlayerQuitEvent) {
        val actorId = event.player.uniqueId
        val voucherId = pendingRedeemVouchers.remove(actorId)
        if (voucherId != null) {
            offlineRedeemHolds[actorId] = voucherId
        }
        voucherRecoveryGuards.complete(actorId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherHeldChange(event: PlayerItemHeldEvent) {
        if (isRecoveryGuarded(event.player.uniqueId) || currentRedeemHold(event.player.uniqueId) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherInventoryClick(event: InventoryClickEvent) {
        var hotbar: ItemStack? = null
        var offhand: ItemStack? = null
        val player = event.whoClicked as? Player
        if (player != null) {
            if (isRecoveryGuarded(player.uniqueId)) {
                event.isCancelled = true
                return
            }
            hotbar = if (event.click == ClickType.NUMBER_KEY) {
                player.inventory.getItem(event.hotbarButton)
            } else {
                null
            }
            offhand = player.inventory.itemInOffHand
        }
        if (ReceiptTransferPolicy.containsTagged(
                ::isTransferProtected,
                event.currentItem,
                event.cursor,
                hotbar,
                offhand,
            )
        ) {
            event.isCancelled = true
            return
        }
        if (isVoucherInsertIntoForbiddenInventory(event, hotbar, offhand)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player
        if (player != null && isRecoveryGuarded(player.uniqueId)) {
            event.isCancelled = true
            return
        }
        if (isTransferProtected(event.oldCursor) ||
            event.newItems.values.any { isTransferProtected(it) } ||
            (isForbiddenVoucherInventory(event.view.topInventory.type) &&
                isVoucher(event.oldCursor) &&
                event.rawSlots.any { rawSlot -> rawSlot < event.view.topInventory.size })
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherInventoryMove(event: InventoryMoveItemEvent) {
        if (isTransferProtected(event.item) ||
            isRecoveryInventory(event.source) || isRecoveryInventory(event.destination)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherInventoryPickup(event: InventoryPickupItemEvent) {
        if (isTransferProtected(event.item.itemStack) || isRecoveryInventory(event.inventory)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherEntityPickup(event: EntityPickupItemEvent) {
        if (isTransferProtected(event.item.itemStack) ||
            (event.entity is Player && isRecoveryGuarded((event.entity as Player).uniqueId))
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherDrop(event: PlayerDropItemEvent) {
        if (isRecoveryGuarded(event.player.uniqueId) ||
            isTransferProtected(event.itemDrop.itemStack)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherCraft(event: CraftItemEvent) {
        if (event.inventory.matrix.any { isVoucher(it) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherCrafter(event: CrafterCraftEvent) {
        val crafter = event.block.state as? Crafter ?: return
        if (crafter.inventory.contents.any { isVoucher(it) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherSmith(event: SmithItemEvent) {
        if (event.inventory.contents.any { isVoucher(it) } ||
            isVoucher(event.cursor) || isVoucher(event.currentItem)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherPrepareSmithing(event: PrepareSmithingEvent) {
        if (event.inventory.contents.any { isVoucher(it) }) {
            event.result = null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherPrepareAnvil(event: PrepareAnvilEvent) {
        if (event.inventory.contents.any { isVoucher(it) }) {
            event.result = null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherPrepareGrindstone(event: PrepareGrindstoneEvent) {
        if (event.inventory.contents.any { isVoucher(it) }) {
            event.result = null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherPlace(event: BlockPlaceEvent) {
        if (isVoucher(event.itemInHand)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherDispense(event: BlockDispenseEvent) {
        if (isVoucher(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherConsume(event: PlayerItemConsumeEvent) {
        if (isVoucher(event.item)) {
            event.isCancelled = true
        }
    }

    /** Runs after the core-specific handler so a valid core click is not shadowed by this guard. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVoucherInteract(event: PlayerInteractEvent) {
        if (isVoucher(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherInteractEntity(event: PlayerInteractEntityEvent) {
        val handIsVoucher = isVoucher(event.player.inventory.getItem(event.hand))
        val entityContainsVoucher = event.rightClicked is ItemFrame &&
            isVoucher((event.rightClicked as ItemFrame).item)
        if (isRecoveryGuarded(event.player.uniqueId) ||
            VoucherEntityPolicy.blocksInteraction(handIsVoucher, entityContainsVoucher)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        onVoucherInteractEntity(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (isRecoveryGuarded(event.player.uniqueId) ||
            isVoucher(event.playerItem) || isVoucher(event.armorStandItem)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherHangingBreak(event: HangingBreakEvent) {
        if (event.entity is ItemFrame &&
            VoucherEntityPolicy.blocksHangingBreak(isVoucher((event.entity as ItemFrame).item))
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherSwapHands(event: PlayerSwapHandItemsEvent) {
        if (isRecoveryGuarded(event.player.uniqueId) ||
            isTransferProtected(event.mainHandItem) || isTransferProtected(event.offHandItem)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherDeath(event: PlayerDeathEvent) {
        val iterator = event.drops.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if ((isRecoveryGuarded(event.entity.uniqueId) && isVoucher(item)) ||
                isTransferProtected(item)
            ) {
                event.itemsToKeep.add(item.clone())
                iterator.remove()
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherMerge(event: ItemMergeEvent) {
        if (isVoucher(event.entity.itemStack) || isVoucher(event.target.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherDespawn(event: ItemDespawnEvent) {
        if (isVoucher(event.entity.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherDamage(event: EntityDamageEvent) {
        if (event.entity is Item && isVoucher((event.entity as Item).itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherPortal(event: EntityPortalEvent) {
        if (event.entity is Item && isVoucher((event.entity as Item).itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherTeleport(event: EntityTeleportEvent) {
        if (event.entity is Item && isVoucher((event.entity as Item).itemStack)) {
            event.isCancelled = true
        }
    }

    private fun openVault(player: Player, coreId: UUID) {
        val playerId = player.uniqueId
        val canWithdraw = !sessionsValue.hasActiveSession()
        databaseExecutorValue.submit {
            val core = repositoryValue.findCore(coreId).orElseThrow {
                IllegalStateException("コアが見つかりません")
            }
            val team = repositoryValue.findTeam(core.teamId()).orElseThrow {
                IllegalStateException("コアのチームが見つかりません")
            }
            if (!team.members().contains(playerId)) {
                throw IllegalStateException("このコアへアクセスできるチームメンバーではありません")
            }
            VaultData(
                resourcesValue.load(team.id(), playerId),
                team.ownerId().equals(playerId),
                canWithdraw,
            )
        }.whenComplete { data, failure ->
            runOnMainThread {
                val current = onlinePlayer(playerId) ?: return@runOnMainThread
                if (failure != null) {
                    current.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED))
                    return@runOnMainThread
                }
                val value = Objects.requireNonNull(data, "data")
                current.openInventory(
                    ResourceVaultGui.create(
                        coreId,
                        value.snapshot,
                        value.owner,
                        value.canWithdraw,
                    ),
                )
            }
        }
    }

    private fun beginWithdrawal(
        player: Player,
        coreId: UUID,
        resourceType: ResourceType,
        requestedQuantity: Long,
    ) {
        val actorId = player.uniqueId
        player.closeInventory()
        player.sendMessage(Component.text("携帯ポイント証票を発行しています…", NamedTextColor.GRAY))
        databaseExecutorValue.submit {
            val core = repositoryValue.findCore(coreId).orElseThrow {
                IllegalStateException("コアが見つかりません")
            }
            val team = repositoryValue.findTeam(core.teamId()).orElseThrow {
                IllegalStateException("コアのチームが見つかりません")
            }
            if (!team.ownerId().equals(actorId)) {
                throw IllegalStateException("証票の引き出しはチームオーナーのみ実行できます")
            }
            val quantity = if (requestedQuantity > 0L) {
                requestedQuantity
            } else {
                resourcesValue.load(team.id(), actorId).balance(resourceType)
            }
            if (quantity <= 0L) {
                throw IllegalStateException("引き出せる残高がありません")
            }
            vouchersValue.withdraw(
                team.id(),
                actorId,
                resourceType,
                quantity,
                UUID.randomUUID(),
                Instant.now(),
            )
        }.whenComplete { result, failure ->
            runOnMainThread {
                val current = onlinePlayer(actorId)
                if (failure != null) {
                    if (current != null) {
                        current.sendMessage(
                            Component.text(
                                "証票を発行できません: ${rootMessage(failure)}",
                                NamedTextColor.RED,
                            ),
                        )
                    }
                    return@runOnMainThread
                }
                if (current != null) {
                    val value = Objects.requireNonNull(result, "result")
                    deliver(current, value.voucher)
                }
            }
        }
    }

    private fun deliver(player: Player, voucher: ResourceVoucher) {
        val recipientId = player.uniqueId
        val operationId = deterministic(voucher.voucherId(), "DELIVERY")
        databaseExecutorValue.submit {
            vouchersValue.prepareDelivery(
                voucher.voucherId(),
                recipientId,
                operationId,
                Instant.now(),
            )
        }.whenComplete { prepared, failure ->
            runOnMainThread {
                val current = onlinePlayer(recipientId)
                if (failure != null) {
                    if (current != null) {
                        current.sendMessage(
                            Component.text(
                                "証票の配送準備に失敗しました: ${rootMessage(failure)}",
                                NamedTextColor.RED,
                            ),
                        )
                    }
                    return@runOnMainThread
                }
                if (current != null) {
                    continueDelivery(current, Objects.requireNonNull(prepared, "prepared"))
                }
            }
        }
    }

    private fun continueDelivery(player: Player, prepared: VoucherDeliveryResult) {
        val voucher = prepared.voucher()
        val operation = prepared.operation()
        if (!player.isOnline) {
            return
        }
        if (voucher.state() == ResourceVoucherState.REDEEMED ||
            voucher.state() == ResourceVoucherState.VOIDED ||
            prepared.outcome() == VoucherDeliveryOutcome.VOIDED
        ) {
            invalidateVoucherCopies(player, voucher)
            return
        }
        if (operation == null || operation.state() == VoucherDeliveryState.APPLIED ||
            prepared.outcome() == VoucherDeliveryOutcome.ALREADY_AVAILABLE
        ) {
            normalizeDeliveredVoucher(player, voucher, operation?.deliveryOperationId())
            return
        }
        val operationValue = operation
        val existing = findCanonicalVoucherSlot(
            player,
            voucher,
            operationValue.deliveryOperationId(),
            null,
        )
        if (existing < 0) {
            if (player.inventory.firstEmpty() < 0) {
                player.sendMessage(Component.text("インベントリに空きがないため、証票を保留しました。", NamedTextColor.YELLOW))
                return
            }
            val receipt = taggerValue.tagDelivery(
                taggerValue.create(voucher),
                operationValue.deliveryOperationId(),
            )
            val leftovers = player.inventory.addItem(receipt)
            if (leftovers.isNotEmpty()) {
                player.sendMessage(Component.text("証票を安全に配置できないため、発行を保留しました。", NamedTextColor.YELLOW))
                return
            }
        }
        databaseExecutorValue.submit {
            vouchersValue.applyDelivery(
                voucher.voucherId(),
                operationValue.deliveryOperationId(),
                Instant.now(),
            )
        }.whenComplete { _, failure ->
            runOnMainThread {
                val current = onlinePlayer(player.uniqueId)
                if (failure != null) {
                    if (current != null) {
                        current.sendMessage(
                            Component.text(
                                "証票配送を復旧待ちにしました: ${rootMessage(failure)}",
                                NamedTextColor.YELLOW,
                            ),
                        )
                    }
                    return@runOnMainThread
                }
                if (current != null) {
                    normalizeDeliveredVoucher(current, voucher, operationValue.deliveryOperationId())
                    current.sendMessage(Component.text("携帯ポイント証票を受け取りました。", NamedTextColor.GREEN))
                }
            }
        }
    }

    private fun beginRedeem(player: Player, coreId: UUID, data: ResourceVoucherItemData) {
        val actorId = player.uniqueId
        val existingHold = currentRedeemHold(actorId)
        if (existingHold != null) {
            player.sendMessage(Component.text("別の証票の預け入れ処理が進行中です。", NamedTextColor.YELLOW))
            return
        }
        pendingRedeemVouchers[actorId] = data.voucherId
        val operationId = UUID.randomUUID()
        player.sendMessage(Component.text("携帯ポイント証票を預け入れています…", NamedTextColor.GRAY))
        databaseExecutorValue.submit {
            val core = repositoryValue.findCore(coreId).orElseThrow {
                IllegalStateException("コアが見つかりません")
            }
            val team = repositoryValue.findTeam(core.teamId()).orElseThrow {
                IllegalStateException("コアのチームが見つかりません")
            }
            if (!team.members().contains(actorId)) {
                throw IllegalStateException("このチームのメンバーではありません")
            }
            val voucher = vouchersValue.findVoucher(data.voucherId).orElseThrow {
                IllegalStateException("証票のDB記録が見つかりません")
            }
            if (!voucher.teamId().equals(team.id()) ||
                voucher.resourceType() != data.resourceType ||
                voucher.quantity() != data.quantity
            ) {
                throw IllegalStateException("証票の内容がDB記録と一致しません")
            }
            vouchersValue.prepareRedeem(voucher.voucherId(), actorId, operationId, Instant.now())
        }.whenComplete { prepared, failure ->
            runOnMainThread {
                val current = onlinePlayer(actorId)
                if (failure != null) {
                    clearRedeemHold(actorId, data.voucherId)
                    if (current != null) {
                        current.sendMessage(
                            Component.text(
                                "証票を預け入れできません: ${rootMessage(failure)}",
                                NamedTextColor.RED,
                            ),
                        )
                    }
                    return@runOnMainThread
                }
                if (current != null) {
                    continueRedeem(current, Objects.requireNonNull(prepared, "prepared"))
                } else {
                    preserveRedeemHoldOffline(actorId, data.voucherId)
                }
            }
        }
    }

    private fun continueRedeem(player: Player, prepared: VoucherRedeemResult) {
        val operation = prepared.operation()
        val voucher = prepared.voucher()
        if (!player.isOnline) {
            return
        }
        if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
            clearRedeemHold(player.uniqueId, voucher.voucherId())
            if (VoucherReceiptRecoveryPolicy.stripsRolledBackReceipt(operation.state(), voucher.state())) {
                stripMatchingRedeemReceipts(player, voucher, operation.operationId())
            } else {
                invalidateVoucherCopies(player, voucher)
            }
            player.sendMessage(Component.text("この証票の預け入れ操作は取り消し済みです。", NamedTextColor.YELLOW))
            return
        }
        if (operation.state() == VoucherRedeemState.APPLIED ||
            prepared.outcome() == OperationOutcome.ALREADY_APPLIED
        ) {
            clearRedeemHold(player.uniqueId, voucher.voucherId())
            removeVoucherCopies(player, voucher)
            return
        }
        if (!activateRedeemHold(player.uniqueId, voucher.voucherId())) {
            player.sendMessage(Component.text("別の証票の預け入れ処理が進行中です。", NamedTextColor.YELLOW))
            return
        }
        val held = player.inventory.itemInMainHand
        if (!taggerValue.matchesCanonical(held, voucher)) {
            player.sendMessage(Component.text("証票がメインハンドから移動したため、預け入れを保留しました。", NamedTextColor.YELLOW))
            return
        }
        player.inventory.setItemInMainHand(taggerValue.tagRedeem(held, operation.operationId()))
        databaseExecutorValue.submit {
            vouchersValue.applyRedeem(operation.operationId(), Instant.now())
        }.whenComplete { _, failure ->
            runOnMainThread {
                val actorId = player.uniqueId
                clearRedeemHold(actorId, voucher.voucherId())
                val current = onlinePlayer(actorId) ?: return@runOnMainThread
                if (failure != null) {
                    current.sendMessage(
                        Component.text("預け入れを復旧待ちにしました: ${rootMessage(failure)}", NamedTextColor.YELLOW),
                    )
                    return@runOnMainThread
                }
                removeVoucherCopies(current, voucher)
                current.sendMessage(
                    Component.text(voucher.quantity().toString() + "Pをコア資源庫へ預け入れました。", NamedTextColor.GREEN),
                )
            }
        }
    }

    private fun reconcile(actorId: UUID) {
        if (onlinePlayer(actorId) == null) {
            return
        }
        val heldVoucherIds = java.util.List.copyOf(
            offlineRedeemHolds.entries
                .filter { entry -> entry.key == actorId }
                .map { entry -> entry.value },
        )
        databaseExecutorValue.submit {
            val deliveries = ArrayList<DeliveryRecovery>()
            for (operation in vouchersValue.loadOpenDeliveryOperations(actorId)) {
                vouchersValue.findVoucher(operation.voucherId()).ifPresent { voucher ->
                    deliveries.add(DeliveryRecovery(voucher, operation))
                }
            }
            val redeems = ArrayList<RedeemRecovery>()
            for (operation in vouchersValue.loadRedeemsForRecovery(actorId)) {
                vouchersValue.findVoucher(operation.voucherId()).ifPresent { voucher ->
                    redeems.add(RedeemRecovery(voucher, operation))
                }
            }
            val heldVouchers = ArrayList<ResourceVoucher>()
            for (voucherId in heldVoucherIds) {
                vouchersValue.findVoucher(voucherId).ifPresent { held -> heldVouchers.add(held) }
            }
            RecoveryData(
                vouchersValue.loadPendingDeliveries(actorId),
                deliveries,
                redeems,
                heldVouchers,
            )
        }.whenComplete { recovery, failure ->
            runOnMainThread {
                val current = onlinePlayer(actorId)
                if (failure != null) {
                    if (current != null) {
                        current.sendMessage(
                            Component.text(
                                "証票の復旧確認を再試行しています: ${rootMessage(failure)}",
                                NamedTextColor.YELLOW,
                            ),
                        )
                        Bukkit.getScheduler().runTaskLater(
                            pluginValue,
                            Runnable { reconcile(actorId) },
                            20L,
                        )
                    }
                    return@runOnMainThread
                }
                if (current == null) {
                    return@runOnMainThread
                }
                val recoveryValue = Objects.requireNonNull(recovery, "recovery")
                try {
                    for (held in recoveryValue.heldVouchers) {
                        val hasOpenOperation = recoveryValue.redeems.any { redeem ->
                            redeem.voucher.voucherId().equals(held.voucherId())
                        }
                        if (!hasOpenOperation && held.state() != ResourceVoucherState.RESERVED) {
                            clearRedeemHold(actorId, held.voucherId())
                            if (held.state() == ResourceVoucherState.REDEEMED ||
                                held.state() == ResourceVoucherState.VOIDED
                            ) {
                                invalidateVoucherCopies(current, held)
                            }
                        }
                    }
                    for (delivery in recoveryValue.deliveryOperations) {
                        val operation = delivery.operation
                        continueDelivery(
                            current,
                            VoucherDeliveryResult(
                                if (operation.state() == VoucherDeliveryState.APPLIED) {
                                    VoucherDeliveryOutcome.ALREADY_AVAILABLE
                                } else {
                                    VoucherDeliveryOutcome.ALREADY_PREPARED
                                },
                                delivery.voucher,
                                operation,
                            ),
                        )
                    }
                    for (voucher in recoveryValue.pending) {
                        if (recoveryValue.deliveryOperations.none { delivery ->
                                delivery.voucher.voucherId().equals(voucher.voucherId())
                            }
                        ) {
                            deliver(current, voucher)
                        }
                    }
                    for (redeem in recoveryValue.redeems) {
                        reconcileRedeem(current, redeem.voucher, redeem.operation)
                    }
                } finally {
                    voucherRecoveryGuards.complete(actorId)
                }
            }
        }
    }

    private fun reconcileRedeem(
        player: Player,
        voucher: ResourceVoucher,
        operation: VoucherRedeemOperation,
    ) {
        if (operation.state() == VoucherRedeemState.APPLIED ||
            voucher.state() == ResourceVoucherState.REDEEMED
        ) {
            clearRedeemHold(player.uniqueId, voucher.voucherId())
            removeVoucherCopies(player, voucher)
            return
        }
        if (voucher.state() == ResourceVoucherState.VOIDED) {
            clearRedeemHold(player.uniqueId, voucher.voucherId())
            invalidateVoucherCopies(player, voucher)
            return
        }
        if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
            clearRedeemHold(player.uniqueId, voucher.voucherId())
            stripMatchingRedeemReceipts(player, voucher, operation.operationId())
            return
        }
        val slot = findCanonicalVoucherSlot(player, voucher, null, operation.operationId())
        if (slot < 0) {
            player.sendMessage(Component.text("預け入れ途中の証票が見つからないため、監査保留にしました。", NamedTextColor.YELLOW))
            return
        }
        val actorId = player.uniqueId
        if (!activateRedeemHold(actorId, voucher.voucherId())) {
            player.sendMessage(Component.text("別の証票の預け入れ処理が進行中です。", NamedTextColor.YELLOW))
            return
        }
        val item = player.inventory.getItem(slot)
        if (!taggerValue.isRedeemReceipt(item)) {
            player.inventory.setItem(
                slot,
                taggerValue.tagRedeem(item!!, operation.operationId()),
            )
        }
        databaseExecutorValue.submit {
            vouchersValue.applyRedeem(operation.operationId(), Instant.now())
        }.whenComplete { _, failure ->
            runOnMainThread {
                clearRedeemHold(actorId, voucher.voucherId())
                val current = onlinePlayer(actorId) ?: return@runOnMainThread
                if (failure == null) {
                    removeVoucherCopies(current, voucher)
                }
            }
        }
    }

    private fun findCanonicalVoucherSlot(
        player: Player,
        voucher: ResourceVoucher,
        deliveryOperationId: UUID?,
        redeemOperationId: UUID?,
    ): Int {
        val contents = player.inventory.contents
        for (slot in contents.indices) {
            val item = contents[slot]
            if (!taggerValue.matchesCanonical(item, voucher)) {
                continue
            }
            val data = taggerValue.read(item).orElseThrow()
            if (data.deliveryOperationId.isPresent &&
                !data.deliveryOperationId.filter { id -> id == deliveryOperationId }.isPresent
            ) {
                continue
            }
            if (data.redeemOperationId.isPresent &&
                !data.redeemOperationId.filter { id -> id == redeemOperationId }.isPresent
            ) {
                continue
            }
            return slot
        }
        return -1
    }

    private fun normalizeDeliveredVoucher(
        player: Player,
        voucher: ResourceVoucher,
        operationId: UUID?,
    ) {
        if (voucher.state() == ResourceVoucherState.REDEEMED ||
            voucher.state() == ResourceVoucherState.VOIDED
        ) {
            invalidateVoucherCopies(player, voucher)
            return
        }
        var keepSlot = -1
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot)
            if (!taggerValue.matchesCanonical(item, voucher)) {
                continue
            }
            val data = taggerValue.read(item).orElseThrow()
            if (operationId != null && data.deliveryOperationId.filter { id -> operationId == id }.isPresent) {
                keepSlot = slot
                break
            }
            if (keepSlot < 0 && data.deliveryOperationId.isEmpty && data.redeemOperationId.isEmpty) {
                keepSlot = slot
            }
        }
        if (keepSlot < 0) {
            return
        }
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot)
            if (!taggerValue.matchesCanonical(item, voucher)) {
                continue
            }
            if (slot == keepSlot) {
                player.inventory.setItem(
                    slot,
                    taggerValue.stripReceipts(item!!),
                )
            } else {
                player.inventory.setItem(slot, null)
            }
        }
    }

    private fun removeVoucherCopies(player: Player, voucher: ResourceVoucher) {
        invalidateVoucherCopies(player, voucher)
    }

    /** Removes only a matching rolled-back redeem receipt and keeps the available voucher. */
    private fun stripMatchingRedeemReceipts(
        player: Player,
        voucher: ResourceVoucher,
        operationId: UUID,
    ) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot)
            if (matchesRedeemReceipt(item, voucher, operationId)) {
                inventory.setItem(
                    slot,
                    taggerValue.stripRedeemReceipt(item!!, operationId),
                )
            }
        }
        val armor = inventory.armorContents
        var armorChanged = false
        for (slot in armor.indices) {
            if (matchesRedeemReceipt(armor[slot], voucher, operationId)) {
                armor[slot] = taggerValue.stripRedeemReceipt(
                    armor[slot]!!,
                    operationId,
                )
                armorChanged = true
            }
        }
        if (armorChanged) {
            inventory.armorContents = armor
        }
        val extra = inventory.extraContents
        var extraChanged = false
        for (slot in extra.indices) {
            if (matchesRedeemReceipt(extra[slot], voucher, operationId)) {
                extra[slot] = taggerValue.stripRedeemReceipt(
                    extra[slot]!!,
                    operationId,
                )
                extraChanged = true
            }
        }
        if (extraChanged) {
            inventory.extraContents = extra
        }
        if (matchesRedeemReceipt(inventory.itemInOffHand, voucher, operationId)) {
            inventory.setItemInOffHand(
                taggerValue.stripRedeemReceipt(
                    Objects.requireNonNull(inventory.itemInOffHand, "item"),
                    operationId,
                ),
            )
        }
        if (matchesRedeemReceipt(player.itemOnCursor, voucher, operationId)) {
            player.setItemOnCursor(
                taggerValue.stripRedeemReceipt(
                    Objects.requireNonNull(player.itemOnCursor, "item"),
                    operationId,
                ),
            )
        }
    }

    private fun matchesRedeemReceipt(
        item: ItemStack?,
        voucher: ResourceVoucher,
        operationId: UUID,
    ): Boolean = VoucherReceiptRecoveryPolicy.isMatchingRedeemReceipt(
        taggerValue.read(item).orElse(null),
        voucher.voucherId(),
        operationId,
    )

    /** Removes every parseable physical copy by voucher UUID, including amount>1 duplicates. */
    private fun invalidateVoucherCopies(player: Player, voucher: ResourceVoucher) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            if (taggerValue.isFor(inventory.getItem(slot), voucher.voucherId())) {
                inventory.setItem(slot, null)
            }
        }
        val armor = inventory.armorContents
        var armorChanged = false
        for (slot in armor.indices) {
            if (taggerValue.isFor(armor[slot], voucher.voucherId())) {
                armor[slot] = null
                armorChanged = true
            }
        }
        if (armorChanged) {
            inventory.armorContents = armor
        }
        val extra = inventory.extraContents
        var extraChanged = false
        for (slot in extra.indices) {
            if (taggerValue.isFor(extra[slot], voucher.voucherId())) {
                extra[slot] = null
                extraChanged = true
            }
        }
        if (extraChanged) {
            inventory.extraContents = extra
        }
        if (taggerValue.isFor(inventory.itemInOffHand, voucher.voucherId())) {
            inventory.setItemInOffHand(null)
        }
    }

    private fun isVoucher(item: ItemStack?): Boolean = taggerValue.isVoucher(item)

    private fun isReceipt(item: ItemStack?): Boolean =
        taggerValue.isDeliveryReceipt(item) || taggerValue.isRedeemReceipt(item)

    private fun isTransferProtected(item: ItemStack?): Boolean {
        if (isReceipt(item)) {
            return true
        }
        return taggerValue.read(item)
            .filter { data -> data.redeemOperationId.isEmpty }
            .map { data -> hasRedeemHold(data.voucherId) }
            .orElse(false)
    }

    private fun currentRedeemHold(actorId: UUID): UUID? =
        pendingRedeemVouchers[actorId] ?: offlineRedeemHolds[actorId]

    private fun hasRedeemHold(voucherId: UUID): Boolean =
        pendingRedeemVouchers.containsValue(voucherId) || offlineRedeemHolds.containsValue(voucherId)

    private fun activateRedeemHold(actorId: UUID, voucherId: UUID): Boolean {
        val existing = currentRedeemHold(actorId)
        if (existing != null && existing != voucherId) {
            return false
        }
        offlineRedeemHolds.remove(actorId, voucherId)
        pendingRedeemVouchers[actorId] = voucherId
        return true
    }

    private fun preserveRedeemHoldOffline(actorId: UUID, voucherId: UUID) {
        val pending = pendingRedeemVouchers.remove(actorId)
        if (pending == null || pending == voucherId) {
            offlineRedeemHolds[actorId] = voucherId
        }
    }

    private fun clearRedeemHold(actorId: UUID, voucherId: UUID) {
        pendingRedeemVouchers.remove(actorId, voucherId)
        offlineRedeemHolds.remove(actorId, voucherId)
    }

    private fun isRecoveryGuarded(playerId: UUID): Boolean = voucherRecoveryGuards.isGuarded(playerId)

    private fun isRecoveryInventory(inventory: Inventory): Boolean =
        inventory.holder is Player && isRecoveryGuarded((inventory.holder as Player).uniqueId)

    private fun isVoucherInsertIntoForbiddenInventory(
        event: InventoryClickEvent,
        hotbar: ItemStack?,
        offhand: ItemStack?,
    ): Boolean {
        if (!isForbiddenVoucherInventory(event.view.topInventory.type)) {
            return false
        }
        val topSize = event.view.topInventory.size
        val topTarget = event.rawSlot >= 0 && event.rawSlot < topSize
        val shiftClick = event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT
        return VoucherContainerPolicy.blocksPlainVoucherInsertion(
            true,
            topTarget,
            shiftClick,
            isVoucher(event.cursor),
            event.click == ClickType.NUMBER_KEY && isVoucher(hotbar),
            event.click == ClickType.SWAP_OFFHAND && isVoucher(offhand),
            isVoucher(event.currentItem),
        )
    }

    private fun withdrawalRequest(rawSlot: Int): WithdrawalRequest? = when (rawSlot) {
        ResourceVaultGui.DEFENSE_TEN_SLOT -> WithdrawalRequest(ResourceType.DEFENSE_POINTS, 10L)
        ResourceVaultGui.DEFENSE_HUNDRED_SLOT -> WithdrawalRequest(ResourceType.DEFENSE_POINTS, 100L)
        ResourceVaultGui.DEFENSE_ALL_SLOT -> WithdrawalRequest(ResourceType.DEFENSE_POINTS, -1L)
        ResourceVaultGui.ENHANCEMENT_ONE_SLOT -> WithdrawalRequest(ResourceType.ENHANCEMENT_POINTS, 1L)
        ResourceVaultGui.ENHANCEMENT_TEN_SLOT -> WithdrawalRequest(ResourceType.ENHANCEMENT_POINTS, 10L)
        ResourceVaultGui.ENHANCEMENT_ALL_SLOT -> WithdrawalRequest(ResourceType.ENHANCEMENT_POINTS, -1L)
        else -> null
    }

    private fun runOnMainThread(action: Runnable) {
        if (pluginValue.isEnabled) {
            Bukkit.getScheduler().runTask(pluginValue, action)
        }
    }

    private companion object {
        @JvmStatic
        private fun onlinePlayer(playerId: UUID): Player? {
            val player = Bukkit.getPlayer(playerId)
            return if (player != null && player.isOnline) player else null
        }

        @JvmStatic
        private fun deterministic(base: UUID, namespace: String): UUID =
            UUID.nameUUIDFromBytes(
                (base.toString() + "|" + namespace).toByteArray(StandardCharsets.UTF_8),
            )

        @JvmStatic
        private fun isForbiddenVoucherInventory(type: InventoryType): Boolean =
            type == InventoryType.ANVIL || type == InventoryType.GRINDSTONE || type == InventoryType.SMITHING

        @JvmStatic
        private fun rootMessage(failure: Throwable): String {
            var root: Throwable = if (failure is CompletionException && failure.cause != null) {
                failure.cause!!
            } else {
                failure
            }
            while (root.cause != null) {
                root = root.cause!!
            }
            return root.message ?: root.javaClass.simpleName
        }
    }

    private data class WithdrawalRequest(
        val resourceType: ResourceType,
        val quantity: Long,
    )

    private data class VaultData(
        val snapshot: TeamResourceSnapshot,
        val owner: Boolean,
        val canWithdraw: Boolean,
    )

    private data class RecoveryData(
        val pending: List<ResourceVoucher>,
        val deliveryOperations: List<DeliveryRecovery>,
        val redeems: List<RedeemRecovery>,
        val heldVouchers: List<ResourceVoucher>,
    )

    private data class DeliveryRecovery(
        val voucher: ResourceVoucher,
        val operation: VoucherDeliveryOperation,
    )

    private data class RedeemRecovery(
        val voucher: ResourceVoucher,
        val operation: VoucherRedeemOperation,
    )
}
