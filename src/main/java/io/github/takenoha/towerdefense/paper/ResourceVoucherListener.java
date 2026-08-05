package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.persistence.ResourceType;
import io.github.takenoha.towerdefense.persistence.ResourceVoucher;
import io.github.takenoha.towerdefense.persistence.ResourceVoucherRepository;
import io.github.takenoha.towerdefense.persistence.ResourceVoucherState;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryOperation;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryOutcome;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryResult;
import io.github.takenoha.towerdefense.persistence.VoucherDeliveryState;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemOperation;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemResult;
import io.github.takenoha.towerdefense.persistence.VoucherRedeemState;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper bridge for withdrawing, delivering, and redeeming team-bound point vouchers. */
public final class ResourceVoucherListener implements Listener {
    private final JavaPlugin plugin;
    private final DefenseRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final DefenseSessionManager sessions;
    private final CoreRegistry cores;
    private final ResourceRepository resources;
    private final ResourceVoucherRepository vouchers;
    private final ResourceVoucherTagger tagger;
    /** Main-thread source binding until a RESERVED voucher receives its redeem receipt. */
    private final Map<UUID, UUID> pendingRedeemVouchers = new HashMap<>();
    /** Keeps the source binding across quit/rejoin until the DB operation is reconciled. */
    private final Map<UUID, UUID> offlineRedeemHolds = new HashMap<>();
    /** Blocks player inventory actions while join/restart recovery is still reading the DB. */
    private final PlayerRecoveryGuard voucherRecoveryGuards = new PlayerRecoveryGuard();

    public ResourceVoucherListener(
            JavaPlugin plugin,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            ResourceRepository resources,
            ResourceVoucherRepository vouchers,
            ResourceVoucherTagger tagger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.vouchers = Objects.requireNonNull(vouchers, "vouchers");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
    }

    /** The core-specific path must run before the generic cancellation guard. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVoucherCoreInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ResourceVoucherItemData data = tagger.read(event.getItem()).orElse(null);
        if (data == null) {
            return;
        }
        if (isRecoveryGuarded(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (data.hasReceipt()) {
            event.getPlayer().sendMessage(Component.text(
                    "処理中の証票は移動・預け入れできません。", NamedTextColor.YELLOW));
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Optional<CoreRecord> core = cores.at(clicked);
        if (core.isPresent()) {
            openVault(event.getPlayer(), core.orElseThrow().id());
        } else {
            event.getPlayer().sendMessage(Component.text(
                    "証票は発行元チームの登録コアへ預け入れてください。", NamedTextColor.YELLOW));
        }
    }

    /** CoreManagementListener owns cancellation of the vault inventory; this handler still sees it. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onVaultClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof ResourceVaultInventoryHolder holder)
                || !(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (isRecoveryGuarded(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlot() == ResourceVaultGui.CLOSE_SLOT) {
            return;
        }
        ResourceType redeemType = event.getRawSlot() == ResourceVaultGui.DEFENSE_SLOT
                ? ResourceType.DEFENSE_POINTS
                : event.getRawSlot() == ResourceVaultGui.ENHANCEMENT_SLOT
                        ? ResourceType.ENHANCEMENT_POINTS
                        : null;
        if (redeemType != null) {
            ItemStack held = player.getInventory().getItemInMainHand();
            ResourceVoucherItemData data = tagger.read(held).orElse(null);
            if (data == null || data.hasReceipt() || data.resourceType() != redeemType
                    || held.getAmount() != 1 || held.getMaxStackSize() != 1) {
                player.sendMessage(Component.text(
                        redeemType.displayName() + "の証票をメインハンドに持ってください。",
                        NamedTextColor.YELLOW));
                return;
            }
            beginRedeem(player, holder.coreId(), data);
            return;
        }
        WithdrawalRequest request = withdrawalRequest(event.getRawSlot());
        if (request != null) {
            beginWithdrawal(player, holder.coreId(), request.resourceType(), request.quantity());
        }
    }

    @EventHandler
    public void onVoucherJoin(PlayerJoinEvent event) {
        voucherRecoveryGuards.begin(event.getPlayer().getUniqueId());
        reconcile(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onVoucherRespawn(PlayerRespawnEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        voucherRecoveryGuards.begin(playerId);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reconcile(playerId);
        }, 1L);
    }

    @EventHandler
    public void onVoucherQuit(PlayerQuitEvent event) {
        UUID actorId = event.getPlayer().getUniqueId();
        UUID voucherId = pendingRedeemVouchers.remove(actorId);
        if (voucherId != null) {
            offlineRedeemHolds.put(actorId, voucherId);
        }
        voucherRecoveryGuards.complete(actorId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherHeldChange(PlayerItemHeldEvent event) {
        if (isRecoveryGuarded(event.getPlayer().getUniqueId())
                || currentRedeemHold(event.getPlayer().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherInventoryClick(InventoryClickEvent event) {
        ItemStack hotbar = null;
        ItemStack offhand = null;
        if (event.getWhoClicked() instanceof Player player) {
            if (isRecoveryGuarded(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            hotbar = event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                    ? player.getInventory().getItem(event.getHotbarButton()) : null;
            offhand = player.getInventory().getItemInOffHand();
        }
        if (ReceiptTransferPolicy.containsTagged(
                this::isTransferProtected,
                event.getCurrentItem(),
                event.getCursor(),
                hotbar,
                offhand)) {
            event.setCancelled(true);
            return;
        }
        if (isVoucherInsertIntoForbiddenInventory(event, hotbar, offhand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && isRecoveryGuarded(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (isTransferProtected(event.getOldCursor())
                || event.getNewItems().values().stream().anyMatch(this::isTransferProtected)
                || (isForbiddenVoucherInventory(event.getView().getTopInventory().getType())
                        && isVoucher(event.getOldCursor())
                        && event.getRawSlots().stream().anyMatch(
                                rawSlot -> rawSlot < event.getView().getTopInventory().getSize()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherInventoryMove(InventoryMoveItemEvent event) {
        if (isTransferProtected(event.getItem())
                || isRecoveryInventory(event.getSource())
                || isRecoveryInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherInventoryPickup(InventoryPickupItemEvent event) {
        if (isTransferProtected(event.getItem().getItemStack())
                || isRecoveryInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherEntityPickup(EntityPickupItemEvent event) {
        if (isTransferProtected(event.getItem().getItemStack())
                || (event.getEntity() instanceof Player player
                        && isRecoveryGuarded(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherDrop(PlayerDropItemEvent event) {
        if (isRecoveryGuarded(event.getPlayer().getUniqueId())
                || isTransferProtected(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherCraft(CraftItemEvent event) {
        if (Arrays.stream(event.getInventory().getMatrix()).anyMatch(this::isVoucher)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherCrafter(org.bukkit.event.block.CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter
                && Arrays.stream(crafter.getInventory().getContents()).anyMatch(this::isVoucher)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherSmith(SmithItemEvent event) {
        if (Arrays.stream(event.getInventory().getContents()).anyMatch(this::isVoucher)
                || isVoucher(event.getCursor())
                || isVoucher(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherPrepareSmithing(PrepareSmithingEvent event) {
        if (Arrays.stream(event.getInventory().getContents()).anyMatch(this::isVoucher)) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherPrepareAnvil(PrepareAnvilEvent event) {
        if (Arrays.stream(event.getInventory().getContents()).anyMatch(this::isVoucher)) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherPrepareGrindstone(PrepareGrindstoneEvent event) {
        if (Arrays.stream(event.getInventory().getContents()).anyMatch(this::isVoucher)) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherPlace(BlockPlaceEvent event) {
        if (isVoucher(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherDispense(BlockDispenseEvent event) {
        if (isVoucher(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherConsume(PlayerItemConsumeEvent event) {
        if (isVoucher(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** Runs after the core-specific handler so a valid core click is not shadowed by this guard. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVoucherInteract(PlayerInteractEvent event) {
        if (isVoucher(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherInteractEntity(PlayerInteractEntityEvent event) {
        if (isVoucher(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherInteractAtEntity(PlayerInteractAtEntityEvent event) {
        onVoucherInteractEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isVoucher(event.getPlayerItem()) || isVoucher(event.getArmorStandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherSwapHands(PlayerSwapHandItemsEvent event) {
        if (isRecoveryGuarded(event.getPlayer().getUniqueId())
                || isTransferProtected(event.getMainHandItem())
                || isTransferProtected(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherDeath(PlayerDeathEvent event) {
        var iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if ((isRecoveryGuarded(event.getEntity().getUniqueId()) && isVoucher(item))
                    || isTransferProtected(item)) {
                event.getItemsToKeep().add(item.clone());
                iterator.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherMerge(ItemMergeEvent event) {
        if (isVoucher(event.getEntity().getItemStack())
                || isVoucher(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherDespawn(ItemDespawnEvent event) {
        if (isVoucher(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && isVoucher(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof Item item && isVoucher(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoucherTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof Item item && isVoucher(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    private void openVault(Player player, UUID coreId) {
        UUID playerId = player.getUniqueId();
        boolean canWithdraw = !sessions.hasActiveSession();
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.members().contains(playerId)) {
                throw new IllegalStateException("このコアへアクセスできるチームメンバーではありません");
            }
            return new VaultData(
                    resources.load(team.id(), playerId),
                    team.ownerId().equals(playerId),
                    canWithdraw);
        }).whenComplete((data, failure) -> runOnMainThread(() -> {
            Player current = onlinePlayer(playerId);
            if (current == null) {
                return;
            }
            if (failure != null) {
                current.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                return;
            }
            current.openInventory(ResourceVaultGui.create(
                    coreId, data.snapshot(), data.owner(), data.canWithdraw()));
        }));
    }

    private void beginWithdrawal(
            Player player, UUID coreId, ResourceType resourceType, long requestedQuantity) {
        UUID actorId = player.getUniqueId();
        player.closeInventory();
        player.sendMessage(Component.text("携帯ポイント証票を発行しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.ownerId().equals(actorId)) {
                throw new IllegalStateException("証票の引き出しはチームオーナーのみ実行できます");
            }
            long quantity = requestedQuantity > 0L
                    ? requestedQuantity
                    : resources.load(team.id(), actorId).balance(resourceType);
            if (quantity <= 0L) {
                throw new IllegalStateException("引き出せる残高がありません");
            }
            return vouchers.withdraw(
                    team.id(), actorId, resourceType, quantity, UUID.randomUUID(), Instant.now());
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            Player current = onlinePlayer(actorId);
            if (failure != null) {
                if (current != null) {
                    current.sendMessage(Component.text(
                            "証票を発行できません: " + rootMessage(failure), NamedTextColor.RED));
                }
                return;
            }
            if (current != null) {
                deliver(current, result.voucher());
            }
        }));
    }

    private void deliver(Player player, ResourceVoucher voucher) {
        UUID recipientId = player.getUniqueId();
        UUID operationId = deterministic(voucher.voucherId(), "DELIVERY");
        databaseExecutor.submit(() -> vouchers.prepareDelivery(
                        voucher.voucherId(), recipientId, operationId, Instant.now()))
                .whenComplete((prepared, failure) -> runOnMainThread(() -> {
                    Player current = onlinePlayer(recipientId);
                    if (failure != null) {
                        if (current != null) {
                            current.sendMessage(Component.text(
                                    "証票の配送準備に失敗しました: " + rootMessage(failure),
                                    NamedTextColor.RED));
                        }
                        return;
                    }
                    if (current != null) {
                        continueDelivery(current, prepared);
                    }
                }));
    }

    private void continueDelivery(Player player, VoucherDeliveryResult prepared) {
        ResourceVoucher voucher = prepared.voucher();
        VoucherDeliveryOperation operation = prepared.operation();
        if (!player.isOnline()) {
            return;
        }
        if (voucher.state() == ResourceVoucherState.REDEEMED
                || voucher.state() == ResourceVoucherState.VOIDED
                || prepared.outcome() == VoucherDeliveryOutcome.VOIDED) {
            invalidateVoucherCopies(player, voucher);
            return;
        }
        if (operation == null || operation.state() == VoucherDeliveryState.APPLIED
                || prepared.outcome() == VoucherDeliveryOutcome.ALREADY_AVAILABLE) {
            normalizeDeliveredVoucher(player, voucher, operation == null
                    ? null : operation.deliveryOperationId());
            return;
        }
        int existing = findCanonicalVoucherSlot(
                player, voucher, operation.deliveryOperationId(), null);
        if (existing < 0) {
            if (player.getInventory().firstEmpty() < 0) {
                player.sendMessage(Component.text(
                        "インベントリに空きがないため、証票を保留しました。", NamedTextColor.YELLOW));
                return;
            }
            ItemStack receipt = tagger.tagDelivery(tagger.create(voucher), operation.deliveryOperationId());
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(receipt);
            if (!leftovers.isEmpty()) {
                player.sendMessage(Component.text(
                        "証票を安全に配置できないため、発行を保留しました。", NamedTextColor.YELLOW));
                return;
            }
        }
        databaseExecutor.submit(() -> vouchers.applyDelivery(
                        voucher.voucherId(), operation.deliveryOperationId(), Instant.now()))
                .whenComplete((outcome, failure) -> runOnMainThread(() -> {
                    Player current = onlinePlayer(player.getUniqueId());
                    if (failure != null) {
                        if (current != null) {
                            current.sendMessage(Component.text(
                                    "証票配送を復旧待ちにしました: " + rootMessage(failure),
                                    NamedTextColor.YELLOW));
                        }
                        return;
                    }
                    if (current != null) {
                        normalizeDeliveredVoucher(current, voucher, operation.deliveryOperationId());
                        current.sendMessage(Component.text(
                                "携帯ポイント証票を受け取りました。", NamedTextColor.GREEN));
                    }
                }));
    }

    private void beginRedeem(Player player, UUID coreId, ResourceVoucherItemData data) {
        UUID actorId = player.getUniqueId();
        UUID existingHold = currentRedeemHold(actorId);
        if (existingHold != null) {
            player.sendMessage(Component.text(
                    "別の証票の預け入れ処理が進行中です。", NamedTextColor.YELLOW));
            return;
        }
        pendingRedeemVouchers.put(actorId, data.voucherId());
        UUID operationId = UUID.randomUUID();
        player.sendMessage(Component.text("携帯ポイント証票を預け入れています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.members().contains(actorId)) {
                throw new IllegalStateException("このチームのメンバーではありません");
            }
            ResourceVoucher voucher = vouchers.findVoucher(data.voucherId()).orElseThrow(
                    () -> new IllegalStateException("証票のDB記録が見つかりません"));
            if (!voucher.teamId().equals(team.id())
                    || voucher.resourceType() != data.resourceType()
                    || voucher.quantity() != data.quantity()) {
                throw new IllegalStateException("証票の内容がDB記録と一致しません");
            }
            return vouchers.prepareRedeem(voucher.voucherId(), actorId, operationId, Instant.now());
        }).whenComplete((prepared, failure) -> runOnMainThread(() -> {
            Player current = onlinePlayer(actorId);
            if (failure != null) {
                clearRedeemHold(actorId, data.voucherId());
                if (current != null) {
                    current.sendMessage(Component.text(
                            "証票を預け入れできません: " + rootMessage(failure), NamedTextColor.RED));
                }
                return;
            }
            if (current != null) {
                continueRedeem(current, prepared);
            } else {
                preserveRedeemHoldOffline(actorId, data.voucherId());
            }
        }));
    }

    private void continueRedeem(Player player, VoucherRedeemResult prepared) {
        VoucherRedeemOperation operation = prepared.operation();
        ResourceVoucher voucher = prepared.voucher();
        if (!player.isOnline()) {
            return;
        }
        if (operation.state() == VoucherRedeemState.ROLLED_BACK) {
            clearRedeemHold(player.getUniqueId(), voucher.voucherId());
            player.sendMessage(Component.text(
                    "この証票の預け入れ操作は取り消し済みです。", NamedTextColor.YELLOW));
            return;
        }
        if (operation.state() == VoucherRedeemState.APPLIED
                || prepared.outcome() == OperationOutcome.ALREADY_APPLIED) {
            clearRedeemHold(player.getUniqueId(), voucher.voucherId());
            removeVoucherCopies(player, voucher);
            return;
        }
        if (!activateRedeemHold(player.getUniqueId(), voucher.voucherId())) {
            player.sendMessage(Component.text(
                    "別の証票の預け入れ処理が進行中です。", NamedTextColor.YELLOW));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!tagger.matchesCanonical(held, voucher)) {
            player.sendMessage(Component.text(
                    "証票がメインハンドから移動したため、預け入れを保留しました。", NamedTextColor.YELLOW));
            return;
        }
        player.getInventory().setItemInMainHand(
                tagger.tagRedeem(held, operation.operationId()));
        databaseExecutor.submit(() -> vouchers.applyRedeem(operation.operationId(), Instant.now()))
                .whenComplete((outcome, failure) -> runOnMainThread(() -> {
                    UUID actorId = player.getUniqueId();
                    clearRedeemHold(actorId, voucher.voucherId());
                    Player current = onlinePlayer(actorId);
                    if (current == null) {
                        return;
                    }
                    if (failure != null) {
                        current.sendMessage(Component.text(
                                "預け入れを復旧待ちにしました: " + rootMessage(failure),
                                NamedTextColor.YELLOW));
                        return;
                    }
                    removeVoucherCopies(current, voucher);
                    current.sendMessage(Component.text(
                            voucher.quantity() + "Pをコア資源庫へ預け入れました。", NamedTextColor.GREEN));
                }));
    }

    private void reconcile(UUID actorId) {
        if (onlinePlayer(actorId) == null) {
            return;
        }
        List<UUID> heldVoucherIds = offlineRedeemHolds.entrySet().stream()
                .filter(entry -> entry.getKey().equals(actorId))
                .map(Map.Entry::getValue)
                .toList();
        databaseExecutor.submit(() -> {
                    List<DeliveryRecovery> deliveries = new ArrayList<>();
                    for (VoucherDeliveryOperation operation
                            : vouchers.loadOpenDeliveryOperations(actorId)) {
                        vouchers.findVoucher(operation.voucherId()).ifPresent(
                                voucher -> deliveries.add(new DeliveryRecovery(voucher, operation)));
                    }
                    List<RedeemRecovery> redeems = new ArrayList<>();
                    for (VoucherRedeemOperation operation : vouchers.loadOpenRedeems(actorId)) {
                        vouchers.findVoucher(operation.voucherId()).ifPresent(
                                voucher -> redeems.add(new RedeemRecovery(voucher, operation)));
                    }
                    List<ResourceVoucher> heldVouchers = new ArrayList<>();
                    for (UUID voucherId : heldVoucherIds) {
                        vouchers.findVoucher(voucherId).ifPresent(heldVouchers::add);
                    }
                    return new RecoveryData(
                            vouchers.loadPendingDeliveries(actorId), deliveries, redeems, heldVouchers);
                })
                .whenComplete((recovery, failure) -> runOnMainThread(() -> {
                    Player current = onlinePlayer(actorId);
                    if (failure != null) {
                        if (current != null) {
                            current.sendMessage(Component.text(
                                    "証票の復旧確認を再試行しています: " + rootMessage(failure),
                                    NamedTextColor.YELLOW));
                            Bukkit.getScheduler().runTaskLater(
                                    plugin, () -> reconcile(actorId), 20L);
                        }
                        return;
                    }
                    if (current == null) {
                        return;
                    }
                    try {
                        for (ResourceVoucher held : recovery.heldVouchers()) {
                            boolean hasOpenOperation = recovery.redeems().stream()
                                    .anyMatch(redeem -> redeem.voucher().voucherId().equals(held.voucherId()));
                            if (!hasOpenOperation && held.state() != ResourceVoucherState.RESERVED) {
                                clearRedeemHold(actorId, held.voucherId());
                                if (held.state() == ResourceVoucherState.REDEEMED
                                        || held.state() == ResourceVoucherState.VOIDED) {
                                    invalidateVoucherCopies(current, held);
                                }
                            }
                        }
                        for (DeliveryRecovery delivery : recovery.deliveryOperations()) {
                            VoucherDeliveryOperation operation = delivery.operation();
                            continueDelivery(current, new VoucherDeliveryResult(
                                    operation.state() == VoucherDeliveryState.APPLIED
                                            ? VoucherDeliveryOutcome.ALREADY_AVAILABLE
                                            : VoucherDeliveryOutcome.ALREADY_PREPARED,
                                    delivery.voucher(),
                                    operation));
                        }
                        for (ResourceVoucher voucher : recovery.pending()) {
                            if (recovery.deliveryOperations().stream().noneMatch(
                                    delivery -> delivery.voucher().voucherId().equals(voucher.voucherId()))) {
                                deliver(current, voucher);
                            }
                        }
                        for (RedeemRecovery redeem : recovery.redeems()) {
                            reconcileRedeem(current, redeem.voucher(), redeem.operation());
                        }
                    } finally {
                        voucherRecoveryGuards.complete(actorId);
                    }
                }));
    }

    private void reconcileRedeem(
            Player player, ResourceVoucher voucher, VoucherRedeemOperation operation) {
        if (operation.state() == VoucherRedeemState.APPLIED
                || voucher.state() == ResourceVoucherState.REDEEMED) {
            clearRedeemHold(player.getUniqueId(), voucher.voucherId());
            removeVoucherCopies(player, voucher);
            return;
        }
        if (operation.state() == VoucherRedeemState.ROLLED_BACK
                || voucher.state() == ResourceVoucherState.VOIDED) {
            clearRedeemHold(player.getUniqueId(), voucher.voucherId());
            invalidateVoucherCopies(player, voucher);
            return;
        }
        int slot = findCanonicalVoucherSlot(player, voucher, null, operation.operationId());
        if (slot < 0) {
            player.sendMessage(Component.text(
                    "預け入れ途中の証票が見つからないため、監査保留にしました。", NamedTextColor.YELLOW));
            return;
        }
        UUID actorId = player.getUniqueId();
        if (!activateRedeemHold(actorId, voucher.voucherId())) {
            player.sendMessage(Component.text(
                    "別の証票の預け入れ処理が進行中です。", NamedTextColor.YELLOW));
            return;
        }
        ItemStack item = player.getInventory().getItem(slot);
        if (!tagger.isRedeemReceipt(item)) {
            player.getInventory().setItem(slot, tagger.tagRedeem(item, operation.operationId()));
        }
        databaseExecutor.submit(() -> vouchers.applyRedeem(operation.operationId(), Instant.now()))
                .whenComplete((outcome, failure) -> runOnMainThread(() -> {
                    clearRedeemHold(actorId, voucher.voucherId());
                    Player current = onlinePlayer(actorId);
                    if (current == null) {
                        return;
                    }
                    if (failure == null) {
                        removeVoucherCopies(current, voucher);
                    }
                }));
    }

    private int findCanonicalVoucherSlot(
            Player player,
            ResourceVoucher voucher,
            UUID deliveryOperationId,
            UUID redeemOperationId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!tagger.matchesCanonical(item, voucher)) {
                continue;
            }
            ResourceVoucherItemData data = tagger.read(item).orElseThrow();
            if (data.deliveryOperationId().isPresent()
                    && !data.deliveryOperationId().filter(id -> id.equals(deliveryOperationId)).isPresent()) {
                continue;
            }
            if (data.redeemOperationId().isPresent()
                    && !data.redeemOperationId().filter(id -> id.equals(redeemOperationId)).isPresent()) {
                continue;
            }
            return slot;
        }
        return -1;
    }

    private void normalizeDeliveredVoucher(
            Player player, ResourceVoucher voucher, UUID operationId) {
        if (voucher.state() == ResourceVoucherState.REDEEMED
                || voucher.state() == ResourceVoucherState.VOIDED) {
            invalidateVoucherCopies(player, voucher);
            return;
        }
        int keepSlot = -1;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!tagger.matchesCanonical(item, voucher)) {
                continue;
            }
            ResourceVoucherItemData data = tagger.read(item).orElseThrow();
            if (operationId != null
                    && data.deliveryOperationId().filter(operationId::equals).isPresent()) {
                keepSlot = slot;
                break;
            }
            if (keepSlot < 0 && data.deliveryOperationId().isEmpty()
                    && data.redeemOperationId().isEmpty()) {
                keepSlot = slot;
            }
        }
        if (keepSlot < 0) {
            return;
        }
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!tagger.matchesCanonical(item, voucher)) {
                continue;
            }
            if (slot == keepSlot) {
                player.getInventory().setItem(slot, tagger.stripReceipts(item));
            } else {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void removeVoucherCopies(Player player, ResourceVoucher voucher) {
        invalidateVoucherCopies(player, voucher);
    }

    /** Removes every parseable physical copy by voucher UUID, including amount>1 duplicates. */
    private void invalidateVoucherCopies(Player player, ResourceVoucher voucher) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (tagger.isFor(inventory.getItem(slot), voucher.voucherId())) {
                inventory.setItem(slot, null);
            }
        }
        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        for (int slot = 0; slot < armor.length; slot++) {
            if (tagger.isFor(armor[slot], voucher.voucherId())) {
                armor[slot] = null;
                armorChanged = true;
            }
        }
        if (armorChanged) {
            inventory.setArmorContents(armor);
        }
        ItemStack[] extra = inventory.getExtraContents();
        boolean extraChanged = false;
        for (int slot = 0; slot < extra.length; slot++) {
            if (tagger.isFor(extra[slot], voucher.voucherId())) {
                extra[slot] = null;
                extraChanged = true;
            }
        }
        if (extraChanged) {
            inventory.setExtraContents(extra);
        }
        if (tagger.isFor(inventory.getItemInOffHand(), voucher.voucherId())) {
            inventory.setItemInOffHand(null);
        }
    }

    private boolean isVoucher(ItemStack item) {
        return tagger.isVoucher(item);
    }

    private boolean isReceipt(ItemStack item) {
        return tagger.isDeliveryReceipt(item) || tagger.isRedeemReceipt(item);
    }

    private boolean isTransferProtected(ItemStack item) {
        if (isReceipt(item)) {
            return true;
        }
        return tagger.read(item)
                .filter(data -> data.redeemOperationId().isEmpty())
                .map(data -> hasRedeemHold(data.voucherId()))
                .orElse(false);
    }

    private UUID currentRedeemHold(UUID actorId) {
        UUID pending = pendingRedeemVouchers.get(actorId);
        return pending != null ? pending : offlineRedeemHolds.get(actorId);
    }

    private boolean hasRedeemHold(UUID voucherId) {
        return pendingRedeemVouchers.containsValue(voucherId)
                || offlineRedeemHolds.containsValue(voucherId);
    }

    private boolean activateRedeemHold(UUID actorId, UUID voucherId) {
        UUID existing = currentRedeemHold(actorId);
        if (existing != null && !existing.equals(voucherId)) {
            return false;
        }
        offlineRedeemHolds.remove(actorId, voucherId);
        pendingRedeemVouchers.put(actorId, voucherId);
        return true;
    }

    private void preserveRedeemHoldOffline(UUID actorId, UUID voucherId) {
        UUID pending = pendingRedeemVouchers.remove(actorId);
        if (pending == null || pending.equals(voucherId)) {
            offlineRedeemHolds.put(actorId, voucherId);
        }
    }

    private void clearRedeemHold(UUID actorId, UUID voucherId) {
        pendingRedeemVouchers.remove(actorId, voucherId);
        offlineRedeemHolds.remove(actorId, voucherId);
    }

    private boolean isRecoveryGuarded(UUID playerId) {
        return voucherRecoveryGuards.isGuarded(playerId);
    }

    private boolean isRecoveryInventory(Inventory inventory) {
        return inventory.getHolder() instanceof Player player
                && isRecoveryGuarded(player.getUniqueId());
    }

    private boolean isVoucherInsertIntoForbiddenInventory(
            InventoryClickEvent event, ItemStack hotbar, ItemStack offhand) {
        if (!isForbiddenVoucherInventory(event.getView().getTopInventory().getType())) {
            return false;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean topTarget = event.getRawSlot() >= 0 && event.getRawSlot() < topSize;
        boolean shiftClick = event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_LEFT
                || event.getClick() == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT;
        return VoucherContainerPolicy.blocksPlainVoucherInsertion(
                true,
                topTarget,
                shiftClick,
                isVoucher(event.getCursor()),
                event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                        && isVoucher(hotbar),
                event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                        && isVoucher(offhand),
                isVoucher(event.getCurrentItem()));
    }

    private static boolean isForbiddenVoucherInventory(InventoryType type) {
        return type == InventoryType.ANVIL
                || type == InventoryType.GRINDSTONE
                || type == InventoryType.SMITHING;
    }

    private WithdrawalRequest withdrawalRequest(int rawSlot) {
        return switch (rawSlot) {
            case ResourceVaultGui.DEFENSE_TEN_SLOT ->
                    new WithdrawalRequest(ResourceType.DEFENSE_POINTS, 10L);
            case ResourceVaultGui.DEFENSE_HUNDRED_SLOT ->
                    new WithdrawalRequest(ResourceType.DEFENSE_POINTS, 100L);
            case ResourceVaultGui.DEFENSE_ALL_SLOT ->
                    new WithdrawalRequest(ResourceType.DEFENSE_POINTS, -1L);
            case ResourceVaultGui.ENHANCEMENT_ONE_SLOT ->
                    new WithdrawalRequest(ResourceType.ENHANCEMENT_POINTS, 1L);
            case ResourceVaultGui.ENHANCEMENT_TEN_SLOT ->
                    new WithdrawalRequest(ResourceType.ENHANCEMENT_POINTS, 10L);
            case ResourceVaultGui.ENHANCEMENT_ALL_SLOT ->
                    new WithdrawalRequest(ResourceType.ENHANCEMENT_POINTS, -1L);
            default -> null;
        };
    }

    private void runOnMainThread(Runnable action) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private static Player onlinePlayer(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() ? player : null;
    }

    private static UUID deterministic(UUID base, String namespace) {
        return UUID.nameUUIDFromBytes((base + "|" + namespace).getBytes(StandardCharsets.UTF_8));
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record WithdrawalRequest(ResourceType resourceType, long quantity) {
    }

    private record VaultData(
            TeamResourceSnapshot snapshot,
            boolean owner,
            boolean canWithdraw) {
    }

    private record RecoveryData(
            List<ResourceVoucher> pending,
            List<DeliveryRecovery> deliveryOperations,
            List<RedeemRecovery> redeems,
            List<ResourceVoucher> heldVouchers) {
    }

    private record DeliveryRecovery(
            ResourceVoucher voucher,
            VoucherDeliveryOperation operation) {
    }

    private record RedeemRecovery(
            ResourceVoucher voucher,
            VoucherRedeemOperation operation) {
    }
}
