package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.domain.CoreRepairCost;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.persistence.CoreMutationResult;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.CoreRepairOperation;
import io.github.takenoha.towerdefense.persistence.CoreRepairOperationState;
import io.github.takenoha.towerdefense.persistence.CoreRepairReceipt;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.PaymentMode;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalRedemption;
import io.github.takenoha.towerdefense.persistence.ResearchCrystalRedemptionState;
import io.github.takenoha.towerdefense.persistence.TeamMutationResult;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.persistence.TowerRepository;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Player-facing core and team management screens for the repair, relocation, and team slice. */
public final class CoreManagementListener implements Listener {
    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final DefenseRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final DefenseSessionManager sessions;
    private final CoreRegistry cores;
    private final CoreItemListener coreItems;
    private final TowerRepository towerRepository;
    private final DefenseShardTagger shardTagger;
    private final ResearchCrystalTagger researchCrystals;
    private final ResourceRepository resources;
    private final CoreRepairReceiptTagger repairReceipts;
    private final Set<UUID> repairInFlight = new java.util.HashSet<>();
    private final Set<UUID> teamActionInFlight = new java.util.HashSet<>();
    private final Set<UUID> crystalInFlight = new java.util.HashSet<>();

    public CoreManagementListener(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            CoreItemListener coreItems,
            DefenseShardTagger shardTagger) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                coreItems,
                shardTagger,
                null,
                new ResearchCrystalTagger(plugin),
                null);
    }

    public CoreManagementListener(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            CoreItemListener coreItems,
            DefenseShardTagger shardTagger,
            ResearchCrystalTagger researchCrystals) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                coreItems,
                shardTagger,
                null,
                researchCrystals,
                null);
    }

    public CoreManagementListener(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            CoreItemListener coreItems,
            DefenseShardTagger shardTagger,
            TowerRepository towerRepository,
            ResearchCrystalTagger researchCrystals) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                coreItems,
                shardTagger,
                towerRepository,
                researchCrystals,
                null);
    }

    public CoreManagementListener(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            CoreItemListener coreItems,
            DefenseShardTagger shardTagger,
            TowerRepository towerRepository,
            ResearchCrystalTagger researchCrystals,
            ResourceRepository resources) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.coreItems = Objects.requireNonNull(coreItems, "coreItems");
        this.shardTagger = Objects.requireNonNull(shardTagger, "shardTagger");
        this.towerRepository = towerRepository;
        this.researchCrystals = Objects.requireNonNull(researchCrystals, "researchCrystals");
        this.resources = resources;
        this.repairReceipts = new CoreRepairReceiptTagger(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCoreInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || !isEmptyHand(event.getItem())) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Optional<CoreRecord> core = cores.at(clicked);
        if (core.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        openCoreGui(event.getPlayer(), core.orElseThrow().id());
    }

    @EventHandler
    public void onRepairReceiptJoin(PlayerJoinEvent event) {
        reconcileOpenRepairReceipts(event.getPlayer());
    }

    @EventHandler
    public void onResearchCrystalJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> reconcileResearchCrystalReceipts(event.getPlayer()),
                1L);
    }

    /** Reconciles receipts for players who remain online across a plugin reload. */
    public void reconcileOnlineResearchCrystalReceipts() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> reconcileResearchCrystalReceipts(player),
                    1L);
        }
    }

    /** Reconciles a receipt after death, when the server has restored the respawn inventory. */
    @EventHandler
    public void onRepairReceiptRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> reconcileOpenRepairReceipts(event.getPlayer()),
                1L);
    }

    @EventHandler
    public void onResearchCrystalRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> reconcileResearchCrystalReceipts(event.getPlayer()),
                1L);
    }

    /**
     * A quit may happen while an async prepare/reserve callback is outstanding. Do not touch a
     * saved offline inventory. Operations without a physical receipt can be rolled back now;
     * receipt-bearing operations are intentionally left for join reconciliation.
     */
    @EventHandler
    public void onRepairReceiptQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        repairInFlight.remove(playerId);
        databaseExecutor.submit(() -> repository.loadPreparedCoreRepairs(playerId))
                .whenComplete((operations, failure) -> {
                    if (failure != null || operations == null) {
                        return;
                    }
                    for (CoreRepairOperation operation : operations) {
                        repository.findCoreRepairReceipt(operation.operationId()).ifPresentOrElse(
                                receipt -> plugin.getLogger().info(
                                        "Deferring core repair receipt reconciliation until player "
                                                + playerId + " rejoins: " + operation.operationId()
                                                + " (" + receipt.state() + ")"),
                                () -> repository.rollbackPreparedCoreRepair(
                                        operation.operationId(), Instant.now()));
                    }
                });
    }

    @EventHandler
    public void onResearchCrystalQuit(PlayerQuitEvent event) {
        // The durable redemption receipt is reconciled after the player rejoins. Never mutate a
        // saved offline inventory from an outstanding async callback.
        crystalInFlight.remove(event.getPlayer().getUniqueId());
    }

    /** Prevents an in-flight receipt from being moved while its database operation is pending. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResearchCrystalClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && crystalInFlight.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResearchCrystalDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && crystalInFlight.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResearchCrystalDrop(PlayerDropItemEvent event) {
        if (crystalInFlight.contains(event.getPlayer().getUniqueId())
                || researchCrystals.hasRedemptionReceipt(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResearchCrystalSwap(PlayerSwapHandItemsEvent event) {
        if (crystalInFlight.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResearchCrystalInventoryMove(InventoryMoveItemEvent event) {
        if (researchCrystals.hasRedemptionReceipt(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResearchCrystalPickup(EntityPickupItemEvent event) {
        if (researchCrystals.hasRedemptionReceipt(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResearchCrystalDespawn(ItemDespawnEvent event) {
        if (researchCrystals.hasRedemptionReceipt(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    private void reconcileOpenRepairReceipts(Player player) {
        UUID playerId = player.getUniqueId();
        databaseExecutor.submit(() -> {
            List<CoreRepairOperation> operations =
                    new ArrayList<>(repository.loadPreparedCoreRepairs(playerId));
            for (CoreRepairOperation terminal
                    : repository.loadTerminalCoreRepairReceipts(playerId)) {
                if (operations.stream().noneMatch(existing ->
                        existing.operationId().equals(terminal.operationId()))) {
                    operations.add(terminal);
                }
            }
            return List.copyOf(operations);
        })
                .whenComplete((operations, failure) -> runOnMainThread(() -> {
                    if (failure != null || operations == null) {
                        if (failure != null) {
                            plugin.getLogger().warning(
                                    "Could not reconcile core repair receipts for " + playerId
                                            + ": " + rootMessage(failure));
                        }
                        return;
                    }
                    for (CoreRepairOperation operation : operations) {
                        reconcileRepairReceipt(player, operation);
                    }
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptInventoryClick(InventoryClickEvent event) {
        if (containsRepairReceipt(event)) {
            event.setCancelled(true);
        }
    }

    private boolean containsRepairReceipt(InventoryClickEvent event) {
        ItemStack auxiliary = null;
        if (event.getWhoClicked() instanceof Player player) {
            if (event.getClick() == ClickType.NUMBER_KEY) {
                auxiliary = player.getInventory().getItem(event.getHotbarButton());
            } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                auxiliary = player.getInventory().getItemInOffHand();
            }
        }
        return ReceiptTransferPolicy.containsTagged(
                repairReceipts::isTagged,
                event.getCurrentItem(),
                event.getCursor(),
                auxiliary);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptInventoryDrag(InventoryDragEvent event) {
        if (repairReceipts.isTagged(event.getOldCursor())
                || event.getNewItems().values().stream().anyMatch(repairReceipts::isTagged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptInventoryMove(InventoryMoveItemEvent event) {
        if (repairReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptInventoryPickup(InventoryPickupItemEvent event) {
        if (repairReceipts.isTagged(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptEntityPickup(EntityPickupItemEvent event) {
        if (repairReceipts.isTagged(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptDrop(PlayerDropItemEvent event) {
        if (repairReceipts.isTagged(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptCraft(CraftItemEvent event) {
        if (event.getInventory().getMatrix() != null
                && java.util.Arrays.stream(event.getInventory().getMatrix())
                        .anyMatch(repairReceipts::isTagged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter
                && java.util.Arrays.stream(crafter.getInventory().getContents())
                        .anyMatch(repairReceipts::isTagged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptPlace(BlockPlaceEvent event) {
        if (repairReceipts.isTagged(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptDispense(BlockDispenseEvent event) {
        if (repairReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptInteract(PlayerInteractEvent event) {
        if (repairReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        if (repairReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptInteractEntity(PlayerInteractEntityEvent event) {
        if (repairReceipts.isTagged(
                event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptInteractAtEntity(PlayerInteractAtEntityEvent event) {
        onRepairReceiptInteractEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (repairReceipts.isTagged(event.getPlayerItem())
                || repairReceipts.isTagged(event.getArmorStandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptSwapHands(PlayerSwapHandItemsEvent event) {
        if (repairReceipts.isTagged(event.getMainHandItem())
                || repairReceipts.isTagged(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptDeath(PlayerDeathEvent event) {
        var iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (repairReceipts.isTagged(item)) {
                event.getItemsToKeep().add(item.clone());
                iterator.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptMerge(ItemMergeEvent event) {
        if (repairReceipts.isTagged(event.getEntity().getItemStack())
                || repairReceipts.isTagged(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptDespawn(ItemDespawnEvent event) {
        if (repairReceipts.isTagged(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item
                && repairReceipts.isTagged(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof Item item
                && repairReceipts.isTagged(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRepairReceiptTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof Item item
                && repairReceipts.isTagged(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CoreManagementInventoryHolder)
                && !(top.getHolder() instanceof ResourceVaultInventoryHolder)
                && !(top.getHolder() instanceof TeamManagementInventoryHolder)
                && !(top.getHolder() instanceof TeamManagementConfirmationHolder)
                && !(top.getHolder() instanceof TowerResearchInventoryHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() >= top.getSize()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (top.getHolder() instanceof CoreManagementInventoryHolder holder) {
            if (event.getRawSlot() == CoreManagementGui.CLOSE_SLOT) {
                player.closeInventory();
            } else if (event.getRawSlot() == CoreManagementGui.TEAM_SLOT) {
                openTeamGui(player, holder.coreId());
            } else if (event.getRawSlot() == CoreManagementGui.RESOURCE_VAULT_SLOT) {
                openResourceVault(player, holder.coreId());
            } else if (event.getRawSlot() == CoreManagementGui.RESEARCH_DEPOSIT_SLOT) {
                beginResearchCrystalDeposit(player, holder.coreId());
            } else if (event.getRawSlot() == CoreManagementGui.TOWER_RESEARCH_SLOT) {
                openTowerResearchGui(player, holder.coreId());
            } else if (event.getRawSlot() == CoreManagementGui.REPAIR_SLOT) {
                beginRepair(player, holder.coreId(), false);
            } else if (event.getRawSlot() == CoreManagementGui.LEGACY_REPAIR_SLOT) {
                beginRepair(player, holder.coreId(), true);
            } else if (event.getRawSlot() == CoreManagementGui.RELOCATE_SLOT) {
                beginRelocation(player, holder.coreId());
            }
        } else if (top.getHolder() instanceof ResourceVaultInventoryHolder holder) {
            if (event.getRawSlot() == ResourceVaultGui.CLOSE_SLOT) {
                openCoreGui(player, holder.coreId());
            }
        } else if (top.getHolder() instanceof TeamManagementInventoryHolder holder) {
            handleTeamManagementClick(player, holder, event.getRawSlot(), event.getClick());
        } else if (top.getHolder() instanceof TeamManagementConfirmationHolder holder) {
            handleTeamConfirmationClick(player, holder, event.getRawSlot());
        } else if (top.getHolder() instanceof TowerResearchInventoryHolder holder) {
            handleTowerResearchClick(player, holder, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof CoreManagementInventoryHolder
                || holder instanceof ResourceVaultInventoryHolder
                || holder instanceof TeamManagementInventoryHolder
                || holder instanceof TeamManagementConfirmationHolder
                || holder instanceof TowerResearchInventoryHolder) {
            event.setCancelled(true);
        }
    }

    private void openCoreGui(Player player, UUID coreId) {
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが永続データに存在しません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが永続データに存在しません"));
            if (!team.members().contains(player.getUniqueId())) {
                throw new IllegalStateException("このコアへアクセスできるチームメンバーではありません");
            }
            TeamProgress progress = repository.loadTeamProgress(team.id());
            CoreRepairCost repairCost = core.currentHitPoints() >= core.maximumHitPoints()
                    ? null
                    : CoreRepairCost.forMissing(
                            core.maximumHitPoints() - core.currentHitPoints(),
                            progress.highestClearedLevel(),
                            settings.core());
            TeamResourceSnapshot resourceSnapshot = resources == null
                    ? new TeamResourceSnapshot(team.id(), 0L, 0L, 0L, 0L)
                    : resources.load(team.id(), player.getUniqueId());
            return new CoreGuiData(core, team, progress, repairCost, resourceSnapshot);
        }).whenComplete((data, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                return;
            }
            player.openInventory(CoreManagementGui.create(
                    data.core(),
                    data.team(),
                    data.progress(),
                    data.repairCost(),
                    settings.core().repairMaterial(),
                    data.resources(),
                    settings.rewards().legacyResourcePaymentsEnabled()));
        }));
    }

    private void openResourceVault(Player player, UUID coreId) {
        boolean canWithdraw = !sessions.hasActiveSession();
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが永続データに存在しません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが永続データに存在しません"));
            if (!team.members().contains(player.getUniqueId())) {
                throw new IllegalStateException("このコアへアクセスできるチームメンバーではありません");
            }
            TeamResourceSnapshot snapshot = resources == null
                    ? new TeamResourceSnapshot(team.id(), 0L, 0L, 0L, 0L)
                    : resources.load(team.id(), player.getUniqueId());
            return new ResourceVaultData(
                    snapshot,
                    team.ownerId().equals(player.getUniqueId()),
                    canWithdraw);
        }).whenComplete((data, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                return;
            }
            player.openInventory(ResourceVaultGui.create(
                    coreId,
                    data.snapshot(),
                    data.owner(),
                    data.canWithdraw()));
        }));
    }

    private void openTowerResearchGui(Player player, UUID coreId) {
        if (towerRepository == null) {
            player.sendMessage(Component.text(
                    "タワー研究画面は現在利用できません。", NamedTextColor.RED));
            return;
        }
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.members().contains(player.getUniqueId())) {
                throw new IllegalStateException("このコアへアクセスできるチームメンバーではありません");
            }
            return new TowerResearchGuiData(
                    repository.loadTeamProgress(team.id()),
                    towerRepository.loadTowerResearch(team.id()));
        }).whenComplete((data, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                return;
            }
            player.openInventory(TowerResearchGui.create(
                    coreId,
                    data.progress(),
                    data.research(),
                    settings.towers()));
        }));
    }

    private void handleTowerResearchClick(
            Player player,
            TowerResearchInventoryHolder holder,
            int rawSlot) {
        if (rawSlot == TowerResearchGui.CLOSE_SLOT) {
            openCoreGui(player, holder.coreId());
            return;
        }
        TowerResearchGui.towerTypeAt(rawSlot)
                .ifPresent(type -> beginTowerResearchPurchase(player, holder.coreId(), type));
    }

    private void beginTowerResearchPurchase(
            Player player,
            UUID coreId,
            io.github.takenoha.towerdefense.domain.TowerType towerType) {
        if (towerRepository == null) {
            return;
        }
        UUID actorId = player.getUniqueId();
        UUID operationId = UUID.randomUUID();
        player.sendMessage(Component.text("タワー研究を購入しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.members().contains(actorId)) {
                throw new IllegalStateException("このチームのメンバーではありません");
            }
            var current = towerRepository.findTowerResearch(team.id(), towerType).orElseThrow(
                    () -> new IllegalStateException("タワー研究データが見つかりません"));
            int cost = settings.towers().researchCost(current.researchLevel());
            return towerRepository.purchaseTowerResearch(
                    team.id(), actorId, towerType, cost, operationId, Instant.now());
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(
                        "タワー研究を購入できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(
                    towerType.displayName() + "研究をLv" + result.research().researchLevel()
                            + "へ解放しました。",
                    NamedTextColor.GREEN));
            openTowerResearchGui(player, coreId);
        }));
    }

    private void beginResearchCrystalDeposit(Player player, UUID coreId) {
        if (sessions.hasActiveSession()) {
            player.sendMessage(Component.text("防衛戦中は研究結晶を納品できません。", NamedTextColor.RED));
            return;
        }
        List<ResearchCrystalInventoryPolicy.Candidate> candidates =
                ResearchCrystalInventoryPolicy.scan(
                        player.getInventory().getStorageContents(),
                        player.getInventory().getItemInOffHand(),
                        researchCrystals);
        if (candidates.isEmpty()) {
            player.sendMessage(Component.text(
                    "自分のインベントリに納品できる研究結晶がありません。",
                    NamedTextColor.YELLOW));
            return;
        }
        UUID actorId = player.getUniqueId();
        if (!crystalInFlight.add(actorId)) {
            player.sendMessage(Component.text("研究結晶の納品を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text(
                "研究結晶をインベントリ全体から確認しています…", NamedTextColor.GRAY));
        processResearchCrystalCandidate(
                player,
                coreId,
                candidates,
                0,
                new ResearchCrystalDepositSummary());
    }

    private void processResearchCrystalCandidate(
            Player player,
            UUID coreId,
            List<ResearchCrystalInventoryPolicy.Candidate> candidates,
            int index,
            ResearchCrystalDepositSummary summary) {
        if (!player.isOnline()) {
            crystalInFlight.remove(player.getUniqueId());
            return;
        }
        if (index >= candidates.size()) {
            finishCrystalDeposit(player, coreId, summary);
            return;
        }
        ResearchCrystalInventoryPolicy.Candidate candidate = candidates.get(index);
        ItemStack current = currentCrystalCandidate(player, candidate);
        if (!matchesCrystalCandidate(current, candidate)) {
            summary.skippedStacks++;
            processResearchCrystalCandidate(player, coreId, candidates, index + 1, summary);
            return;
        }

        UUID operationId = UUID.randomUUID();
        // Keep the physical handoff durable while the database operation is in flight.
        ItemStack tagged = current.clone();
        researchCrystals.tagRedemption(tagged, operationId);
        if (candidate.isOffHand()) {
            player.getInventory().setItemInOffHand(tagged);
        } else {
            player.getInventory().setItem(candidate.storageSlot(), tagged);
        }
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            return repository.prepareResearchCrystalRedemption(
                    candidate.identity().batchId(),
                    core.id(),
                    player.getUniqueId(),
                    candidate.identity().teamId(),
                    candidate.identity().issuedQuantity(),
                    candidate.quantity(),
                    operationId,
                    Instant.now());
        }).whenComplete((prepared, prepareFailure) -> runOnMainThread(() -> {
            if (prepareFailure != null) {
                if (player.isOnline()) {
                    clearResearchCrystalReceipt(player, operationId);
                }
                summary.failures.add(rootMessage(prepareFailure));
                processResearchCrystalCandidate(player, coreId, candidates, index + 1, summary);
                return;
            }
            if (!player.isOnline()) {
                rollbackCrystalDeposit(player, prepared.operationId(), null, null);
                return;
            }
            if (sessions.hasActiveSession()
                    || countResearchCrystalReceipt(player, prepared.operationId())
                            < prepared.quantity()) {
                rollbackCrystalDeposit(
                        player,
                        prepared.operationId(),
                        "研究結晶の現物または防衛フェーズが変わったため納品を取り消しました。",
                        () -> processResearchCrystalCandidate(
                                player, coreId, candidates, index + 1, summary));
                return;
            }
            // Commit the points before consuming the physical item. The receipt makes a stop
            // between these callbacks recoverable on the next join.
            databaseExecutor.submit(() -> repository.applyResearchCrystalRedemption(
                            prepared.operationId(), Instant.now()))
                    .whenComplete((result, applyFailure) -> runOnMainThread(() -> {
                        if (applyFailure != null) {
                            rollbackCrystalDeposit(
                                    player,
                                    prepared.operationId(),
                                    "研究結晶を納品できなかったため復旧を試みます: "
                                            + rootMessage(applyFailure),
                                    () -> processResearchCrystalCandidate(
                                            player, coreId, candidates, index + 1, summary));
                            return;
                        }
                        if (!player.isOnline()) {
                            crystalInFlight.remove(player.getUniqueId());
                            return;
                        }
                        int consumed = consumeResearchCrystalReceipt(
                                player, prepared.operationId(), prepared.quantity());
                        if (consumed != prepared.quantity()) {
                            crystalInFlight.remove(player.getUniqueId());
                            plugin.getLogger().warning(
                                    "Applied research crystal redemption is awaiting physical "
                                            + "receipt recovery: " + prepared.operationId());
                            player.updateInventory();
                            player.sendMessage(Component.text(
                                    "研究結晶の納品は確定しましたが、現物の後処理を保留しています。"
                                            + "再接続後に自動復旧します。",
                                    NamedTextColor.YELLOW));
                            return;
                        }
                        summary.convertedQuantity += prepared.quantity();
                        summary.latestResearchPoints = result.progress().researchPoints();
                        processResearchCrystalCandidate(
                                player, coreId, candidates, index + 1, summary);
                    }));
        }));
    }

    private void rollbackCrystalDeposit(
            Player player,
            UUID operationId,
            String message,
            Runnable afterRollback) {
        databaseExecutor.submit(() -> repository.rollbackResearchCrystalRedemption(
                        operationId, Instant.now()))
                .whenComplete((rolledBack, rollbackFailure) -> runOnMainThread(() -> {
                    if (rollbackFailure != null) {
                        crystalInFlight.remove(player.getUniqueId());
                        plugin.getLogger().log(
                                java.util.logging.Level.SEVERE,
                                "Could not roll back research crystal redemption " + operationId,
                                rollbackFailure);
                        if (player.isOnline()) {
                            player.sendMessage(Component.text(
                                    "研究結晶の納品復旧を保留しています。再接続後に再試行します。",
                                    NamedTextColor.RED));
                        }
                        return;
                    }
                    if (rolledBack.isPresent()
                            && rolledBack.orElseThrow().state() == ResearchCrystalRedemptionState.ROLLED_BACK) {
                        if (player.isOnline()) {
                            clearResearchCrystalReceipt(player, operationId);
                            player.updateInventory();
                            if (message != null) {
                                player.sendMessage(Component.text(message, NamedTextColor.RED));
                            }
                            if (afterRollback != null) {
                                afterRollback.run();
                                return;
                            }
                        }
                        crystalInFlight.remove(player.getUniqueId());
                        return;
                    }
                    // An ambiguous apply result must retain its receipt for durable reconciliation.
                    crystalInFlight.remove(player.getUniqueId());
                    if (player.isOnline()) {
                        player.sendMessage(Component.text(
                                "研究結晶の納品状態が確定できないため、現物の復旧を保留しています。",
                                NamedTextColor.YELLOW));
                    }
                }));
    }

    private void finishCrystalDeposit(
            Player player,
            UUID coreId,
            ResearchCrystalDepositSummary summary) {
        crystalInFlight.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        if (summary.convertedQuantity > 0) {
            player.sendMessage(Component.text(
                    "研究結晶を" + summary.convertedQuantity
                            + "個納品し、研究ポイントへ変換しました。現在: "
                            + summary.latestResearchPoints,
                    NamedTextColor.GREEN));
            if (!summary.failures.isEmpty() || summary.skippedStacks > 0) {
                player.sendMessage(Component.text(
                        "一部の研究結晶は状態が変わったため、納品していません。",
                        NamedTextColor.YELLOW));
            }
            openCoreGui(player, coreId);
            return;
        }
        if (!summary.failures.isEmpty()) {
            player.sendMessage(Component.text(
                    "研究結晶を納品できません: " + summary.failures.get(0),
                    NamedTextColor.RED));
        } else {
            player.sendMessage(Component.text(
                    "研究結晶の現物が変わったため納品できませんでした。もう一度お試しください。",
                    NamedTextColor.YELLOW));
        }
    }

    private ItemStack currentCrystalCandidate(
            Player player,
            ResearchCrystalInventoryPolicy.Candidate candidate) {
        return candidate.isOffHand()
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItem(candidate.storageSlot());
    }

    private boolean matchesCrystalCandidate(
            ItemStack current,
            ResearchCrystalInventoryPolicy.Candidate candidate) {
        return current != null
                && current.getAmount() >= candidate.quantity()
                && current.isSimilar(candidate.snapshot())
                && researchCrystals.read(current)
                        .map(candidate.identity()::equals)
                        .orElse(false);
    }

    private int countResearchCrystalReceipt(Player player, UUID operationId) {
        long total = 0L;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (researchCrystals.redemptionOperationId(item).filter(operationId::equals).isPresent()) {
                total += item.getAmount();
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (researchCrystals.redemptionOperationId(offHand).filter(operationId::equals).isPresent()) {
            total += offHand.getAmount();
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private int consumeResearchCrystalReceipt(
            Player player,
            UUID operationId,
            int quantity) {
        int remaining = quantity;
        for (int slot = 0;
                slot < player.getInventory().getStorageContents().length && remaining > 0;
                slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!researchCrystals.redemptionOperationId(item).filter(operationId::equals).isPresent()) {
                continue;
            }
            int consumed = Math.min(remaining, item.getAmount());
            if (consumed == item.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                ItemStack remainderItem = item.clone();
                remainderItem.setAmount(item.getAmount() - consumed);
                researchCrystals.clearRedemptionReceipt(remainderItem);
                player.getInventory().setItem(slot, remainderItem);
            }
            remaining -= consumed;
        }
        if (remaining > 0) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (researchCrystals.redemptionOperationId(offHand).filter(operationId::equals).isPresent()) {
                int consumed = Math.min(remaining, offHand.getAmount());
                if (consumed == offHand.getAmount()) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    ItemStack remainderItem = offHand.clone();
                    remainderItem.setAmount(offHand.getAmount() - consumed);
                    researchCrystals.clearRedemptionReceipt(remainderItem);
                    player.getInventory().setItemInOffHand(remainderItem);
                }
                remaining -= consumed;
            }
        }
        if (remaining == 0) {
            clearResearchCrystalReceipt(player, operationId);
        }
        return quantity - remaining;
    }

    private void clearResearchCrystalReceipt(Player player, UUID operationId) {
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (researchCrystals.redemptionOperationId(item).filter(operationId::equals).isPresent()) {
                ItemStack stripped = item.clone();
                researchCrystals.clearRedemptionReceipt(stripped);
                player.getInventory().setItem(slot, stripped);
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (researchCrystals.redemptionOperationId(offHand).filter(operationId::equals).isPresent()) {
            ItemStack stripped = offHand.clone();
            researchCrystals.clearRedemptionReceipt(stripped);
            player.getInventory().setItemInOffHand(stripped);
        }
    }

    private void reconcileResearchCrystalReceipts(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Set<UUID> operationIds = new java.util.HashSet<>();
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            int currentSlot = slot;
            ItemStack item = player.getInventory().getItem(slot);
            collectResearchCrystalReceipt(
                    item,
                    operationIds,
                    () -> {
                        ItemStack stripped = item.clone();
                        researchCrystals.clearRedemptionReceipt(stripped);
                        player.getInventory().setItem(currentSlot, stripped);
                    });
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        collectResearchCrystalReceipt(
                offHand,
                operationIds,
                () -> {
                    ItemStack stripped = offHand.clone();
                    researchCrystals.clearRedemptionReceipt(stripped);
                    player.getInventory().setItemInOffHand(stripped);
                });
        for (UUID operationId : operationIds) {
            reconcileResearchCrystalReceipt(player, operationId);
        }
    }

    private void collectResearchCrystalReceipt(
            ItemStack item,
            Set<UUID> operationIds,
            Runnable clearMalformedReceipt) {
        if (!researchCrystals.hasRedemptionReceipt(item)) {
            return;
        }
        Optional<UUID> operationId = researchCrystals.redemptionOperationId(item);
        if (operationId.isPresent()) {
            operationIds.add(operationId.orElseThrow());
        } else {
            clearMalformedReceipt.run();
        }
    }

    private void reconcileResearchCrystalReceipt(Player player, UUID operationId) {
        databaseExecutor.submit(() -> repository.findResearchCrystalRedemption(operationId))
                .whenComplete((loaded, failure) -> runOnMainThread(() -> {
                    if (failure != null || !player.isOnline()) {
                        if (failure != null) {
                            plugin.getLogger().warning(
                                    "Could not reconcile research crystal redemption "
                                            + operationId + ": " + rootMessage(failure));
                        }
                        return;
                    }
                    if (loaded.isEmpty()) {
                        clearResearchCrystalReceipt(player, operationId);
                        player.updateInventory();
                        return;
                    }
                    ResearchCrystalRedemption redemption = loaded.orElseThrow();
                    if (!redemption.actorId().equals(player.getUniqueId())) {
                        plugin.getLogger().warning(
                                "Research crystal receipt belongs to another actor: " + operationId);
                        return;
                    }
                    if (redemption.state() == ResearchCrystalRedemptionState.APPLIED) {
                        int consumed = consumeResearchCrystalReceipt(
                                player, operationId, redemption.quantity());
                        player.updateInventory();
                        if (consumed != redemption.quantity()) {
                            plugin.getLogger().warning(
                                    "Research crystal receipt remains after applied recovery: "
                                            + operationId);
                        }
                        return;
                    }
                    if (redemption.state() == ResearchCrystalRedemptionState.ROLLED_BACK) {
                        clearResearchCrystalReceipt(player, operationId);
                        player.updateInventory();
                        return;
                    }
                    databaseExecutor.submit(() -> repository.rollbackResearchCrystalRedemption(
                                    operationId, Instant.now()))
                            .whenComplete((rolledBack, rollbackFailure) -> runOnMainThread(() -> {
                                if (rollbackFailure != null || !player.isOnline()) {
                                    if (rollbackFailure != null) {
                                        plugin.getLogger().warning(
                                                "Could not roll back prepared research crystal "
                                                        + operationId + ": "
                                                        + rootMessage(rollbackFailure));
                                    }
                                    return;
                                }
                                if (rolledBack.isPresent()
                                        && rolledBack.orElseThrow().state()
                                                == ResearchCrystalRedemptionState.ROLLED_BACK) {
                                    clearResearchCrystalReceipt(player, operationId);
                                    player.updateInventory();
                                }
                            }));
                }));
    }

    private static final class ResearchCrystalDepositSummary {
        private int convertedQuantity;
        private long latestResearchPoints;
        private int skippedStacks;
        private final List<String> failures = new ArrayList<>();
    }

    private void openTeamGui(Player player, UUID coreId) {
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが永続データに存在しません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが永続データに存在しません"));
            if (!team.members().contains(player.getUniqueId())) {
                throw new IllegalStateException("このチームを管理できるメンバーではありません");
            }
            return new TeamGuiData(core, team);
        }).whenComplete((data, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                return;
            }
            player.openInventory(TeamManagementGui.create(
                    data.core(),
                    data.team(),
                    player.getUniqueId()));
        }));
    }

    private void handleTeamManagementClick(
            Player player,
            TeamManagementInventoryHolder holder,
            int rawSlot,
            ClickType click) {
        if (rawSlot == TeamManagementGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (rawSlot == TeamManagementGui.INVITE_SLOT) {
            beginInvite(player, holder.coreId());
            return;
        }
        if (rawSlot == TeamManagementGui.RENAME_SLOT) {
            player.sendMessage(Component.text(
                    "チーム名変更: /td team rename <名前>（1〜"
                            + TeamRecord.MAX_DISPLAY_NAME_CODE_POINTS + "文字）",
                    NamedTextColor.LIGHT_PURPLE));
            return;
        }
        if (rawSlot == TeamManagementGui.LEAVE_SLOT) {
            openTeamConfirmation(
                    player,
                    holder.coreId(),
                    player.getUniqueId(),
                    TeamManagementConfirmationHolder.Action.LEAVE_TEAM);
            return;
        }
        holder.memberAt(rawSlot).ifPresent(targetId -> {
            if (targetId.equals(player.getUniqueId())) {
                player.sendMessage(Component.text(
                        "自分自身にはメンバー操作を実行できません。",
                        NamedTextColor.YELLOW));
                return;
            }
            TeamManagementConfirmationHolder.Action action = click.isRightClick()
                    ? TeamManagementConfirmationHolder.Action.TRANSFER_OWNER
                    : TeamManagementConfirmationHolder.Action.REMOVE_MEMBER;
            openTeamConfirmation(player, holder.coreId(), targetId, action);
        });
    }

    private void openTeamConfirmation(
            Player player,
            UUID coreId,
            UUID targetId,
            TeamManagementConfirmationHolder.Action action) {
        player.openInventory(TeamManagementGui.createConfirmation(coreId, targetId, action));
    }

    private void handleTeamConfirmationClick(
            Player player,
            TeamManagementConfirmationHolder holder,
            int rawSlot) {
        if (rawSlot == TeamManagementGui.CANCEL_SLOT) {
            openTeamGui(player, holder.coreId());
        } else if (rawSlot == TeamManagementGui.CONFIRM_SLOT) {
            beginTeamAction(player, holder);
        }
    }

    private void beginInvite(Player player, UUID coreId) {
        if (sessions.hasActiveSession()) {
            player.sendMessage(Component.text("防衛戦中はチームを変更できません。", NamedTextColor.RED));
            return;
        }
        List<? extends Player> nearby = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                .filter(candidate -> candidate.getWorld().equals(player.getWorld()))
                .filter(candidate -> candidate.getLocation().distanceSquared(player.getLocation()) <= 36.0d)
                .toList();
        if (nearby.isEmpty()) {
            player.sendMessage(Component.text(
                    "6ブロック以内に招待できるプレイヤーがいません。",
                    NamedTextColor.YELLOW));
            return;
        }
        if (nearby.size() > 1) {
            player.sendMessage(Component.text(
                    "招待対象を1人に絞るため、6ブロック以内の他プレイヤーを離してください。",
                    NamedTextColor.YELLOW));
            return;
        }
        Player target = nearby.get(0);
        UUID actorId = player.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (!teamActionInFlight.add(actorId)) {
            player.sendMessage(Component.text("チーム操作を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.ownerId().equals(actorId)) {
                throw new IllegalStateException("プレイヤー招待はチームオーナーだけが実行できます");
            }
            if (repository.findTeamByMember(targetId).isPresent()) {
                throw new IllegalStateException("招待対象はすでに別のチームに所属しています");
            }
            return repository.addTeamMember(
                    team.id(),
                    actorId,
                    targetId,
                    UUID.randomUUID(),
                    Instant.now());
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            teamActionInFlight.remove(actorId);
            if (failure != null) {
                player.sendMessage(Component.text(
                        "プレイヤーを招待できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(
                    playerName(targetId) + "をチームへ招待しました。",
                    NamedTextColor.GREEN));
            Player invited = Bukkit.getPlayer(targetId);
            if (invited != null) {
                invited.sendMessage(Component.text(
                        playerName(actorId) + "のチームに招待されました。",
                        NamedTextColor.GREEN));
            }
            openTeamGui(player, coreId);
        }));
    }

    private void beginTeamAction(
            Player player,
            TeamManagementConfirmationHolder holder) {
        UUID actorId = player.getUniqueId();
        if (!teamActionInFlight.add(actorId)) {
            player.sendMessage(Component.text("チーム操作を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(holder.coreId()).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.members().contains(actorId)) {
                throw new IllegalStateException("このチームのメンバーではありません");
            }
            if (holder.action() == TeamManagementConfirmationHolder.Action.LEAVE_TEAM
                    && !holder.targetId().equals(actorId)) {
                throw new IllegalStateException("脱退対象が現在のプレイヤーと一致しません");
            }
            UUID operationId = UUID.randomUUID();
            Instant now = Instant.now();
            return switch (holder.action()) {
                case REMOVE_MEMBER -> repository.removeTeamMember(
                        team.id(), actorId, holder.targetId(), operationId, now);
                case TRANSFER_OWNER -> repository.transferTeamOwnership(
                        team.id(), actorId, holder.targetId(), operationId, now);
                case LEAVE_TEAM -> repository.leaveTeam(team.id(), actorId, operationId, now);
            };
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            teamActionInFlight.remove(actorId);
            if (failure != null) {
                player.sendMessage(Component.text(
                        "チーム操作を実行できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                openTeamGui(player, holder.coreId());
                return;
            }
            TeamMutationResult mutation = Objects.requireNonNull(result, "team mutation result");
            String message = switch (holder.action()) {
                case REMOVE_MEMBER -> playerName(holder.targetId()) + "をチームから除名しました。";
                case TRANSFER_OWNER -> playerName(holder.targetId()) + "へオーナーを移譲しました。";
                case LEAVE_TEAM -> "チームから脱退しました。";
            };
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
            if (holder.action() == TeamManagementConfirmationHolder.Action.REMOVE_MEMBER) {
                Player removed = Bukkit.getPlayer(holder.targetId());
                if (removed != null) {
                    removed.sendMessage(Component.text(
                            "チームから除名されました。",
                            NamedTextColor.YELLOW));
                }
            }
            if (mutation.team().isEmpty() || holder.action()
                    == TeamManagementConfirmationHolder.Action.LEAVE_TEAM) {
                player.closeInventory();
                return;
            }
            openTeamGui(player, holder.coreId());
        }));
    }

    private void beginRepair(Player player, UUID coreId, boolean explicitLegacy) {
        UUID actorId = player.getUniqueId();
        if (!repairInFlight.add(actorId)) {
            player.sendMessage(Component.text("修繕を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        databaseExecutor.submit(() -> {
            CoreRecord core = repository.findCore(coreId).orElseThrow(
                    () -> new IllegalStateException("コアが見つかりません"));
            TeamRecord team = repository.findTeam(core.teamId()).orElseThrow(
                    () -> new IllegalStateException("コアのチームが見つかりません"));
            if (!team.members().contains(actorId)) {
                throw new IllegalStateException("このコアを修繕する権限がありません");
            }
            TeamProgress progress = repository.loadTeamProgress(team.id());
            if (core.currentHitPoints() >= core.maximumHitPoints()) {
                throw new IllegalStateException("コアはすでに最大HPです");
            }
            return new CoreGuiData(
                    core,
                    team,
                    progress,
                    CoreRepairCost.forMissing(
                            core.maximumHitPoints() - core.currentHitPoints(),
                            progress.highestClearedLevel(),
                            settings.core()),
                    resources == null
                            ? new TeamResourceSnapshot(team.id(), 0L, 0L, 0L, 0L)
                            : resources.load(team.id(), actorId));
        }).whenComplete((data, lookupFailure) -> runOnMainThread(() -> {
            if (lookupFailure != null) {
                finishRepair(player, rootMessage(lookupFailure));
                return;
            }
            if (!player.isOnline()) {
                repairInFlight.remove(player.getUniqueId());
                return;
            }
            if (sessions.hasActiveSession()) {
                finishRepair(player, "防衛戦中はコアを修繕できません。");
                return;
            }
            Material repairMaterial = Material.matchMaterial(settings.core().repairMaterial());
            if (repairMaterial == null) {
                finishRepair(player, "core.repair-material のMaterialが不正です。");
                return;
            }
            long shardCost = data.repairCost().defenseShardAmount();
            boolean walletPayment = data.resources().defensePoints() >= shardCost;
            PaymentMode paymentMode;
            try {
                paymentMode = PaymentSelectionPolicy.choose(
                        explicitLegacy,
                        walletPayment,
                        settings.rewards().legacyResourcePaymentsEnabled());
            } catch (IllegalStateException selectionFailure) {
                finishRepair(player, selectionFailure.getMessage());
                return;
            }
            if (paymentMode == PaymentMode.LEGACY_ITEMS) {
                player.sendMessage(Component.text(
                        explicitLegacy
                                ? "旧素材支払いを明示的に選択しました（旧方式は廃止予定です）。"
                                : "防衛ポイント不足のため旧素材支払いを使用します（旧方式は廃止予定です）。",
                        NamedTextColor.YELLOW));
            }
            UUID operationId = UUID.randomUUID();
            if (paymentMode == PaymentMode.LEGACY_ITEMS) {
                plugin.getLogger().info(
                        "Legacy core repair payment selected actor=" + player.getUniqueId()
                                + " team=" + data.team().id()
                                + " operation=" + operationId
                                + " cost=" + data.repairCost().vanillaMaterialAmount()
                                + "+" + shardCost
                                + " mode=" + paymentMode);
            }
            databaseExecutor.submit(() -> repository.prepareCoreRepair(
                            data.core().id(),
                            player.getUniqueId(),
                            data.repairCost().repairAmount(),
                            paymentMode == PaymentMode.POINT_WALLET ? shardCost : 0L,
                            paymentMode,
                            repairMaterial.name(),
                            data.repairCost().vanillaMaterialAmount(),
                            paymentMode == PaymentMode.LEGACY_ITEMS ? shardCost : 0L,
                            operationId,
                            Instant.now()))
                    .whenComplete((prepared, prepareFailure) -> runOnMainThread(() -> {
                        if (prepareFailure != null) {
                            finishRepair(player, rootMessage(prepareFailure));
                            return;
                        }
                        if (!player.isOnline()) {
                            rollbackPreparedRepair(
                                    player,
                                    Objects.requireNonNull(prepared, "prepared repair"),
                                    "プレイヤーが切断したため修繕を取り消しました。");
                            return;
                        }
                        reserveAndSecureRepairReceipt(
                                player,
                                repairMaterial,
                                Objects.requireNonNull(prepared, "prepared repair"));
                    }));
        }));
    }

    private void reserveAndSecureRepairReceipt(
            Player player,
            Material repairMaterial,
            CoreRepairOperation prepared) {
        if (expectedReceiptQuantity(prepared) == 0L) {
            applyPreparedRepair(player, prepared);
            return;
        }
        String receiptMaterial = expectedReceiptMaterial(prepared);
        long receiptQuantity = expectedReceiptQuantity(prepared);
        databaseExecutor.submit(() -> repository.reserveCoreRepairReceipt(
                        prepared.operationId(),
                        player.getUniqueId(),
                        receiptMaterial,
                        receiptQuantity,
                        Instant.now()))
                .whenComplete((receipt, reserveFailure) -> runOnMainThread(() -> {
                    if (reserveFailure != null) {
                        rollbackPreparedRepair(player, prepared, rootMessage(reserveFailure));
                        return;
                    }
                    if (!player.isOnline()
                            || !secureReceiptItemsInPlace(player, repairMaterial, prepared)) {
                        if (player.isOnline()) {
                            restoreAndRollbackRepair(
                                    player,
                                    prepared,
                                    "修繕に必要な素材を安全に確保できませんでした。");
                        } else {
                            rollbackPreparedRepair(
                                    player,
                                    prepared,
                                    "プレイヤーが切断したため修繕を取り消しました。");
                        }
                        return;
                    }
                    databaseExecutor.submit(() -> repository.secureCoreRepairReceipt(
                                    prepared.operationId(), Instant.now()))
                            .whenComplete((secured, secureFailure) -> runOnMainThread(() -> {
                                if (secureFailure != null) {
                                    if (!player.isOnline()) {
                                        plugin.getLogger().warning(
                                                "Deferring core repair receipt recovery until "
                                                        + "player rejoins: " + prepared.operationId());
                                        return;
                                    }
                                    restoreAndRollbackRepair(
                                            player,
                                            prepared,
                                            rootMessage(secureFailure));
                                    return;
                                }
                                if (!player.isOnline()) {
                                    return;
                                }
                                applyPreparedRepair(player, prepared);
                            }));
                }));
    }

    private void applyPreparedRepair(Player player, CoreRepairOperation prepared) {
        if (!player.isOnline()) {
            return;
        }
        if (expectedReceiptQuantity(prepared) > 0L
                && countReceiptItems(player, prepared.operationId())
                        < expectedReceiptQuantity(prepared)) {
            restoreAndRollbackRepair(
                    player,
                    prepared,
                    "修繕receiptの現物確認に失敗しました。");
            return;
        }
        databaseExecutor.submit(() -> repository.applyPreparedCoreRepair(
                        prepared.operationId(), Instant.now()))
                .whenComplete((result, applyFailure) -> runOnMainThread(() -> {
                    if (applyFailure != null) {
                        if (player.isOnline()) {
                            reconcileRepairReceipt(player, prepared);
                        }
                        return;
                    }
                    if (!player.isOnline()) {
                        return;
                    }
                    CoreRecord repaired = Objects.requireNonNull(result, "repair result")
                            .core().orElseThrow(
                                    () -> new IllegalStateException("修繕結果にコアがありません"));
                    if (expectedReceiptQuantity(prepared) == 0L) {
                        finishAppliedRepair(player, repaired);
                        return;
                    }
                    databaseExecutor.submit(() -> repository.markCoreRepairReceiptClearPending(
                                    prepared.operationId(), Instant.now()))
                            .whenComplete((pendingResult, pendingFailure) -> runOnMainThread(() -> {
                                if (pendingFailure != null) {
                                    if (player.isOnline()) {
                                        reconcileRepairReceipt(player, prepared);
                                    }
                                    return;
                                }
                                if (!player.isOnline()) {
                                    return;
                                }
                                // CLEAR_PENDING is durable before touching the physical inventory.
                                // A restart now can distinguish a not-yet-removed receipt from one
                                // that was already removed and can safely finish the clear.
                                if (!saveAfterReceiptRemoval(
                                        player, prepared.operationId())) {
                                    return;
                                }
                                databaseExecutor.submit(() -> repository.clearCoreRepairReceipt(
                                                prepared.operationId(), Instant.now()))
                                        .whenComplete((clearResult, clearFailure) ->
                                                runOnMainThread(() -> {
                                                    if (!player.isOnline()) {
                                                        return;
                                                    }
                                                    finishAppliedRepair(player, repaired);
                                                    if (clearFailure != null) {
                                                        player.sendMessage(Component.text(
                                                                "修繕receiptの後処理は再起動時に再試行されます。",
                                                                NamedTextColor.YELLOW));
                                                    }
                                                }));
                            }));
                }));
    }

    private void finishAppliedRepair(
            Player player,
            CoreRecord repaired) {
        cores.replace(repaired);
        repairInFlight.remove(player.getUniqueId());
        player.sendMessage(Component.text(
                "コアを修繕しました。HP: " + repaired.currentHitPoints()
                        + " / " + repaired.maximumHitPoints(),
                NamedTextColor.GREEN));
        openCoreGui(player, repaired.id());
    }

    private void rollbackPreparedRepair(
            Player player,
            CoreRepairOperation prepared,
            String reason) {
        databaseExecutor.submit(() -> repository.rollbackPreparedCoreRepair(
                        prepared.operationId(), Instant.now()))
                .whenComplete((ignored, rollbackFailure) -> runOnMainThread(() -> {
                    if (rollbackFailure != null) {
                        if (player.isOnline()) {
                            finishRepair(
                                    player,
                                    reason + " receiptのrollbackにも失敗しました: "
                                            + rootMessage(rollbackFailure));
                        }
                        return;
                    }
                    completeRolledBackRepair(player, prepared, reason);
                }));
    }

    private void reconcileRepairReceipt(Player player, CoreRepairOperation operation) {
        databaseExecutor.submit(() -> repository.findCoreRepairReceipt(operation.operationId()))
                .whenComplete((receipt, lookupFailure) -> runOnMainThread(() -> {
                    if (lookupFailure != null) {
                        plugin.getLogger().warning(
                                "Could not inspect core repair receipt "
                                        + operation.operationId() + ": "
                                        + rootMessage(lookupFailure));
                        return;
                    }
                    if (!player.isOnline()) {
                        return;
                    }
                    if (operation.state() == CoreRepairOperationState.ROLLED_BACK) {
                        if (receipt.isPresent()
                                && receipt.orElseThrow().state()
                                        == io.github.takenoha.towerdefense.persistence
                                                .CoreRepairReceiptState.RETURN_PENDING) {
                            completeRolledBackRepair(
                                    player,
                                    operation,
                                    "切断前に取り消した修繕素材を返却しました。");
                        } else if (receipt.isPresent()
                                && receipt.orElseThrow().state()
                                        == io.github.takenoha.towerdefense.persistence
                                                .CoreRepairReceiptState.RESTORED) {
                            if (countReceiptItems(player, operation.operationId()) > 0L) {
                                stripReceiptItemsInPlace(player, operation.operationId());
                                player.updateInventory();
                                player.saveData();
                            }
                            repairInFlight.remove(player.getUniqueId());
                        }
                        return;
                    }
                    if (operation.state() == CoreRepairOperationState.APPLIED) {
                        if (receipt.isPresent()
                                && receipt.orElseThrow().state()
                                        == io.github.takenoha.towerdefense.persistence
                                                .CoreRepairReceiptState.CLEARED) {
                            if (countReceiptItems(player, operation.operationId()) > 0L) {
                                saveAfterReceiptRemoval(player, operation.operationId());
                            }
                            repairInFlight.remove(player.getUniqueId());
                        } else if (receipt.isPresent()
                                && receipt.orElseThrow().state()
                                        == io.github.takenoha.towerdefense.persistence
                                                .CoreRepairReceiptState.SECURED) {
                            databaseExecutor.submit(() -> repository.markCoreRepairReceiptClearPending(
                                            operation.operationId(), Instant.now()))
                                    .whenComplete((ignored, pendingFailure) -> runOnMainThread(() -> {
                                        if (pendingFailure != null || !player.isOnline()) {
                                            return;
                                        }
                                        clearCoreRepairReceiptAfterSave(
                                                player, operation.operationId());
                                    }));
                        } else if (receipt.isPresent()
                                && receipt.orElseThrow().state()
                                        == io.github.takenoha.towerdefense.persistence.CoreRepairReceiptState.CLEAR_PENDING) {
                            clearCoreRepairReceiptAfterSave(
                                    player, operation.operationId());
                        } else if (receipt.isPresent()
                                && receipt.orElseThrow().state()
                                        == io.github.takenoha.towerdefense.persistence.CoreRepairReceiptState.RESERVED) {
                            plugin.getLogger().warning(
                                    "Applied core repair has an unresolved pre-handoff receipt; "
                                            + "leaving it for durable audit: " + operation.operationId());
                        }
                        return;
                    }
                    if (receipt.isEmpty()) {
                        rollbackPreparedRepair(
                                player, operation, "修繕receiptが見つからないため取り消しました。");
                        return;
                    }
                    io.github.takenoha.towerdefense.persistence.CoreRepairReceipt currentReceipt =
                            receipt.orElseThrow();
                    long secured = countReceiptItems(player, operation.operationId());
                    long receiptQuantity = currentReceipt.quantity();
                    if (secured >= receiptQuantity
                            && currentReceipt.state()
                                    == io.github.takenoha.towerdefense.persistence.CoreRepairReceiptState.RESERVED) {
                        databaseExecutor.submit(() -> repository.secureCoreRepairReceipt(
                                        operation.operationId(), Instant.now()))
                                .whenComplete((ignored, secureFailure) -> runOnMainThread(() -> {
                                    if (secureFailure != null) {
                                        restoreAndRollbackRepair(
                                                player, operation,
                                                rootMessage(secureFailure));
                                    } else if (player.isOnline()) {
                                        applyPreparedRepair(player, operation);
                                    }
                                }));
                        return;
                    }
                    if (secured >= receiptQuantity
                            && currentReceipt.state()
                                    == io.github.takenoha.towerdefense.persistence.CoreRepairReceiptState.SECURED) {
                        applyPreparedRepair(player, operation);
                        return;
                    }
                    if (currentReceipt.state()
                            == io.github.takenoha.towerdefense.persistence.CoreRepairReceiptState.SECURED) {
                        restoreAndRollbackRepair(
                                player, operation,
                                "修繕receiptの現物が不足していたため取り消しました。");
                    } else {
                        restoreAndRollbackRepair(
                                player, operation,
                                "修繕receiptの確保が完了していないため取り消しました。");
                    }
                }));
    }

    private long countReceiptItems(Player player, UUID operationId) {
        long total = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            if (repairReceipts.isFor(item, operationId)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void restoreAndRollbackRepair(
            Player player,
            CoreRepairOperation operation,
            String reason) {
        databaseExecutor.submit(() -> repository.rollbackPreparedCoreRepair(
                        operation.operationId(), Instant.now()))
                .whenComplete((ignored, rollbackFailure) -> runOnMainThread(() -> {
                    if (rollbackFailure != null) {
                        if (player.isOnline()) {
                            player.sendMessage(Component.text(
                                    reason + " receiptのrollbackにも失敗しました: "
                                            + rootMessage(rollbackFailure),
                                    NamedTextColor.RED));
                        }
                        return;
                    }
                    completeRolledBackRepair(player, operation, reason);
                }));
    }

    private void completeRolledBackRepair(
            Player player,
            CoreRepairOperation operation,
            String reason) {
        if (!player.isOnline()) {
            return;
        }
        databaseExecutor.submit(() -> repository.findCoreRepairReceipt(operation.operationId()))
                .whenComplete((receipt, lookupFailure) -> runOnMainThread(() -> {
                    if (lookupFailure != null) {
                        player.sendMessage(Component.text(
                                reason + " receiptの復旧確認に失敗しました。再接続後に再試行します。",
                                NamedTextColor.YELLOW));
                        return;
                    }
                    // The async lookup may complete after PlayerQuitEvent. Never mutate or save
                    // an offline Player object; RETURN_PENDING remains durable until join
                    // reconciliation can prove the physical handoff.
                    if (!player.isOnline()) {
                        return;
                    }
                    if (receipt != null
                            && receipt.isPresent()
                            && receipt.orElseThrow().state()
                                    == io.github.takenoha.towerdefense.persistence
                                            .CoreRepairReceiptState.RETURN_PENDING) {
                        stripReceiptItemsInPlace(player, operation.operationId());
                        try {
                            player.updateInventory();
                            player.saveData();
                        } catch (RuntimeException saveFailure) {
                            plugin.getLogger().log(
                                    java.util.logging.Level.WARNING,
                                    "Could not durably save a rolled-back core repair receipt "
                                            + operation.operationId(),
                                    saveFailure);
                            return;
                        }
                        databaseExecutor.submit(() -> repository.restoreCoreRepairReceipt(
                                        operation.operationId(), Instant.now()))
                                .whenComplete((restored, restoreFailure) -> runOnMainThread(() -> {
                                    if (restoreFailure != null) {
                                        plugin.getLogger().log(
                                                java.util.logging.Level.WARNING,
                                                "Could not complete core repair receipt restore "
                                                        + operation.operationId(),
                                                restoreFailure);
                                        return;
                                    }
                                    finishRepair(player, reason);
                                }));
                        return;
                    }
                    finishRepair(player, reason);
                }));
    }

    private void beginRelocation(Player player, UUID coreId) {
        if (sessions.hasActiveSession()) {
            player.sendMessage(Component.text("防衛戦中はコアを移設できません。", NamedTextColor.RED));
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            player.sendMessage(Component.text(
                    "移設先の固体ブロックを見てください。", NamedTextColor.YELLOW));
            return;
        }
        player.closeInventory();
        databaseExecutor.submit(() -> repository.findCore(coreId).orElseThrow(
                        () -> new IllegalStateException("移設対象のコアが見つかりません")))
                .whenComplete((core, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                        return;
                    }
                    coreItems.beginGuiRelocation(player, target, core);
                }));
    }

    private boolean secureReceiptItemsInPlace(
            Player player,
            Material material,
            CoreRepairOperation operation) {
        long materialQuantity = operation.vanillaMaterialAmount();
        long shardQuantity = operation.legacyDefenseShardAmount();
        // addItem/setItem below address storage slots only. Preflight the same slot domain so
        // armor/offhand capacity cannot make a full storage inventory look safely splittable.
        ItemStack[] contents = player.getInventory().getStorageContents();
        Optional<List<ReceiptInventoryPlanner.Extraction>> materialPlan =
                ReceiptInventoryPlanner.plan(
                        contents,
                        item -> item != null
                                && item.getType() == material
                                && !repairReceipts.isTagged(item),
                        materialQuantity,
                        material.name());
        Optional<List<ReceiptInventoryPlanner.Extraction>> shardPlan =
                ReceiptInventoryPlanner.plan(
                        contents,
                        item -> shardTagger.isShard(item) && !repairReceipts.isTagged(item),
                        shardQuantity,
                        "DEFENSE_SHARD");
        if (materialPlan.isEmpty() || shardPlan.isEmpty()) {
            return false;
        }
        List<ReceiptInventoryPlanner.Extraction> plan = new ArrayList<>();
        plan.addAll(materialPlan.orElseThrow());
        plan.addAll(shardPlan.orElseThrow());
        if (!ReceiptInventoryPlanner.canApply(contents, plan)) {
            return false;
        }
        List<ItemStack> remainders = new ArrayList<>();
        for (ReceiptInventoryPlanner.Extraction extraction : plan) {
            ItemStack current = player.getInventory().getItem(extraction.slot());
            ItemStack tagged = current.clone();
            tagged.setAmount(extraction.amount());
            player.getInventory().setItem(
                    extraction.slot(), repairReceipts.tag(tagged, operation.operationId()));
            int remainder = current.getAmount() - extraction.amount();
            if (remainder > 0) {
                ItemStack ordinary = current.clone();
                ordinary.setAmount(remainder);
                remainders.add(ordinary);
            }
        }
        for (ItemStack remainder : remainders) {
            if (!addWithoutDrop(player, remainder)) {
                throw new IllegalStateException(
                        "Receipt inventory preflight disagreed with the live inventory");
            }
        }
        return true;
    }

    private long countOrdinaryRepairMaterial(Player player, Material material) {
        long total = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material && !repairReceipts.isTagged(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private long countOrdinaryShards(Player player) {
        long total = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            if (shardTagger.isShard(item) && !repairReceipts.isTagged(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static String expectedReceiptMaterial(CoreRepairOperation operation) {
        return operation.paymentMode() == PaymentMode.LEGACY_ITEMS
                && operation.legacyDefenseShardAmount() > 0L
                ? "CORE_REPAIR_BUNDLE"
                : operation.vanillaMaterial();
    }

    private static long expectedReceiptQuantity(CoreRepairOperation operation) {
        return Math.addExact(
                operation.vanillaMaterialAmount(), operation.legacyDefenseShardAmount());
    }

    /** Removes receipt-tagged stacks and strips their PDC before they become ordinary items. */
    private List<ItemStack> takeReceiptItems(Player player, UUID operationId) {
        List<ItemStack> removed = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!repairReceipts.isFor(item, operationId)) {
                continue;
            }
            removed.add(repairReceipts.strip(item));
            player.getInventory().setItem(slot, null);
        }
        return List.copyOf(removed);
    }

    /** Strips a receipt in place when rollback returns the same physical stack to the owner. */
    private void stripReceiptItemsInPlace(Player player, UUID operationId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (repairReceipts.isFor(item, operationId)) {
                player.getInventory().setItem(slot, repairReceipts.strip(item));
            }
        }
    }

    /** Removes a paid receipt, saves the player data, and only then advances the DB state. */
    private boolean saveAfterReceiptRemoval(Player player, UUID operationId) {
        if (!player.isOnline()) {
            return false;
        }
        takeReceiptItems(player, operationId);
        try {
            player.updateInventory();
            player.saveData();
            return true;
        } catch (RuntimeException saveFailure) {
            plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Could not durably save cleared core repair receipt " + operationId,
                    saveFailure);
            return false;
        }
    }

    private void clearCoreRepairReceiptAfterSave(Player player, UUID operationId) {
        if (!saveAfterReceiptRemoval(player, operationId)) {
            return;
        }
        databaseExecutor.submit(() -> repository.clearCoreRepairReceipt(
                        operationId, Instant.now()))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(
                                java.util.logging.Level.WARNING,
                                "Could not clear core repair receipt " + operationId,
                                failure);
                    }
                });
    }

    private void refund(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            MapResult result = addOrDrop(player, item);
            if (!result.complete()) {
                plugin.getLogger().warning("Could not fully refund a failed core repair item");
            }
        }
    }

    private MapResult addOrDrop(Player player, ItemStack item) {
        java.util.HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        int left = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (left > 0) {
            leftovers.values().forEach(value -> player.getWorld().dropItemNaturally(
                    player.getLocation(), value));
        }
        return new MapResult(left == 0);
    }

    private boolean addWithoutDrop(Player player, ItemStack item) {
        return player.getInventory().addItem(item).isEmpty();
    }

    private void finishRepair(Player player, String message) {
        repairInFlight.remove(player.getUniqueId());
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private void runOnMainThread(Runnable action) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private static boolean isEmptyHand(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        if (root instanceof CompletionException && root.getCause() != null) {
            root = root.getCause();
        }
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String playerName(UUID playerId) {
        org.bukkit.OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString().substring(0, 8) : player.getName();
    }

    private record CoreGuiData(
            CoreRecord core,
            TeamRecord team,
            TeamProgress progress,
            CoreRepairCost repairCost,
            TeamResourceSnapshot resources) {
    }

    private record ResourceVaultData(
            TeamResourceSnapshot snapshot,
            boolean owner,
            boolean canWithdraw) {
    }

    private record TeamGuiData(CoreRecord core, TeamRecord team) {
    }

    private record TowerResearchGuiData(
            TeamProgress progress,
            List<io.github.takenoha.towerdefense.domain.TowerResearch> research) {
    }

    private record MapResult(boolean complete) {
    }
}
