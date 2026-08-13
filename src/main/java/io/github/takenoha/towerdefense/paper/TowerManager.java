package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.BattleBoost;
import io.github.takenoha.towerdefense.persistence.BattleBoostKind;
import io.github.takenoha.towerdefense.persistence.PersistenceConflictException;
import io.github.takenoha.towerdefense.persistence.PaymentMode;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import io.github.takenoha.towerdefense.persistence.TowerPlacement;
import io.github.takenoha.towerdefense.persistence.TowerRecord;
import io.github.takenoha.towerdefense.persistence.TowerDamageMutationResult;
import io.github.takenoha.towerdefense.persistence.TowerRemoval;
import io.github.takenoha.towerdefense.persistence.TowerRemovalState;
import io.github.takenoha.towerdefense.persistence.TowerRepository;
import io.github.takenoha.towerdefense.persistence.TowerUpgrade;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeReceipt;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeReceiptState;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeResult;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeState;
import io.github.takenoha.towerdefense.runtime.CoreAttackSchedule;
import io.github.takenoha.towerdefense.runtime.BattleBoostRegistry;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import io.github.takenoha.towerdefense.runtime.DefenseRuntimeStatus;
import io.github.takenoha.towerdefense.runtime.TaggedEnemy;
import io.github.takenoha.towerdefense.runtime.TowerRegistry;
import io.github.takenoha.towerdefense.tactical.EmptyTacticalEffectSnapshot;
import io.github.takenoha.towerdefense.tactical.TacticalEffectSnapshot;
import io.github.takenoha.towerdefense.tactical.TacticalEffectSnapshotProvider;
import io.github.takenoha.towerdefense.tactical.TacticalTargetContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;

/** Main-thread physical bridge, management flow, and combat loop for installed towers. */
public final class TowerManager implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final DefenseRepository defenseRepository;
    private final TowerRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final DefenseSessionManager sessions;
    private final CoreRegistry cores;
    private final TowerRegistry towers;
    private final TowerItemTagger itemTagger;
    private final TowerEntityTagger entityTagger;
    private final EventEnemyTagger eventEnemyTagger;
    private final ResourceRepository resources;
    private final DefenseShardTagger shardTagger;
    private final EnhancementCoreTagger enhancementCoreTagger;
    private final TowerUpgradeReceiptTagger upgradeReceipts;
    private final CombatArea combatArea;
    private final NamespacedKey towerDamageKey;
    private final Set<UUID> placementInFlight = new HashSet<>();
    private final Set<UUID> removalInFlight = new HashSet<>();
    private final Set<UUID> priorityInFlight = new HashSet<>();
    private final Set<UUID> upgradeInFlight = new HashSet<>();
    private final Set<UUID> pendingRemovalTowerIds = new HashSet<>();
    private final Set<UUID> appliedTowerIds;
    private final Map<UUID, CoreAttackSchedule> attackSchedules = new HashMap<>();
    private final Map<UUID, CoreAttackSchedule> enemyTowerAttackSchedules = new HashMap<>();
    private final Map<UUID, PendingTowerDamage> towerDamageInFlight = new HashMap<>();
    private final BattleBoostRegistry battleBoosts = new BattleBoostRegistry();
    private final Set<UUID> boostInFlight = new HashSet<>();
    private final TacticalEffectSnapshotProvider tacticalEffects;

    private BukkitTask tickTask;
    private long currentTick;

    public TowerManager(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository defenseRepository,
            TowerRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            TowerRegistry towers,
            TowerItemTagger itemTagger,
            TowerEntityTagger entityTagger) {
        this(
                plugin,
                settings,
                defenseRepository,
                repository,
                databaseExecutor,
                sessions,
                cores,
                towers,
                itemTagger,
                entityTagger,
                null,
                ignored -> EmptyTacticalEffectSnapshot.INSTANCE);
    }

    public TowerManager(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository defenseRepository,
            TowerRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            TowerRegistry towers,
            TowerItemTagger itemTagger,
            TowerEntityTagger entityTagger,
            ResourceRepository resources) {
        this(
                plugin,
                settings,
                defenseRepository,
                repository,
                databaseExecutor,
                sessions,
                cores,
                towers,
                itemTagger,
                entityTagger,
                resources,
                ignored -> EmptyTacticalEffectSnapshot.INSTANCE);
    }

    /** Full constructor used by the tactical build integration. */
    public TowerManager(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository defenseRepository,
            TowerRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            TowerRegistry towers,
            TowerItemTagger itemTagger,
            TowerEntityTagger entityTagger,
            ResourceRepository resources,
            TacticalEffectSnapshotProvider tacticalEffects) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.defenseRepository = Objects.requireNonNull(defenseRepository, "defenseRepository");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.towers = Objects.requireNonNull(towers, "towers");
        this.itemTagger = Objects.requireNonNull(itemTagger, "itemTagger");
        this.entityTagger = Objects.requireNonNull(entityTagger, "entityTagger");
        eventEnemyTagger = new EventEnemyTagger(plugin);
        this.resources = resources;
        this.tacticalEffects = Objects.requireNonNull(tacticalEffects, "tacticalEffects");
        shardTagger = new DefenseShardTagger(plugin);
        enhancementCoreTagger = new EnhancementCoreTagger(plugin);
        upgradeReceipts = new TowerUpgradeReceiptTagger(plugin);
        combatArea = new CombatArea(
                settings.combat().radius(),
                settings.combat().spawnInner(),
                settings.combat().spawnOuter(),
                settings.combat().minimumCoreDistance(),
                settings.combat().coreGap());
        towerDamageKey = new NamespacedKey(plugin, "tower_damage_touched");
        appliedTowerIds = new HashSet<>(repository.loadAppliedTowerIds());
        defenseRepository.activeEventId().ifPresent(eventId ->
                battleBoosts.replaceAll(defenseRepository.loadBattleBoosts(eventId)));
        for (TowerRemoval removal : repository.loadPendingTowerRemovals()) {
            pendingRemovalTowerIds.add(removal.towerId());
        }
    }

    /** Registers the provisional Arrow and Cannon recipes. */
    public void registerRecipe() {
        NamespacedKey arrowKey = TowerRecipeCatalog.key(plugin, TowerType.ARROW);
        ShapedRecipe arrowRecipe = new ShapedRecipe(
                arrowKey, itemTagger.recipeTemplate(TowerType.ARROW));
        arrowRecipe.shape("IRI", "RDR", "IRI");
        arrowRecipe.setIngredient('I', Material.IRON_INGOT);
        arrowRecipe.setIngredient('R', Material.REDSTONE);
        arrowRecipe.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(arrowRecipe);

        NamespacedKey cannonKey = TowerRecipeCatalog.key(plugin, TowerType.CANNON);
        ShapedRecipe cannonRecipe = new ShapedRecipe(
                cannonKey, itemTagger.recipeTemplate(TowerType.CANNON));
        cannonRecipe.shape("CGC", "GIG", "CGC");
        cannonRecipe.setIngredient('C', Material.COBBLESTONE);
        cannonRecipe.setIngredient('G', Material.GUNPOWDER);
        cannonRecipe.setIngredient('I', Material.IRON_INGOT);
        Bukkit.addRecipe(cannonRecipe);

        ShapedRecipe frostRecipe = new ShapedRecipe(
                TowerRecipeCatalog.key(plugin, TowerType.FROST),
                itemTagger.recipeTemplate(TowerType.FROST));
        frostRecipe.shape("PIP", "IDI", "PIP");
        frostRecipe.setIngredient('P', Material.PACKED_ICE);
        frostRecipe.setIngredient('I', Material.IRON_INGOT);
        frostRecipe.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(frostRecipe);

        ShapedRecipe lightningRecipe = new ShapedRecipe(
                TowerRecipeCatalog.key(plugin, TowerType.LIGHTNING),
                itemTagger.recipeTemplate(TowerType.LIGHTNING));
        lightningRecipe.shape("RER", "ECE", "RER");
        lightningRecipe.setIngredient('R', Material.REDSTONE);
        lightningRecipe.setIngredient('E', Material.ENDER_PEARL);
        lightningRecipe.setIngredient('C', Material.COPPER_INGOT);
        Bukkit.addRecipe(lightningRecipe);

        ShapedRecipe supportRecipe = new ShapedRecipe(
                TowerRecipeCatalog.key(plugin, TowerType.SUPPORT),
                itemTagger.recipeTemplate(TowerType.SUPPORT));
        supportRecipe.shape("GAG", "AEA", "GAG");
        supportRecipe.setIngredient('G', Material.GOLD_INGOT);
        supportRecipe.setIngredient('A', Material.AMETHYST_SHARD);
        supportRecipe.setIngredient('E', Material.EMERALD);
        Bukkit.addRecipe(supportRecipe);

        ShapedRecipe sniperRecipe = new ShapedRecipe(
                TowerRecipeCatalog.key(plugin, TowerType.SNIPER),
                itemTagger.recipeTemplate(TowerType.SNIPER));
        sniperRecipe.shape("FIF", "IEI", "FIF");
        sniperRecipe.setIngredient('F', Material.FEATHER);
        sniperRecipe.setIngredient('I', Material.IRON_INGOT);
        sniperRecipe.setIngredient('E', Material.ENDER_EYE);
        Bukkit.addRecipe(sniperRecipe);

        ShapedRecipe flameRecipe = new ShapedRecipe(
                TowerRecipeCatalog.key(plugin, TowerType.FLAME),
                itemTagger.recipeTemplate(TowerType.FLAME));
        flameRecipe.shape("BBB", "BFB", "BDB");
        flameRecipe.setIngredient('B', Material.BLAZE_POWDER);
        flameRecipe.setIngredient('F', Material.FIRE_CHARGE);
        flameRecipe.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(flameRecipe);
    }

    /** Restores only known prepared operations; unknown physical states remain fail-closed. */
    public void recoverPreparedPlacements() {
        for (TowerPlacement placement : repository.loadPendingTowerPlacements()) {
            if (recoverPhysicalPlacement(placement)) {
                databaseExecutor.execute(() -> repository.rollbackTowerPlacement(
                        placement.operationId(), Instant.now()));
            }
        }
    }

    /** Rolls back removal reservations left before their physical item handoff completed. */
    public void recoverPreparedRemovals() {
        for (TowerRemoval removal : repository.loadPendingTowerRemovals()) {
            pendingRemovalTowerIds.add(removal.towerId());
            databaseExecutor.submit(() -> repository.rollbackTowerRemoval(
                            removal.operationId(), Instant.now()))
                    .whenComplete((result, failure) -> runOnMainThread(() -> {
                        if (failure != null || result.isEmpty()) {
                            plugin.getLogger().log(
                                    java.util.logging.Level.SEVERE,
                                    "Could not recover prepared tower removal "
                                            + removal.operationId(),
                                    failure);
                            return;
                        }
                        TowerRemoval outcome = result.orElseThrow();
                        if (outcome.state() == TowerRemovalState.ROLLED_BACK) {
                            removeMatchingItems(removal.towerId());
                            pendingRemovalTowerIds.remove(removal.towerId());
                        } else if (outcome.state() == TowerRemovalState.APPLIED) {
                            finishAppliedRemoval(outcome);
                        }
                    }));
        }
    }

    /**
     * Reconciles legacy upgrade stop windows at startup. Existing PREPARED rows from the wallet
     * migration had no physical receipt and are rolled back fail-closed; receipt-bearing rows
     * remain until the owning player joins and the inventory can be inspected safely.
     */
    public void recoverPreparedUpgrades() {
        for (TowerUpgrade upgrade : repository.loadPreparedTowerUpgrades()) {
            List<TowerUpgradeReceipt> receipts = repository.findTowerUpgradeReceipts(
                    upgrade.operationId());
            if (receipts.isEmpty()) {
                databaseExecutor.submit(() -> repository.rollbackTowerUpgrade(
                        upgrade.operationId(), Instant.now()));
            } else {
                plugin.getLogger().info(
                        "Deferring legacy tower upgrade receipt reconciliation until player "
                                + upgrade.actorId() + " rejoins: " + upgrade.operationId());
            }
        }
    }

    /** Finishes the physical side of removals that committed immediately before a restart. */
    public void recoverAppliedRemovals() {
        for (TowerRemoval removal : repository.loadAppliedTowerRemovals()) {
            removePhysicalEntity(removal.entityId(), removal.towerId());
        }
    }

    public void startTicking() {
        if (tickTask != null) {
            throw new IllegalStateException("the tower tick task is already running");
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        Optional<TowerType> recipeType = itemTagger.recipeType(result);
        if (recipeType.isEmpty()) {
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Component.text(
                    "タワーは1個ずつクラフトしてください。", NamedTextColor.YELLOW));
            return;
        }
        event.setCurrentItem(itemTagger.create(UUID.randomUUID(), recipeType.orElseThrow(), 1));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        reconcilePreparedUpgradeReceipts(event.getPlayer());
        reconcileAppliedItems(event.getPlayer());
        for (UUID towerId : pendingRemovalTowerIds) {
            removeMatchingItemsFromPlayer(event.getPlayer(), towerId);
        }
    }

    /** Leaves receipt-bearing upgrade stop windows for join reconciliation instead of touching
     * the saved offline inventory from an async callback. */
    @EventHandler
    public void onUpgradeReceiptQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        databaseExecutor.submit(() -> repository.loadPreparedTowerUpgrades().stream()
                        .filter(upgrade -> upgrade.actorId().equals(playerId))
                        .toList())
                .whenComplete((upgrades, failure) -> {
                    if (failure != null || upgrades == null) {
                        return;
                    }
                    for (TowerUpgrade upgrade : upgrades) {
                        if (repository.findTowerUpgradeReceipts(upgrade.operationId()).isEmpty()) {
                            repository.rollbackTowerUpgrade(upgrade.operationId(), Instant.now());
                        }
                    }
                });
    }

    /**
     * Paper can pre-cancel a right-click when the vanilla item has no block-use action. Tower
     * items are intentionally non-placeable vanilla materials, so this handler must still see
     * that event and claim it for the plugin-owned placement flow.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Optional<TowerItemIdentity> identity = itemTagger.read(event.getItem());
        if (identity.isEmpty() || event.getClickedBlock() == null) {
            return;
        }
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        Block target = clicked.getRelative(event.getBlockFace());
        beginPlacement(event.getPlayer(), clicked, target, identity.orElseThrow());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptClick(InventoryClickEvent event) {
        if (containsUpgradeReceipt(event)) {
            event.setCancelled(true);
        }
    }

    private boolean containsUpgradeReceipt(InventoryClickEvent event) {
        ItemStack auxiliary = null;
        if (event.getWhoClicked() instanceof Player player) {
            if (event.getClick() == ClickType.NUMBER_KEY) {
                auxiliary = player.getInventory().getItem(event.getHotbarButton());
            } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                auxiliary = player.getInventory().getItemInOffHand();
            }
        }
        return ReceiptTransferPolicy.containsTagged(
                upgradeReceipts::isTagged,
                event.getCurrentItem(),
                event.getCursor(),
                auxiliary);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptDrag(InventoryDragEvent event) {
        if (upgradeReceipts.isTagged(event.getOldCursor())
                || event.getNewItems().values().stream().anyMatch(upgradeReceipts::isTagged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptMove(InventoryMoveItemEvent event) {
        if (upgradeReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptPickup(InventoryPickupItemEvent event) {
        if (upgradeReceipts.isTagged(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptEntityPickup(EntityPickupItemEvent event) {
        if (upgradeReceipts.isTagged(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptDrop(PlayerDropItemEvent event) {
        if (upgradeReceipts.isTagged(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptPlace(BlockPlaceEvent event) {
        if (upgradeReceipts.isTagged(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptDispense(BlockDispenseEvent event) {
        if (upgradeReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptInteract(PlayerInteractEvent event) {
        if (upgradeReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptInteractEntity(PlayerInteractEntityEvent event) {
        if (upgradeReceipts.isTagged(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptInteractAtEntity(PlayerInteractAtEntityEvent event) {
        onUpgradeReceiptInteractEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (upgradeReceipts.isTagged(event.getPlayerItem())
                || upgradeReceipts.isTagged(event.getArmorStandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptSwapHands(PlayerSwapHandItemsEvent event) {
        if (upgradeReceipts.isTagged(event.getMainHandItem())
                || upgradeReceipts.isTagged(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptConsume(PlayerItemConsumeEvent event) {
        if (upgradeReceipts.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptCraft(CraftItemEvent event) {
        if (java.util.Arrays.stream(event.getInventory().getMatrix())
                .anyMatch(upgradeReceipts::isTagged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter
                && java.util.Arrays.stream(crafter.getInventory().getContents())
                        .anyMatch(upgradeReceipts::isTagged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptDeath(PlayerDeathEvent event) {
        var iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (upgradeReceipts.isTagged(item)) {
                event.getItemsToKeep().add(item.clone());
                iterator.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptMerge(ItemMergeEvent event) {
        if (upgradeReceipts.isTagged(event.getEntity().getItemStack())
                || upgradeReceipts.isTagged(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptDespawn(ItemDespawnEvent event) {
        if (upgradeReceipts.isTagged(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item
                && upgradeReceipts.isTagged(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof Item item
                && upgradeReceipts.isTagged(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUpgradeReceiptTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof Item item
                && upgradeReceipts.isTagged(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Optional<TowerEntityIdentity> identity = entityTagger.read(event.getRightClicked());
        if (identity.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            openTowerGui(
                    event.getPlayer(),
                    identity.orElseThrow(),
                    event.getRightClicked().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof TowerManagementInventoryHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Optional<TowerTargetPriority> targetPriority =
                TowerManagementGui.priorityAt(event.getRawSlot());
        if (event.getRawSlot() == TowerManagementGui.CLOSE_SLOT) {
            player.closeInventory();
        } else if (event.getRawSlot() == TowerManagementGui.BOOST_POWER_SLOT) {
            beginBattleBoost(player, holder.towerId(), BattleBoostKind.POWER);
        } else if (event.getRawSlot() == TowerManagementGui.BOOST_SPEED_SLOT) {
            beginBattleBoost(player, holder.towerId(), BattleBoostKind.SPEED);
        } else if (event.getRawSlot() == TowerManagementGui.BOOST_RANGE_SLOT) {
            beginBattleBoost(player, holder.towerId(), BattleBoostKind.RANGE);
        } else if (event.getRawSlot() == TowerManagementGui.REPAIR_SLOT) {
            beginTowerRepair(player, holder.towerId());
        } else if (targetPriority.isPresent()) {
            setTargetPriority(player, holder.towerId(), targetPriority.orElseThrow());
        } else if (event.getRawSlot() == TowerManagementGui.UPGRADE_SLOT) {
            beginUpgrade(player, holder.towerId(), false);
        } else if (event.getRawSlot() == TowerManagementGui.LEGACY_UPGRADE_SLOT) {
            beginUpgrade(player, holder.towerId(), true);
        } else if (event.getRawSlot() == TowerManagementGui.REMOVE_SLOT) {
            beginRemoval(player, holder.towerId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof TowerManagementInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTowerDamage(EntityDamageEvent event) {
        if (entityTagger.read(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTowerAttack(EntityDamageByEntityEvent event) {
        if (entityTagger.read(event.getDamager()).isPresent()
                && event.getEntity() instanceof LivingEntity living
                && !(event.getEntity() instanceof Player)) {
            living.getPersistentDataContainer().set(
                    towerDamageKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        if (entityTagger.read(event.getEntity()).isPresent()) {
            plugin.getLogger().warning(
                    "A registered tower entity died unexpectedly: "
                            + event.getEntity().getUniqueId());
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        if (event.getEntity().getPersistentDataContainer().has(
                towerDamageKey, PersistentDataType.BYTE)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() != null && entityTagger.read(event.getTarget()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (touchesTower(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (touchesTower(event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::touchesTower);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::touchesTower);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::touchesTower)
                || touchesTower(event.getBlock().getRelative(event.getDirection()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::touchesTower)
                || touchesTower(event.getBlock().getRelative(event.getDirection()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (touchesTower(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            entityTagger.read(entity).ifPresent(identity -> {
                Optional<TowerRecord> tower = towers.find(identity.towerId());
                if (tower.isEmpty()
                        || !tower.orElseThrow().entityId().equals(entity.getUniqueId())) {
                    entity.remove();
                }
            });
        }
    }

    private void openTowerGui(
            Player player,
            TowerEntityIdentity identity,
            UUID physicalEntityId) {
        TowerRecord cached = towers.find(identity.towerId()).orElse(null);
        if (cached == null || !cached.entityId().equals(physicalEntityId)) {
            if (cached == null) {
                player.sendMessage(Component.text(
                        "このタワーは永続データに存在しません。", NamedTextColor.RED));
            }
            return;
        }
        databaseExecutor.submit(() -> {
            TowerRecord tower = repository.findTower(identity.towerId()).orElseThrow(
                    () -> new IllegalStateException("このタワーは永続データに存在しません"));
            if (!tower.entityId().equals(physicalEntityId)) {
                throw new IllegalStateException("タワー本体の識別情報が一致しません");
            }
            var team = defenseRepository.findTeam(tower.teamId()).orElseThrow(
                    () -> new IllegalStateException("タワーのチームが永続データに存在しません"));
            if (!team.members().contains(player.getUniqueId())) {
                throw new IllegalStateException("このタワーを操作できるチームメンバーではありません");
            }
            var research = repository.findTowerResearch(tower.teamId(), tower.type()).orElseThrow(
                    () -> new IllegalStateException("タワー研究データが見つかりません"));
            Optional<UUID> eventId = defenseRepository.activeEventId();
            List<BattleBoost> boosts = eventId.isPresent()
                    ? defenseRepository.loadBattleBoosts(eventId.orElseThrow())
                    : List.of();
            long battleFunds = eventId.isPresent()
                    ? defenseRepository.loadBattleFunds(eventId.orElseThrow()).balance()
                    : 0L;
            TeamResourceSnapshot resourceSnapshot = resources == null
                    ? new TeamResourceSnapshot(tower.teamId(), 0L, 0L, 0L, 0L)
                    : resources.load(tower.teamId(), player.getUniqueId());
            return new TowerGuiData(
                    tower,
                    research.researchLevel(),
                    eventId,
                    battleFunds,
                    boosts,
                    resourceSnapshot);
        }).whenComplete((data, failure) -> runOnMainThread(() -> {
            if (failure != null || !player.isOnline()) {
                if (failure == null) {
                    return;
                }
                player.sendMessage(Component.text(rootMessage(failure), NamedTextColor.RED));
                return;
            }
            boolean canRemove = !sessions.hasActiveSession();
            String reason = canRemove
                    ? ""
                    : "防衛戦開始後は回収・移設できません。";
            int shardCost = data.tower().individualLevel() < data.researchLevel()
                    ? settings.towers().individualUpgradeShardCost(
                            data.tower().individualLevel())
                    : 0;
            int coreCost = data.tower().individualLevel() < data.researchLevel()
                    ? settings.towers().individualUpgradeCoreCost(
                            data.tower().individualLevel())
                    : 0;
            battleBoosts.replaceAll(data.boosts());
            Map<BattleBoostKind, BattleBoost> boosts = new HashMap<>();
            for (BattleBoost boost : data.boosts()) {
                if (boost.towerId().equals(data.tower().id())) {
                    boosts.put(boost.kind(), boost);
                }
            }
            boolean canBuyBoost = data.eventId().isPresent()
                    && sessions.maySpendBattleFunds(data.tower().teamId());
            int powerCost = nextBoostCost(boosts, BattleBoostKind.POWER);
            int speedCost = nextBoostCost(boosts, BattleBoostKind.SPEED);
            int rangeCost = nextBoostCost(boosts, BattleBoostKind.RANGE);
            boolean canSpendBattleFunds = data.eventId().isPresent()
                    && sessions.maySpendBattleFunds(data.tower().teamId());
            long repairAmount = Math.min(
                    data.tower().maximumHitPoints() - data.tower().currentHitPoints(),
                    settings.towers().battleRepairHealthPerPurchase());
            long repairCostLong = repairAmount * settings.towers().battleRepairFundsPerHealth();
            repairCostLong = tacticalRepairCost(
                    repairCostLong,
                    tacticalEffectsForEvent(data.eventId()));
            int repairCost = repairCostLong > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : (int) repairCostLong;
            player.openInventory(TowerManagementGui.create(
                    data.tower(),
                    canRemove,
                    reason,
                    data.researchLevel(),
                    shardCost,
                    coreCost,
                    canSpendBattleFunds,
                    data.battleFunds(),
                    boosts,
                    powerCost,
                    speedCost,
                    rangeCost,
                    canSpendBattleFunds,
                    data.tower().currentHitPoints(),
                    data.tower().maximumHitPoints(),
                    repairCost,
                    data.resources(),
                    settings.rewards().legacyResourcePaymentsEnabled()));
        }));
    }

    private int nextBoostCost(Map<BattleBoostKind, BattleBoost> boosts, BattleBoostKind kind) {
        BattleBoost current = boosts.get(kind);
        int currentLevel = current == null ? 0 : current.level();
        if (currentLevel >= settings.towers().battleBoostStackLimit()) {
            return 0;
        }
        return settings.towers().battleBoostCost(currentLevel);
    }

    private void beginBattleBoost(
            Player player,
            UUID towerId,
            BattleBoostKind kind) {
        TowerRecord tower = towers.find(towerId).orElse(null);
        if (tower == null) {
            player.sendMessage(Component.text(
                    "ブースト対象のタワーが見つかりません。", NamedTextColor.RED));
            return;
        }
        if (!sessions.maySpendBattleFunds(tower.teamId())) {
            player.sendMessage(Component.text(
                    "戦闘ブーストは準備時間またはウェーブ間だけ購入できます。",
                    NamedTextColor.RED));
            return;
        }
        if (!boostInFlight.add(towerId)) {
            player.sendMessage(Component.text("戦闘ブーストを処理中です。", NamedTextColor.YELLOW));
            return;
        }
        UUID actorId = player.getUniqueId();
        player.sendMessage(Component.text("戦闘ブーストを購入しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            UUID eventId = defenseRepository.activeEventId().orElseThrow(
                    () -> new IllegalStateException("防衛戦が見つかりません"));
            List<BattleBoost> existing = defenseRepository.loadBattleBoosts(eventId);
            int currentLevel = existing.stream()
                    .filter(boost -> boost.towerId().equals(towerId) && boost.kind() == kind)
                    .mapToInt(BattleBoost::level)
                    .findFirst()
                    .orElse(0);
            if (currentLevel >= settings.towers().battleBoostStackLimit()) {
                throw new IllegalStateException("このタワーのブースト上限に達しています");
            }
            return defenseRepository.purchaseBattleBoost(
                    eventId,
                    tower.teamId(),
                    actorId,
                    towerId,
                    kind,
                    settings.towers().battleBoostCost(currentLevel),
                    boostMultiplier(kind),
                    UUID.randomUUID(),
                    Instant.now());
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            boostInFlight.remove(towerId);
            if (failure != null) {
                player.sendMessage(Component.text(
                        "戦闘ブーストを購入できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            battleBoosts.replace(result.boost());
            player.sendMessage(Component.text(
                    "戦闘ブースト（" + kind.id() + "）をLv" + result.boost().level()
                            + "へ上げました。残高: " + result.funds().balance(),
                    NamedTextColor.GREEN));
            openTowerGui(
                    player,
                    new TowerEntityIdentity(
                            tower.id(),
                            tower.teamId(),
                            tower.type(),
                            tower.individualLevel()),
                    tower.entityId());
        }));
    }

    private double boostMultiplier(BattleBoostKind kind) {
        return switch (kind) {
            case POWER -> settings.towers().battleBoostPowerMultiplier();
            case SPEED -> settings.towers().battleBoostSpeedMultiplier();
            case RANGE -> settings.towers().battleBoostRangeMultiplier();
        };
    }

    private void beginTowerRepair(Player player, UUID towerId) {
        TowerRecord tower = towers.find(towerId).orElse(null);
        if (tower == null) {
            player.sendMessage(Component.text(
                    "修理対象のタワーが見つかりません。", NamedTextColor.RED));
            return;
        }
        if (!sessions.maySpendBattleFunds(tower.teamId())) {
            player.sendMessage(Component.text(
                    "タワー修理は準備時間またはウェーブ間だけ実行できます。",
                    NamedTextColor.RED));
            return;
        }
        long missing = tower.maximumHitPoints() - tower.currentHitPoints();
        if (missing <= 0L) {
            player.sendMessage(Component.text("このタワーはHP満タンです。", NamedTextColor.YELLOW));
            return;
        }
        long repaired = Math.min(missing, settings.towers().battleRepairHealthPerPurchase());
        long baseCost = Math.multiplyExact(repaired, settings.towers().battleRepairFundsPerHealth());
        if (!boostInFlight.add(towerId)) {
            player.sendMessage(Component.text("タワー修理を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("タワー修理を処理しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            UUID eventId = defenseRepository.activeEventId().orElseThrow(
                    () -> new IllegalStateException("防衛戦が見つかりません"));
            long cost = tacticalRepairCost(
                    baseCost,
                    tacticalEffectsForEvent(Optional.of(eventId)));
            return defenseRepository.repairTowerWithBattleFunds(
                    eventId,
                    tower.teamId(),
                    player.getUniqueId(),
                    towerId,
                    repaired,
                    cost,
                    UUID.randomUUID(),
                    Instant.now());
        }).whenComplete((result, failure) -> runOnMainThread(() -> {
            boostInFlight.remove(towerId);
            if (failure != null) {
                player.sendMessage(Component.text(
                        "タワーを修理できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            TowerRecord updated = tower.withCurrentHitPoints(
                    result.durability().currentHitPoints(), Instant.now());
            towers.replace(updated);
            player.sendMessage(Component.text(
                    "タワーを" + result.durability().currentHitPoints()
                            + " / " + result.durability().maximumHitPoints()
                            + " HPへ修理しました。残高: " + result.funds().balance(),
                    NamedTextColor.GREEN));
            openTowerGui(
                    player,
                    new TowerEntityIdentity(
                            updated.id(),
                            updated.teamId(),
                            updated.type(),
                            updated.individualLevel()),
                    updated.entityId());
        }));
    }

    private void setTargetPriority(
            Player player,
            UUID towerId,
            TowerTargetPriority targetPriority) {
        if (pendingRemovalTowerIds.contains(towerId) || removalInFlight.contains(towerId)) {
            player.sendMessage(Component.text(
                    "タワー回収処理中のため対象優先を変更できません。", NamedTextColor.YELLOW));
            return;
        }
        if (!priorityInFlight.add(towerId)) {
            player.sendMessage(Component.text("対象優先を保存中です。", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("対象優先を保存しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> repository.updateTargetPriority(
                        towerId,
                        player.getUniqueId(),
                        targetPriority,
                        Instant.now()))
                .whenComplete((updated, failure) -> runOnMainThread(() -> {
                    priorityInFlight.remove(towerId);
                    if (failure != null) {
                        player.sendMessage(Component.text(
                                "対象優先を変更できません: " + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    towers.replace(updated);
                    Object openHolder = player.getOpenInventory().getTopInventory().getHolder();
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!(openHolder instanceof TowerManagementInventoryHolder holder)
                            || !holder.towerId().equals(towerId)) {
                        return;
                    }
                    openTowerGui(
                            player,
                            new TowerEntityIdentity(
                                    updated.id(),
                                    updated.teamId(),
                                    updated.type(),
                                    updated.individualLevel()),
                            updated.entityId());
                    player.sendMessage(Component.text(
                            "対象優先を「" + updated.targetPriority().displayName() + "」に変更しました。",
                            NamedTextColor.GREEN));
                }));
    }

    private void beginUpgrade(Player player, UUID towerId, boolean explicitLegacy) {
        if (pendingRemovalTowerIds.contains(towerId) || removalInFlight.contains(towerId)) {
            player.sendMessage(Component.text(
                    "タワー回収処理中のため強化できません。", NamedTextColor.YELLOW));
            return;
        }
        TowerRecord tower = towers.find(towerId).orElse(null);
        if (tower == null) {
            player.sendMessage(Component.text(
                    "強化対象のタワーが見つかりません。", NamedTextColor.RED));
            return;
        }
        if (!sessions.mayUpgradeTower(tower.teamId())) {
            player.sendMessage(Component.text(
                    "個体Lv強化は戦闘外またはウェーブ間準備時間だけ可能です。",
                    NamedTextColor.RED));
            return;
        }
        if (!upgradeInFlight.add(towerId)) {
            player.sendMessage(Component.text("タワー強化を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        int shardCost = settings.towers().individualUpgradeShardCost(tower.individualLevel());
        int coreCost = settings.towers().individualUpgradeCoreCost(tower.individualLevel());
        player.sendMessage(Component.text("個体Lv強化を準備しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            TeamResourceSnapshot snapshot = resources == null
                    ? new TeamResourceSnapshot(tower.teamId(), 0L, 0L, 0L, 0L)
                    : resources.load(tower.teamId(), player.getUniqueId());
            boolean walletPayment = snapshot.defensePoints() >= shardCost
                    && snapshot.enhancementPoints() >= coreCost;
            return PaymentSelectionPolicy.choose(
                    explicitLegacy,
                    walletPayment,
                    settings.rewards().legacyResourcePaymentsEnabled());
        }).whenComplete((paymentMode, quoteFailure) -> runOnMainThread(() -> {
            if (quoteFailure != null) {
                finishUpgrade(player, towerId, rootMessage(quoteFailure));
                return;
            }
            if (!player.isOnline()) {
                upgradeInFlight.remove(towerId);
                return;
            }
            if (paymentMode == PaymentMode.LEGACY_ITEMS) {
                player.sendMessage(Component.text(
                        explicitLegacy
                                ? "旧素材支払いを明示的に選択しました（旧方式は廃止予定です）。"
                                : "ポイント不足のため旧素材支払いを使用します（旧方式は廃止予定です）。",
                        NamedTextColor.YELLOW));
            }
            UUID operationId = UUID.randomUUID();
            if (paymentMode == PaymentMode.LEGACY_ITEMS) {
                plugin.getLogger().info(
                        "Legacy tower upgrade payment selected actor=" + player.getUniqueId()
                                + " team=" + tower.teamId()
                                + " operation=" + operationId
                                + " cost=" + shardCost + "+" + coreCost
                                + " mode=" + paymentMode);
            }
            TowerUpgrade request = paymentMode == PaymentMode.POINT_WALLET
                    ? TowerUpgrade.preparedWallet(
                            operationId,
                            tower,
                            player.getUniqueId(),
                            shardCost,
                            coreCost,
                            Instant.now())
                    : TowerUpgrade.prepared(
                            operationId,
                            tower,
                            player.getUniqueId(),
                            shardCost,
                            coreCost,
                            Instant.now());
            databaseExecutor.submit(() -> repository.prepareTowerUpgrade(request))
                    .whenComplete((prepared, prepareFailure) -> runOnMainThread(() -> {
                        if (prepareFailure != null) {
                            finishUpgrade(player, towerId,
                                    "タワーを強化できません: " + rootMessage(prepareFailure));
                            return;
                        }
                        if (prepared.state() != TowerUpgradeState.PREPARED
                                || !sessions.mayUpgradeTower(tower.teamId())
                                || !currentTowerEntityMatches(tower)
                                || !player.isOnline()) {
                            rollbackUpgrade(
                                    player,
                                    prepared,
                                    "強化前に対象または防衛フェーズが変わったため取り消しました。");
                            return;
                        }
                        if (prepared.paymentMode() == PaymentMode.LEGACY_ITEMS) {
                            beginLegacyUpgradeReceipt(player, prepared);
                        } else {
                            applyWalletUpgrade(player, prepared);
                        }
                    }));
        }));
    }

    private void applyWalletUpgrade(Player player, TowerUpgrade prepared) {
        databaseExecutor.submit(() -> repository.applyTowerUpgradeFromWallet(
                        prepared.operationId(), Instant.now()))
                .whenComplete((result, applyFailure) -> runOnMainThread(() -> {
                    if (applyFailure != null) {
                        rollbackUpgrade(
                                player,
                                prepared,
                                "強化を永続化できなかったためポイントは消費されていません: "
                                        + rootMessage(applyFailure));
                        return;
                    }
                    finishAppliedUpgrade(player, prepared, result.tower().orElse(null));
                }));
    }

    private void beginLegacyUpgradeReceipt(Player player, TowerUpgrade prepared) {
        databaseExecutor.submit(() -> repository.reserveTowerUpgradeReceipts(
                        prepared.operationId(), player.getUniqueId(), Instant.now()))
                .whenComplete((receipts, reserveFailure) -> runOnMainThread(() -> {
                    if (reserveFailure != null) {
                        rollbackUpgrade(player, prepared, rootMessage(reserveFailure));
                        return;
                    }
                    if (!player.isOnline() || !secureUpgradeItemsInPlace(player, prepared)) {
                        rollbackLegacyUpgrade(
                                player,
                                prepared,
                                "強化素材を安全に確保できなかったため取り消しました。");
                        return;
                    }
                    databaseExecutor.submit(() -> repository.secureTowerUpgradeReceipts(
                                    prepared.operationId(), Instant.now()))
                            .whenComplete((secured, secureFailure) -> runOnMainThread(() -> {
                                if (secureFailure != null) {
                                    if (!player.isOnline()) {
                                        plugin.getLogger().warning(
                                                "Deferring tower upgrade receipt recovery until "
                                                        + "player rejoins: " + prepared.operationId());
                                        return;
                                    }
                                    rollbackLegacyUpgrade(
                                            player,
                                            prepared,
                                            rootMessage(secureFailure));
                                    return;
                                }
                                if (!player.isOnline()) {
                                    return;
                                }
                                applyLegacyUpgrade(player, prepared);
                            }));
                }));
    }

    private void applyLegacyUpgrade(Player player, TowerUpgrade prepared) {
        if (!player.isOnline()) {
            return;
        }
        if (!hasUpgradeReceiptItems(player, prepared)) {
            rollbackLegacyUpgrade(
                    player,
                    prepared,
                    "強化receiptの現物確認に失敗したため取り消しました。");
            return;
        }
        databaseExecutor.submit(() -> repository.applyTowerUpgrade(
                        prepared.operationId(), Instant.now()))
                .whenComplete((result, applyFailure) -> runOnMainThread(() -> {
                    if (applyFailure != null) {
                        rollbackLegacyUpgrade(player, prepared, rootMessage(applyFailure));
                        return;
                    }
                    databaseExecutor.submit(() -> repository.markTowerUpgradeReceiptsClearPending(
                                    prepared.operationId(), Instant.now()))
                            .whenComplete((pendingResult, pendingFailure) ->
                                    runOnMainThread(() -> {
                                        if (pendingFailure != null) {
                                            if (player.isOnline()) {
                                                reconcilePreparedUpgradeReceipt(player, prepared);
                                            }
                                            return;
                                        }
                                        if (!player.isOnline()) {
                                            return;
                                        }
                                        // CLEAR_PENDING is durable before physical removal, so a
                                        // restart can safely finish the receipt clear either way.
                                        if (!saveAfterUpgradeReceiptRemoval(
                                                player, prepared.operationId())) {
                                            return;
                                        }
                                        databaseExecutor.submit(() -> repository.clearTowerUpgradeReceipts(
                                                        prepared.operationId(), Instant.now()));
                                        finishAppliedUpgrade(player, prepared, result.tower().orElse(null));
                                    }));
                }));
    }

    private void finishAppliedUpgrade(
            Player player,
            TowerUpgrade prepared,
            TowerRecord updated) {
        if (updated == null) {
            finishUpgrade(player, prepared.towerId(),
                    "強化結果のタワーを確認できません。管理者へ連絡してください。");
            return;
        }
        upgradeInFlight.remove(prepared.towerId());
        towers.replace(updated);
        Entity entity = Bukkit.getEntity(updated.entityId());
        if (entity != null) {
            entityTagger.tag(entity, new TowerEntityIdentity(
                    updated.id(), updated.teamId(), updated.type(), updated.individualLevel()));
        }
        player.sendMessage(Component.text(
                "タワーを個体Lv" + updated.individualLevel() + "へ強化しました。",
                NamedTextColor.GREEN));
        openTowerGui(
                player,
                new TowerEntityIdentity(
                        updated.id(), updated.teamId(), updated.type(), updated.individualLevel()),
                updated.entityId());
    }

    private boolean secureUpgradeItemsInPlace(Player player, TowerUpgrade upgrade) {
        // addItem/setItem below address storage slots only. Preflight the same slot domain so
        // armor/offhand capacity cannot make a full storage inventory look safely splittable.
        ItemStack[] contents = player.getInventory().getStorageContents();
        Optional<List<ReceiptInventoryPlanner.Extraction>> shardPlan =
                ReceiptInventoryPlanner.plan(
                        contents,
                        item -> shardTagger.isShard(item) && !upgradeReceipts.isTagged(item),
                        upgrade.defenseShardCost(),
                        "DEFENSE_SHARD");
        Optional<List<ReceiptInventoryPlanner.Extraction>> corePlan =
                ReceiptInventoryPlanner.plan(
                        contents,
                        item -> enhancementCoreTagger.isEnhancementCore(item)
                                && !upgradeReceipts.isTagged(item),
                        upgrade.enhancementCoreCost(),
                        "ENHANCEMENT_CORE");
        if (shardPlan.isEmpty() || corePlan.isEmpty()) {
            return false;
        }
        List<ReceiptInventoryPlanner.Extraction> plan = new ArrayList<>();
        plan.addAll(shardPlan.orElseThrow());
        plan.addAll(corePlan.orElseThrow());
        if (!ReceiptInventoryPlanner.canApply(contents, plan)) {
            return false;
        }
        List<ItemStack> remainders = new ArrayList<>();
        for (ReceiptInventoryPlanner.Extraction extraction : plan) {
            ItemStack current = player.getInventory().getItem(extraction.slot());
            ItemStack tagged = current.clone();
            tagged.setAmount(extraction.amount());
            player.getInventory().setItem(
                    extraction.slot(),
                    upgradeReceipts.tag(tagged, upgrade.operationId(), extraction.material()));
            int remainder = current.getAmount() - extraction.amount();
            if (remainder > 0) {
                ItemStack ordinary = current.clone();
                ordinary.setAmount(remainder);
                remainders.add(ordinary);
            }
        }
        for (ItemStack remainder : remainders) {
            if (!giveOrDropWithoutLoss(player, remainder)) {
                throw new IllegalStateException(
                        "Receipt inventory preflight disagreed with the live inventory");
            }
        }
        return true;
    }

    private boolean hasUpgradeReceiptItems(Player player, TowerUpgrade upgrade) {
        return countReceiptItems(player, upgrade.operationId(), "DEFENSE_SHARD")
                        >= upgrade.defenseShardCost()
                && countReceiptItems(player, upgrade.operationId(), "ENHANCEMENT_CORE")
                        >= upgrade.enhancementCoreCost();
    }

    private long countReceiptItems(Player player, UUID operationId, String material) {
        long total = 0L;
        for (ItemStack item : player.getInventory().getContents()) {
            if (upgradeReceipts.isFor(item, operationId, material)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private List<ItemStack> takeUpgradeReceiptItems(Player player, UUID operationId) {
        List<ItemStack> removed = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!upgradeReceipts.operationId(item).filter(operationId::equals).isPresent()) {
                continue;
            }
            removed.add(upgradeReceipts.strip(item));
            player.getInventory().setItem(slot, null);
        }
        return List.copyOf(removed);
    }

    private boolean hasAnyUpgradeReceiptItems(Player player, UUID operationId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (upgradeReceipts.operationId(item).filter(operationId::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private void stripUpgradeReceiptItemsInPlace(Player player, UUID operationId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (upgradeReceipts.operationId(item).filter(operationId::equals).isPresent()) {
                player.getInventory().setItem(slot, upgradeReceipts.strip(item));
            }
        }
    }

    private void removeUpgradeReceiptItems(Player player, UUID operationId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (upgradeReceipts.operationId(item).filter(operationId::equals).isPresent()) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private boolean saveAfterUpgradeReceiptRemoval(Player player, UUID operationId) {
        if (!player.isOnline()) {
            return false;
        }
        removeUpgradeReceiptItems(player, operationId);
        try {
            player.updateInventory();
            player.saveData();
            return true;
        } catch (RuntimeException saveFailure) {
            plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Could not durably save cleared tower upgrade receipt " + operationId,
                    saveFailure);
            return false;
        }
    }

    private void clearTowerUpgradeReceiptsAfterSave(Player player, UUID operationId) {
        if (!saveAfterUpgradeReceiptRemoval(player, operationId)) {
            return;
        }
        databaseExecutor.submit(() -> repository.clearTowerUpgradeReceipts(
                        operationId, Instant.now()))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(
                                java.util.logging.Level.WARNING,
                                "Could not clear tower upgrade receipts " + operationId,
                                failure);
                    }
                });
    }

    private void rollbackLegacyUpgrade(
            Player player,
            TowerUpgrade upgrade,
            String message) {
        databaseExecutor.submit(() -> {
            return repository.rollbackTowerUpgrade(upgrade.operationId(), Instant.now());
        }).whenComplete((ignored, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not roll back legacy tower upgrade " + upgrade.operationId(),
                        failure);
                if (player.isOnline()) {
                    player.sendMessage(Component.text(
                            "タワー強化の復旧を保留しています。管理者へ連絡してください。",
                            NamedTextColor.RED));
                }
                return;
            }
            completeRolledBackUpgrade(player, upgrade, message);
        }));
    }

    private void completeRolledBackUpgrade(
            Player player,
            TowerUpgrade upgrade,
            String message) {
        if (!player.isOnline()) {
            return;
        }
        databaseExecutor.submit(() -> repository.findTowerUpgradeReceipts(
                        upgrade.operationId()))
                .whenComplete((receipts, lookupFailure) -> runOnMainThread(() -> {
                    if (lookupFailure != null) {
                        plugin.getLogger().log(
                                java.util.logging.Level.WARNING,
                                "Could not inspect rolled-back tower receipts "
                                        + upgrade.operationId(),
                                lookupFailure);
                        return;
                    }
                    // The async lookup may complete after PlayerQuitEvent. Never mutate or save
                    // an offline Player object; RETURN_PENDING remains durable until join
                    // reconciliation can prove the physical handoff.
                    if (!player.isOnline()) {
                        return;
                    }
                    if (receipts.stream().anyMatch(receipt ->
                            receipt.state() == TowerUpgradeReceiptState.RETURN_PENDING)) {
                        stripUpgradeReceiptItemsInPlace(player, upgrade.operationId());
                        try {
                            player.updateInventory();
                            player.saveData();
                        } catch (RuntimeException saveFailure) {
                            plugin.getLogger().log(
                                    java.util.logging.Level.WARNING,
                                    "Could not durably save rolled-back tower receipt "
                                            + upgrade.operationId(),
                                    saveFailure);
                            return;
                        }
                        databaseExecutor.submit(() -> repository.restoreTowerUpgradeReceipts(
                                        upgrade.operationId(), Instant.now()))
                                .whenComplete((restored, restoreFailure) -> runOnMainThread(() -> {
                                    if (restoreFailure != null) {
                                        plugin.getLogger().log(
                                                java.util.logging.Level.WARNING,
                                                "Could not complete tower receipt restore "
                                                        + upgrade.operationId(),
                                                restoreFailure);
                                        return;
                                    }
                                    upgradeInFlight.remove(upgrade.towerId());
                                    player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
                                }));
                        return;
                    }
                    upgradeInFlight.remove(upgrade.towerId());
                    player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
                }));
    }

    private void reconcilePreparedUpgradeReceipts(Player player) {
        UUID playerId = player.getUniqueId();
        databaseExecutor.submit(() -> {
            List<TowerUpgrade> upgrades = repository.loadPreparedTowerUpgrades().stream()
                    .filter(upgrade -> upgrade.actorId().equals(playerId))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            for (TowerUpgrade terminal : repository.loadTerminalTowerUpgradeReceipts(playerId)) {
                if (upgrades.stream().noneMatch(existing ->
                        existing.operationId().equals(terminal.operationId()))) {
                    upgrades.add(terminal);
                }
            }
            return List.copyOf(upgrades);
        })
                .whenComplete((upgrades, failure) -> runOnMainThread(() -> {
                    if (failure != null || upgrades == null || !player.isOnline()) {
                        return;
                    }
                    for (TowerUpgrade upgrade : upgrades) {
                        reconcilePreparedUpgradeReceipt(player, upgrade);
                    }
                }));
    }

    private void reconcilePreparedUpgradeReceipt(Player player, TowerUpgrade upgrade) {
        databaseExecutor.submit(() -> repository.findTowerUpgradeReceipts(
                        upgrade.operationId()))
                .whenComplete((receipts, lookupFailure) -> runOnMainThread(() -> {
                    if (lookupFailure != null || !player.isOnline()) {
                        return;
                    }
                    if (receipts.isEmpty()) {
                        if (upgrade.state() != TowerUpgradeState.ROLLED_BACK) {
                            rollbackUpgrade(player, upgrade, "未完了の旧素材強化を安全に取り消しました。");
                        }
                        return;
                    }
                    if (upgrade.state() == TowerUpgradeState.ROLLED_BACK) {
                        if (receipts.stream().anyMatch(receipt ->
                                receipt.state() == TowerUpgradeReceiptState.RETURN_PENDING)) {
                            completeRolledBackUpgrade(
                                    player,
                                    upgrade,
                                    "切断前に取り消した強化素材を返却しました。");
                        } else if (receipts.stream().anyMatch(receipt ->
                                receipt.state() == TowerUpgradeReceiptState.RESTORED)) {
                            if (hasAnyUpgradeReceiptItems(player, upgrade.operationId())) {
                                stripUpgradeReceiptItemsInPlace(player, upgrade.operationId());
                                player.updateInventory();
                                player.saveData();
                            }
                            upgradeInFlight.remove(upgrade.towerId());
                        }
                        return;
                    }
                    boolean secured = hasUpgradeReceiptItems(player, upgrade);
                    boolean allSecured = receipts.stream().allMatch(
                            receipt -> receipt.state() == TowerUpgradeReceiptState.SECURED);
                    if (upgrade.state() == TowerUpgradeState.APPLIED) {
                        boolean clearPending = receipts.stream().anyMatch(receipt ->
                                receipt.state() == TowerUpgradeReceiptState.CLEAR_PENDING);
                        boolean allCleared = receipts.stream().allMatch(receipt ->
                                receipt.state() == TowerUpgradeReceiptState.CLEARED);
                        if (allCleared) {
                            if (hasAnyUpgradeReceiptItems(player, upgrade.operationId())) {
                                saveAfterUpgradeReceiptRemoval(player, upgrade.operationId());
                            }
                            upgradeInFlight.remove(upgrade.towerId());
                        } else if (clearPending) {
                            clearTowerUpgradeReceiptsAfterSave(player, upgrade.operationId());
                        } else if (secured) {
                            databaseExecutor.submit(() -> repository.markTowerUpgradeReceiptsClearPending(
                                            upgrade.operationId(), Instant.now()))
                                    .whenComplete((ignored, pendingFailure) -> runOnMainThread(() -> {
                                        if (pendingFailure != null || !player.isOnline()) {
                                            return;
                                        }
                                        clearTowerUpgradeReceiptsAfterSave(
                                                player, upgrade.operationId());
                                    }));
                        }
                        return;
                    }
                    if (!secured) {
                        rollbackLegacyUpgrade(
                                player,
                                upgrade,
                                "旧素材強化receiptの現物が不足していたため取り消しました。");
                        return;
                    }
                    if (!allSecured) {
                        databaseExecutor.submit(() -> repository.secureTowerUpgradeReceipts(
                                        upgrade.operationId(), Instant.now()))
                                .whenComplete((ignored, secureFailure) -> runOnMainThread(() -> {
                                    if (secureFailure != null) {
                                        rollbackLegacyUpgrade(
                                                player, upgrade, rootMessage(secureFailure));
                                    } else {
                                        applyLegacyUpgrade(player, upgrade);
                                    }
                                }));
                    } else {
                        applyLegacyUpgrade(player, upgrade);
                    }
                }));
    }

    private void rollbackUpgrade(
            Player player,
            TowerUpgrade upgrade,
            String message) {
        databaseExecutor.submit(() -> repository.rollbackTowerUpgrade(
                        upgrade.operationId(), Instant.now()))
                .whenComplete((result, failure) -> runOnMainThread(() -> {
                    upgradeInFlight.remove(upgrade.towerId());
                    if (failure != null) {
                        plugin.getLogger().log(
                                java.util.logging.Level.SEVERE,
                                "Could not roll back tower upgrade " + upgrade.operationId(),
                                failure);
                        player.sendMessage(Component.text(
                                "タワー強化の復旧を保留しています。管理者へ連絡してください。",
                                NamedTextColor.RED));
                        return;
                    }
                    player.sendMessage(Component.text(message, NamedTextColor.RED));
                }));
    }

    private void finishUpgrade(Player player, UUID towerId, String message) {
        upgradeInFlight.remove(towerId);
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private void beginRemoval(Player player, UUID towerId) {
        if (sessions.hasActiveSession()) {
            player.sendMessage(Component.text(
                    "防衛戦開始後はタワーを回収・移設できません。", NamedTextColor.RED));
            return;
        }
        if (priorityInFlight.contains(towerId)) {
            player.sendMessage(Component.text(
                    "対象優先の保存中はタワーを回収できません。", NamedTextColor.YELLOW));
            return;
        }
        if (!removalInFlight.add(towerId)) {
            player.sendMessage(Component.text("タワー回収を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        TowerRecord tower = towers.find(towerId).orElse(null);
        if (tower == null || !currentTowerEntityMatches(tower)) {
            removalInFlight.remove(towerId);
            player.sendMessage(Component.text(
                    "タワー本体を確認できないため回収を中止しました。", NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        TowerRemoval request = TowerRemoval.prepared(
                UUID.randomUUID(), tower, player.getUniqueId(), Instant.now());
        player.sendMessage(Component.text(
                "タワー回収を準備しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> repository.prepareTowerRemoval(request))
                .whenComplete((prepared, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        finishRemoval(player, towerId,
                                "タワーを回収できません: " + rootMessage(failure));
                        return;
                    }
                    if (prepared.state() != TowerRemovalState.PREPARED
                            || sessions.hasActiveSession()
                            || !currentTowerEntityMatches(tower)
                            || !player.isOnline()) {
                        rollbackRemoval(
                                player,
                                prepared,
                                "回収前に対象または防衛フェーズが変わったため取り消しました。");
                        return;
                    }
                    removeMatchingItems(towerId);
                    if (!giveOrDrop(player, itemTagger.create(
                            tower.id(),
                            tower.type(),
                            tower.individualLevel(),
                            tower.targetPriority()))) {
                        rollbackRemoval(
                                player,
                                prepared,
                                "回収アイテムを返却できなかったため取り消しました。");
                        return;
                    }
                    pendingRemovalTowerIds.add(towerId);
                    databaseExecutor.submit(() -> repository.applyTowerRemoval(
                                    prepared.operationId(), Instant.now()))
                            .whenComplete((applied, applyFailure) -> runOnMainThread(() -> {
                                if (applyFailure != null) {
                                    rollbackRemoval(
                                            player,
                                            prepared,
                                            "回収を永続化できなかったため取り消しました: "
                                                    + rootMessage(applyFailure));
                                    return;
                                }
                                finishAppliedRemoval(applied);
                                player.sendMessage(Component.text(
                                        "タワーを回収しました。アイテムを別の場所へ設置できます。",
                                        NamedTextColor.GREEN));
                            }));
                }));
    }

    private void rollbackRemoval(
            Player player,
            TowerRemoval removal,
            String message) {
        databaseExecutor.submit(() -> repository.rollbackTowerRemoval(
                        removal.operationId(), Instant.now()))
                .whenComplete((result, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        plugin.getLogger().log(
                                java.util.logging.Level.SEVERE,
                                "Could not roll back tower removal " + removal.operationId(),
                                failure);
                        player.sendMessage(Component.text(
                                "タワー回収の復旧を保留しています。管理者へ連絡してください。",
                                NamedTextColor.RED));
                        return;
                    }
                    if (result.isEmpty()) {
                        plugin.getLogger().severe(
                                "Tower removal operation disappeared: " + removal.operationId());
                        player.sendMessage(Component.text(
                                "タワー回収の復旧状態を確認できません。管理者へ連絡してください。",
                                NamedTextColor.RED));
                        return;
                    }
                    TowerRemoval outcome = result.orElseThrow();
                    if (outcome.state() == TowerRemovalState.APPLIED) {
                        finishAppliedRemoval(outcome);
                        player.sendMessage(Component.text(
                                "タワー回収は完了しています。アイテムを保持してください。",
                                NamedTextColor.GREEN));
                        return;
                    }
                    removeMatchingItems(removal.towerId());
                    pendingRemovalTowerIds.remove(removal.towerId());
                    removalInFlight.remove(removal.towerId());
                    player.sendMessage(Component.text(message, NamedTextColor.RED));
                }));
    }

    private void finishAppliedRemoval(TowerRemoval removal) {
        removePhysicalEntity(removal.entityId(), removal.towerId());
        towers.unregister(removal.towerId());
        appliedTowerIds.remove(removal.towerId());
        attackSchedules.remove(removal.towerId());
        pendingRemovalTowerIds.remove(removal.towerId());
        removalInFlight.remove(removal.towerId());
    }

    private void finishRemoval(Player player, UUID towerId, String message) {
        removalInFlight.remove(towerId);
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private boolean currentTowerEntityMatches(TowerRecord tower) {
        Entity entity = Bukkit.getEntity(tower.entityId());
        return entity != null && entityTagger.read(entity).map(identity ->
                identity.towerId().equals(tower.id())
                        && identity.teamId().equals(tower.teamId())
                        && identity.type() == tower.type()
                        && identity.individualLevel() == tower.individualLevel()).orElse(false);
    }

    private void beginPlacement(
            Player player,
            Block clicked,
            Block target,
            TowerItemIdentity identity) {
        UUID actorId = player.getUniqueId();
        if (!placementInFlight.add(actorId)) {
            player.sendMessage(Component.text("タワー設置を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        if (!isValidTarget(player, clicked, target, null)
                || !itemTagger.hasTowerId(player.getInventory().getItemInMainHand(), identity.towerId())) {
            placementInFlight.remove(actorId);
            return;
        }
        player.sendMessage(Component.text("タワー設置位置を検証しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> preparePlan(actorId, identity, target))
                .whenComplete((placement, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        placementInFlight.remove(actorId);
                        player.sendMessage(Component.text(
                                "タワーを設置できません: " + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    TowerPlacement prepared = placement;
                    if (!sessions.mayPlaceTower(prepared.teamId())
                            || !isValidTarget(player, clicked, target, prepared.teamId())
                            || !itemTagger.hasTowerId(
                                    player.getInventory().getItemInMainHand(), identity.towerId())) {
                        rollbackPrepared(player, prepared, null, "設置前に対象または防衛フェーズが変わりました。");
                        return;
                    }
                    applyPhysicalEntity(player, target, prepared, identity.towerId());
                }));
    }

    private TowerPlacement preparePlan(
            UUID actorId,
            TowerItemIdentity identity,
            Block target) {
        var team = defenseRepository.findTeamByMember(actorId).orElseThrow(
                () -> new PersistenceConflictException("タワー設置にはチーム参加が必要です"));
        if (!team.members().contains(actorId)) {
            throw new PersistenceConflictException("このチームのメンバーではありません");
        }
        TowerPlacement placement = TowerPlacement.prepared(
                UUID.randomUUID(),
                identity.towerId(),
                actorId,
                team.id(),
                target.getWorld().getUID(),
                target.getX(),
                target.getY(),
                target.getZ(),
                identity.type(),
                identity.individualLevel(),
                identity.targetPriority(),
                Instant.now());
        return repository.prepareTowerPlacement(placement, settings.towers());
    }

    private void applyPhysicalEntity(
            Player player,
            Block target,
            TowerPlacement placement,
            UUID towerId) {
        ArmorStand stand;
        try {
            stand = target.getWorld().spawn(
                    target.getLocation().add(0.5d, 0.0d, 0.5d),
                    ArmorStand.class,
                    org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,
                    entity -> configureTower(entity, placement));
            entityTagger.tag(stand, new TowerEntityIdentity(
                    placement.towerId(), placement.teamId(), placement.type(),
                    placement.individualLevel()));
        } catch (RuntimeException failure) {
            rollbackPrepared(player, placement, null, "タワー本体を生成できなかったため設置を取り消しました。");
            return;
        }
        databaseExecutor.submit(() -> repository.applyTowerPlacement(
                        placement.operationId(), stand.getUniqueId(), settings.towers(), Instant.now()))
                .whenComplete((tower, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        stand.remove();
                        rollbackPrepared(player, placement, null,
                                "永続化に失敗したため設置を取り消しました。");
                        return;
                    }
                    finishPlacement(player, tower, towerId);
                }));
    }

    private void configureTower(ArmorStand stand, TowerPlacement placement) {
        stand.setPersistent(true);
        stand.setRemoveWhenFarAway(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setCollidable(false);
        stand.setMarker(false);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.customName(Component.text(
                placement.type().displayName() + "タワー", NamedTextColor.GREEN));
        stand.setCustomNameVisible(true);
        ItemStack display = new ItemStack(TowerItemTagger.materialFor(placement.type()));
        Objects.requireNonNull(stand.getEquipment(), "tower equipment").setHelmet(display);
    }

    private void finishPlacement(Player player, TowerRecord tower, UUID towerId) {
        placementInFlight.remove(player.getUniqueId());
        towers.register(tower);
        appliedTowerIds.add(towerId);
        attackSchedules.remove(tower.id());
        removeMatchingItems(towerId);
        player.sendMessage(Component.text("タワーを設置しました。", NamedTextColor.GREEN));
    }

    private void rollbackPrepared(
            Player player,
            TowerPlacement placement,
            Entity entity,
            String message) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        placementInFlight.remove(player.getUniqueId());
        player.sendMessage(Component.text(message, NamedTextColor.RED));
        databaseExecutor.execute(() -> repository.rollbackTowerPlacement(
                placement.operationId(), Instant.now()));
    }

    private boolean isValidTarget(
            Player player,
            Block clicked,
            Block target,
            UUID teamId) {
        if (!target.getWorld().equals(player.getWorld())
                || target.getWorld().getEnvironment() != World.Environment.NORMAL
                || !clicked.getType().isSolid()
                || !target.getType().isAir()
                || target.getState() instanceof TileState
                || cores.isCore(clicked)
                || cores.isCore(target)
                || !target.getWorld().getWorldBorder().isInside(target.getLocation())
                || towers.at(target).isPresent()) {
            player.sendMessage(Component.text(
                    "通常の固体ブロックの上にタワーを設置してください。", NamedTextColor.RED));
            return false;
        }
        if (teamId == null) {
            return true;
        }
        Optional<io.github.takenoha.towerdefense.persistence.TeamRecord> team =
                defenseRepository.findTeam(teamId);
        if (team.isEmpty()) {
            player.sendMessage(Component.text(
                    "タワー設置にはチーム参加が必要です。", NamedTextColor.RED));
            return false;
        }
        if (!team.orElseThrow().members().contains(player.getUniqueId())) {
            player.sendMessage(Component.text(
                    "このチームのメンバーではありません。", NamedTextColor.RED));
            return false;
        }
        Optional<io.github.takenoha.towerdefense.persistence.CoreRecord> core =
                cores.forTeam(team.orElseThrow().id());
        if (core.isEmpty()
                || !core.orElseThrow().worldId().equals(target.getWorld().getUID())
                || !combatArea.contains(
                        core.orElseThrow().blockX() + 0.5d,
                        core.orElseThrow().blockZ() + 0.5d,
                        target.getX() + 0.5d,
                        target.getZ() + 0.5d)) {
            player.sendMessage(Component.text(
                    "タワーはチームのコア周辺に設置してください。", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private void tick() {
        currentTick++;
        if (!sessions.hasActiveSession() && !battleBoosts.isEmpty()) {
            battleBoosts.clear();
        }
        Optional<DefenseRuntimeStatus> runtimeStatus = sessions.status();
        TacticalEffectSnapshot tacticalSnapshot = tacticalEffectsForStatus(runtimeStatus);
        processEnemyTowerAttacks();
        for (TowerRecord tower : towers.all()) {
            Entity entity = Bukkit.getEntity(tower.entityId());
            if (!(entity instanceof ArmorStand stand)
                    || !stand.isValid()
                    || stand.isDead()
                    || entityTagger.read(stand).map(identity ->
                            !identity.towerId().equals(tower.id())
                                    || !identity.teamId().equals(tower.teamId())
                                    || identity.type() != tower.type()
                            || identity.individualLevel() != tower.individualLevel())
                            .orElse(true)) {
                continue;
            }
            if (tower.currentHitPoints() <= 0L) {
                continue;
            }
            if (tower.type() == TowerType.SUPPORT) {
                continue;
            }
            Optional<LivingEntity> target = findTarget(tower, stand, tacticalSnapshot);
            if (target.isEmpty()) {
                continue;
            }
            int supportStacks = supportStacksFor(tower, stand, tacticalSnapshot);
            TacticalTargetContext targetContext = tacticalTargetContext(
                    target.orElseThrow(),
                    runtimeStatus.orElse(null));
            CoreAttackSchedule schedule = attackSchedules.computeIfAbsent(
                    tower.id(), ignored -> new CoreAttackSchedule(
                            effectiveAttackInterval(
                                    tower,
                                    supportStacks,
                                    tacticalSnapshot,
                                    targetContext)));
            int attackInterval = effectiveAttackInterval(
                    tower,
                    supportStacks,
                    tacticalSnapshot,
                    targetContext);
            schedule.updateInterval(attackInterval, currentTick);
            if (schedule.tryClaim(currentTick)) {
                LivingEntity center = target.orElseThrow();
                TowerAttackEffects.Budget effectBudget = TowerAttackEffects.newBudget();
                boolean attackSucceeded = switch (tower.type()) {
                    case ARROW, SNIPER -> damageTarget(
                            tower,
                            stand,
                            stand.getLocation().clone().add(0.0d, 1.0d, 0.0d),
                            center,
                            supportStacks,
                            tacticalSnapshot,
                            runtimeStatus.orElse(null),
                            effectBudget);
                    case CANNON -> damageCannonTargets(
                            tower,
                            stand,
                            center,
                            supportStacks,
                            tacticalSnapshot,
                            runtimeStatus.orElse(null),
                            effectBudget);
                    case FROST -> damageFrostTarget(
                            tower,
                            stand,
                            center,
                            supportStacks,
                            tacticalSnapshot,
                            runtimeStatus.orElse(null),
                            effectBudget);
                    case LIGHTNING -> damageLightningTargets(
                            tower,
                            stand,
                            center,
                            supportStacks,
                            tacticalSnapshot,
                            runtimeStatus.orElse(null),
                            effectBudget);
                    case FLAME -> damageFlameTargets(
                            tower,
                            stand,
                            center,
                            supportStacks,
                            tacticalSnapshot,
                            runtimeStatus.orElse(null),
                            effectBudget);
                    case SUPPORT -> throw new IllegalStateException("support tower reached attack path");
                    };
                if (supportStacks > 0 && attackSucceeded) {
                    renderSupportPulses(tower, stand, tacticalSnapshot, effectBudget);
                }
            }
        }
    }

    /** Processes destroyer proximity attacks through the serialized persistence boundary. */
    private void processEnemyTowerAttacks() {
        Optional<DefenseRuntimeStatus> status = sessions.status();
        if (status.isEmpty() || status.orElseThrow().phase() != DefensePhase.WAVE_ACTIVE) {
            enemyTowerAttackSchedules.clear();
            towerDamageInFlight.clear();
            return;
        }
        DefenseRuntimeStatus active = status.orElseThrow();
        TacticalEffectSnapshot tacticalSnapshot = tacticalEffectsForStatus(status);
        Optional<io.github.takenoha.towerdefense.persistence.CoreRecord> core =
                cores.forTeam(active.teamId());
        if (core.isEmpty()) {
            return;
        }
        World world = Bukkit.getWorld(core.orElseThrow().worldId());
        if (world == null) {
            return;
        }
        List<TowerRecord> candidates = towers.all().stream()
                .filter(tower -> tower.teamId().equals(active.teamId()))
                .filter(tower -> tower.worldId().equals(world.getUID()))
                .filter(tower -> tower.currentHitPoints() > 0L)
                .filter(this::hasLiveTowerEntity)
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        long damage = effectiveEnemyTowerDamage(active.stageLevel(), tacticalSnapshot);
        int interval = settings.enemies().towerAttackIntervalTicks();
        double range = settings.enemies().towerAttackRange();
        double rangeSquared = range * range;
        for (Monster monster : world.getEntitiesByClass(Monster.class)) {
            Optional<TaggedEnemy> tagged = eventEnemyTagger.read(monster);
            if (tagged.isEmpty()
                    || tagged.orElseThrow().role() != EnemyRole.DESTROYER
                    || !sessions.mayAffectFromTower(tagged.orElseThrow(), active.teamId())) {
                continue;
            }
            TaggedEnemy enemy = tagged.orElseThrow();
            PendingTowerDamage pending = towerDamageInFlight.get(enemy.logicalEnemyId());
            if (pending != null) {
                if (!pending.submitted() && currentTick >= pending.retryAtTick()) {
                    submitTowerDamage(pending);
                }
                continue;
            }
            TowerRecord target = nearestTower(monster, candidates, rangeSquared).orElse(null);
            if (target == null) {
                enemyTowerAttackSchedules.remove(enemy.logicalEnemyId());
                continue;
            }
            CoreAttackSchedule schedule = enemyTowerAttackSchedules.computeIfAbsent(
                    enemy.logicalEnemyId(),
                    ignored -> new CoreAttackSchedule(interval));
            schedule.updateInterval(interval, currentTick);
            if (!schedule.tryClaim(currentTick)) {
                continue;
            }
            PendingTowerDamage next = new PendingTowerDamage(
                    UUID.randomUUID(),
                    active.eventId(),
                    active.teamId(),
                    enemy.logicalEnemyId(),
                    target.id(),
                    damage,
                    currentTick,
                    false);
            towerDamageInFlight.put(enemy.logicalEnemyId(), next);
            submitTowerDamage(next);
        }
    }

    private Optional<TowerRecord> nearestTower(
            Monster attacker,
            List<TowerRecord> candidates,
            double rangeSquared) {
        return candidates.stream()
                .map(tower -> new TowerDistance(
                        tower,
                        Bukkit.getEntity(tower.entityId())))
                .filter(value -> value.entity() instanceof ArmorStand stand
                        && stand.isValid()
                        && !stand.isDead())
                .map(value -> new TowerDistance(
                        value.tower(),
                        value.entity(),
                        value.entity().getLocation().distanceSquared(attacker.getLocation())))
                .filter(value -> value.distanceSquared() <= rangeSquared)
                .min(Comparator.comparingDouble(TowerDistance::distanceSquared)
                        .thenComparing(value -> value.tower().id().toString()))
                .map(TowerDistance::tower);
    }

    private boolean hasLiveTowerEntity(TowerRecord tower) {
        Entity entity = Bukkit.getEntity(tower.entityId());
        return entity instanceof ArmorStand stand
                && stand.isValid()
                && !stand.isDead()
                && entityTagger.read(stand).map(identity ->
                        identity.towerId().equals(tower.id())
                                && identity.teamId().equals(tower.teamId())
                                && identity.type() == tower.type()
                                && identity.individualLevel() == tower.individualLevel())
                        .orElse(false);
    }

    private long effectiveEnemyTowerDamage(
            long stageLevel,
            TacticalEffectSnapshot tacticalSnapshot) {
        long boundedStage = Math.min(stageLevel, 11L);
        double multiplier = 1.0d + (boundedStage - 1.0d) / 10.0d;
        double scaled = settings.enemies().towerAttackDamage()
                * multiplier
                * positiveMultiplier(tacticalSnapshot.towerDamageTakenMultiplier());
        if (!Double.isFinite(scaled) || scaled >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) Math.ceil(scaled));
    }

    private void submitTowerDamage(PendingTowerDamage pending) {
        PendingTowerDamage submitted = pending.withSubmitted(true);
        towerDamageInFlight.put(pending.logicalEnemyId(), submitted);
        databaseExecutor.submit(() -> defenseRepository.damageTowerByEnemy(
                        submitted.eventId(),
                        submitted.teamId(),
                        submitted.logicalEnemyId(),
                        submitted.towerId(),
                        submitted.damage(),
                        submitted.operationId(),
                        Instant.now()))
                .whenComplete((result, failure) -> runOnMainThread(() -> {
                    PendingTowerDamage current = towerDamageInFlight.get(
                            submitted.logicalEnemyId());
                    if (current == null
                            || !current.operationId().equals(submitted.operationId())) {
                        return;
                    }
                    if (failure != null) {
                        towerDamageInFlight.put(
                                submitted.logicalEnemyId(),
                                submitted.retryAfter(currentTick));
                        plugin.getLogger().warning(
                                "Could not persist enemy tower damage "
                                        + submitted.operationId() + ": " + rootMessage(failure));
                        return;
                    }
                    towerDamageInFlight.remove(submitted.logicalEnemyId());
                    applyTowerDamage(result);
                }));
    }

    private void applyTowerDamage(TowerDamageMutationResult result) {
        TowerRecord current = towers.find(result.towerId()).orElse(null);
        if (result.destroyed()) {
            if (current != null) {
                towers.unregister(current.id());
                attackSchedules.remove(current.id());
                removePhysicalEntity(current.entityId(), current.id());
            }
            return;
        }
        if (current != null
                && result.remainingHitPoints() < current.currentHitPoints()) {
            towers.replace(current.withCurrentHitPoints(
                    result.remainingHitPoints(), Instant.now()));
        }
    }

    private Optional<LivingEntity> findTarget(
            TowerRecord tower,
            ArmorStand stand,
            TacticalEffectSnapshot tacticalSnapshot) {
        double range = effectiveRange(tower, stand, tacticalSnapshot);
        List<LivingEntity> candidates = new ArrayList<>();
        EventEnemyTagger eventTagger = new EventEnemyTagger(plugin);
        Map<UUID, TaggedEnemy> eventTags = new HashMap<>();
        for (Entity entity : stand.getWorld().getNearbyEntities(
                stand.getLocation(), range, range, range)) {
            if (!(entity instanceof Monster monster)
                    || !monster.isValid()
                    || monster.isDead()
                    || entityTagger.read(entity).isPresent()
                    || entity.getLocation().distanceSquared(stand.getLocation()) > range * range
                    || !stand.hasLineOfSight(entity)) {
                continue;
            }
            Optional<TaggedEnemy> tagged = eventTagger.read(entity);
            if (tagged.isPresent() && !sessions.mayAffectFromTower(
                    tagged.orElseThrow(), tower.teamId())) {
                continue;
            }
            tagged.ifPresent(value -> eventTags.put(entity.getUniqueId(), value));
            candidates.add(monster);
        }
        Optional<io.github.takenoha.towerdefense.persistence.CoreRecord> core =
                cores.forTeam(tower.teamId());
        if (core.isEmpty()) {
            return Optional.empty();
        }
        double coreX = core.orElseThrow().blockX() + 0.5d;
        double coreZ = core.orElseThrow().blockZ() + 0.5d;
        Comparator<LivingEntity> coreDistance = Comparator.comparingDouble(candidate ->
                Math.pow(candidate.getX() - coreX, 2.0d)
                        + Math.pow(candidate.getZ() - coreZ, 2.0d));
        Comparator<LivingEntity> towerDistance = Comparator.comparingDouble(candidate ->
                candidate.getLocation().distanceSquared(stand.getLocation()));
        Comparator<LivingEntity> stableId = Comparator.comparing(
                candidate -> candidate.getUniqueId().toString());
        Comparator<LivingEntity> fallback = coreDistance.thenComparing(towerDistance)
                .thenComparing(stableId);
        Comparator<LivingEntity> priorityComparator = switch (tower.targetPriority()) {
            case CORE_NEAREST -> fallback;
            case NEAREST -> towerDistance.thenComparing(coreDistance).thenComparing(stableId);
            case HEALTH_HIGH -> Comparator.comparingDouble(LivingEntity::getHealth)
                    .reversed()
                    .thenComparing(fallback);
            case HEALTH_LOW -> Comparator.comparingDouble(LivingEntity::getHealth)
                    .thenComparing(fallback);
            case BOSS -> Comparator.comparing(
                            (LivingEntity candidate) -> eventTags.containsKey(candidate.getUniqueId())
                                    && eventTags.get(candidate.getUniqueId()).role() == EnemyRole.BOSS)
                    .reversed()
                    .thenComparing(fallback);
        };
        candidates.sort(priorityComparator);
        return candidates.stream().findFirst();
    }

    private boolean damageCannonTargets(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity center,
            int supportStacks,
            TacticalEffectSnapshot tacticalSnapshot,
            DefenseRuntimeStatus runtimeStatus,
            TowerAttackEffects.Budget effectBudget) {
        double radius = effectiveAreaRadius(tower, tacticalSnapshot);
        EventEnemyTagger eventTagger = new EventEnemyTagger(plugin);
        boolean damagedAny = false;
        for (Entity entity : center.getWorld().getNearbyEntities(
                center.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof Monster monster)
                    || !monster.isValid()
                    || monster.isDead()
                    || entityTagger.read(entity).isPresent()
                    || entity.getLocation().distanceSquared(center.getLocation()) > radius * radius
                    || !stand.hasLineOfSight(entity)) {
                continue;
            }
            Optional<TaggedEnemy> tagged = eventTagger.read(entity);
            if (tagged.isPresent() && !sessions.mayAffectFromTower(
                    tagged.orElseThrow(), tower.teamId())) {
                continue;
            }
            damagedAny |= damageTarget(
                    tower,
                    stand,
                    stand.getLocation().clone().add(0.0d, 1.0d, 0.0d),
                    monster,
                    supportStacks,
                    tacticalSnapshot,
                    runtimeStatus,
                    effectBudget);
        }
        return damagedAny;
    }

    private boolean damageFrostTarget(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity target,
            int supportStacks,
            TacticalEffectSnapshot tacticalSnapshot,
            DefenseRuntimeStatus runtimeStatus,
            TowerAttackEffects.Budget effectBudget) {
        TowerSettings towerSettings = settings.towers();
        double beforeHealth = target.getHealth();
        target.damage(
                effectiveDamage(
                        tower,
                        supportStacks,
                        tacticalSnapshot,
                        tacticalTargetContext(target, runtimeStatus)),
                stand);
        boolean damaged = target.isDead() || target.getHealth() < beforeHealth;
        int duration = towerSettings.slowDurationTicksFor(tower.type());
        boolean slowed = false;
        if (duration > 0) {
            double slowPercent = towerSettings.slowPercentFor(tower.type())
                    * positiveMultiplier(tacticalSnapshot.slowStrengthMultiplier(tower.type()));
            int amplifier = Math.max(
                    0,
                    (int) Math.ceil(slowPercent * 4.0d) - 1);
            slowed = target.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    duration,
                    amplifier,
                    true,
                    true,
                    true));
        }
        if (damaged || slowed) {
            renderSuccessfulAttack(
                    tower.type(),
                    stand.getLocation().clone().add(0.0d, 1.0d, 0.0d),
                    target,
                    effectBudget);
        }
        return damaged || slowed;
    }

    private boolean damageLightningTargets(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity center,
            int supportStacks,
            TacticalEffectSnapshot tacticalSnapshot,
            DefenseRuntimeStatus runtimeStatus,
            TowerAttackEffects.Budget effectBudget) {
        TowerSettings towerSettings = settings.towers();
        double radius = towerSettings.chainRadiusFor(tower.type());
        EventEnemyTagger eventTagger = new EventEnemyTagger(plugin);
        List<LivingEntity> candidates = new ArrayList<>();
        candidates.add(center);
        for (Entity entity : center.getWorld().getNearbyEntities(
                center.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof Monster monster)
                    || monster.equals(center)
                    || !monster.isValid()
                    || monster.isDead()
                    || entityTagger.read(entity).isPresent()
                    || entity.getLocation().distanceSquared(center.getLocation()) > radius * radius
                    || !stand.hasLineOfSight(entity)) {
                continue;
            }
            Optional<TaggedEnemy> tagged = eventTagger.read(entity);
            if (tagged.isPresent() && !sessions.mayAffectFromTower(
                    tagged.orElseThrow(), tower.teamId())) {
                continue;
            }
            candidates.add(monster);
        }
        List<LivingEntity> chain = candidates.stream()
                .skip(1)
                .sorted(Comparator.comparingDouble(
                        candidate -> candidate.getLocation().distanceSquared(center.getLocation())))
                .limit(Math.max(
                        0,
                        effectiveChainCount(tower, tacticalSnapshot) - 1L))
                .toList();
        org.bukkit.Location source = stand.getLocation().clone().add(0.0d, 1.0d, 0.0d);
        boolean damagedAny = false;
        for (LivingEntity candidate : chain) {
            if (damageTarget(
                    tower,
                    stand,
                    source,
                    candidate,
                    supportStacks,
                    tacticalSnapshot,
                    runtimeStatus,
                    effectBudget)) {
                damagedAny = true;
                source = candidate.getLocation().clone().add(0.0d, 0.8d, 0.0d);
            }
        }
        damagedAny |= damageTarget(
                tower,
                stand,
                source,
                center,
                supportStacks,
                tacticalSnapshot,
                runtimeStatus,
                effectBudget);
        return damagedAny;
    }

    private boolean damageFlameTargets(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity center,
            int supportStacks,
            TacticalEffectSnapshot tacticalSnapshot,
            DefenseRuntimeStatus runtimeStatus,
            TowerAttackEffects.Budget effectBudget) {
        TowerSettings towerSettings = settings.towers();
        double radius = effectiveAreaRadius(tower, tacticalSnapshot);
        EventEnemyTagger eventTagger = new EventEnemyTagger(plugin);
        boolean damagedAny = false;
        for (Entity entity : center.getWorld().getNearbyEntities(
                center.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof Monster monster)
                    || !monster.isValid()
                    || monster.isDead()
                    || entityTagger.read(entity).isPresent()
                    || entity.getLocation().distanceSquared(center.getLocation()) > radius * radius
                    || !stand.hasLineOfSight(entity)) {
                continue;
            }
            Optional<TaggedEnemy> tagged = eventTagger.read(entity);
            if (tagged.isPresent() && !sessions.mayAffectFromTower(
                    tagged.orElseThrow(), tower.teamId())) {
                continue;
            }
            double beforeHealth = monster.getHealth();
            monster.damage(
                    effectiveDamage(
                            tower,
                            supportStacks,
                            tacticalSnapshot,
                            tacticalTargetContext(monster, runtimeStatus)),
                    stand);
            boolean damaged = monster.isDead() || monster.getHealth() < beforeHealth;
            int previousFireTicks = monster.getFireTicks();
            int burnDuration = effectiveBurnDuration(tower, tacticalSnapshot);
            monster.setFireTicks(Math.max(
                    monster.getFireTicks(), burnDuration));
            if (damaged || monster.getFireTicks() > previousFireTicks) {
                damagedAny = true;
                renderSuccessfulAttack(
                        tower.type(),
                        stand.getLocation().clone().add(0.0d, 1.0d, 0.0d),
                        monster,
                        effectBudget);
            }
        }
        return damagedAny;
    }

    private boolean damageTarget(
            TowerRecord tower,
            ArmorStand stand,
            org.bukkit.Location source,
            LivingEntity target,
            int supportStacks,
            TacticalEffectSnapshot tacticalSnapshot,
            DefenseRuntimeStatus runtimeStatus,
            TowerAttackEffects.Budget effectBudget) {
        double beforeHealth = target.getHealth();
        target.damage(
                effectiveDamage(
                        tower,
                        supportStacks,
                        tacticalSnapshot,
                        tacticalTargetContext(target, runtimeStatus)),
                stand);
        boolean damaged = target.isDead() || target.getHealth() < beforeHealth;
        if (damaged) {
            renderSuccessfulAttack(tower.type(), source, target, effectBudget);
        }
        return damaged;
    }

    private void renderSuccessfulAttack(
            TowerType type,
            org.bukkit.Location source,
            LivingEntity target,
            TowerAttackEffects.Budget effectBudget) {
        org.bukkit.Location targetLocation = target.getLocation().clone().add(0.0d, 0.8d, 0.0d);
        TowerAttackEffects.renderAttack(type, source, targetLocation, effectBudget);
        TowerAttackEffects.renderHit(type, targetLocation, effectBudget);
    }

    private void renderSupportPulses(
            TowerRecord tower,
            ArmorStand target,
            TacticalEffectSnapshot tacticalSnapshot,
            TowerAttackEffects.Budget effectBudget) {
        double radius = effectiveSupportRadius(tacticalSnapshot);
        double radiusSquared = radius * radius;
        towers.all().stream()
                .filter(candidate -> candidate.type() == TowerType.SUPPORT)
                .filter(candidate -> candidate.teamId().equals(tower.teamId()))
                .filter(candidate -> !candidate.id().equals(tower.id()))
                .filter(candidate -> candidate.worldId().equals(tower.worldId()))
                .filter(candidate -> {
                    Entity entity = Bukkit.getEntity(candidate.entityId());
                    return entity instanceof ArmorStand support
                            && support.isValid()
                            && !support.isDead()
                            && support.getLocation().distanceSquared(target.getLocation())
                                    <= radiusSquared;
                })
                .sorted(Comparator.comparing(candidate -> candidate.id().toString()))
                .limit(settings.towers().supportStackLimit())
                .map(candidate -> Bukkit.getEntity(candidate.entityId()))
                .filter(entity -> entity instanceof ArmorStand)
                .forEach(entity -> TowerAttackEffects.renderBuff(
                        TowerType.SUPPORT,
                        ((ArmorStand) entity).getLocation().clone().add(0.0d, 1.0d, 0.0d),
                        target.getLocation().clone().add(0.0d, 1.0d, 0.0d),
                        effectBudget));
    }

    private int supportStacksFor(
            TowerRecord tower,
            ArmorStand stand,
            TacticalEffectSnapshot tacticalSnapshot) {
        TowerSettings towerSettings = settings.towers();
        if (towerSettings.supportStackLimit() <= 0) {
            return 0;
        }
        double radius = effectiveSupportRadius(tacticalSnapshot);
        double radiusSquared = radius * radius;
        return (int) towers.all().stream()
                .filter(candidate -> candidate.type() == TowerType.SUPPORT)
                .filter(candidate -> candidate.teamId().equals(tower.teamId()))
                .filter(candidate -> !candidate.id().equals(tower.id()))
                .filter(candidate -> candidate.worldId().equals(tower.worldId()))
                .filter(candidate -> {
                    Entity entity = Bukkit.getEntity(candidate.entityId());
                    return entity instanceof ArmorStand support
                            && support.isValid()
                            && !support.isDead()
                            && support.getLocation().distanceSquared(stand.getLocation())
                                    <= radiusSquared;
                })
                .sorted(Comparator.comparing(candidate -> candidate.id().toString()))
                .limit(towerSettings.supportStackLimit())
                .count();
    }

    private double effectiveRange(
            TowerRecord tower,
            ArmorStand stand,
            TacticalEffectSnapshot tacticalSnapshot) {
        int supportStacks = supportStacksFor(tower, stand, tacticalSnapshot);
        double supportMultiplier = positiveMultiplier(tacticalSnapshot.supportBuffMultiplier());
        double range = settings.towers().rangeFor(tower.type())
                * Math.pow(
                        settings.towers().supportRangeMultiplier() * supportMultiplier,
                        supportStacks)
                * battleBoosts.multiplier(tower.id(), BattleBoostKind.RANGE);
        return positiveFiniteRange(range + tacticalSnapshot.rangeAdd(tower.type()));
    }

    private int effectiveAttackInterval(
            TowerRecord tower,
            int supportStacks,
            TacticalEffectSnapshot tacticalSnapshot,
            TacticalTargetContext targetContext) {
        double supportMultiplier = positiveMultiplier(tacticalSnapshot.supportBuffMultiplier());
        double intervalValue = settings.towers().attackIntervalTicksFor(tower.type())
                * Math.pow(
                        settings.towers().supportSpeedMultiplier() * supportMultiplier,
                        supportStacks)
                * battleBoosts.multiplier(tower.id(), BattleBoostKind.SPEED)
                * positiveMultiplier(tacticalSnapshot.attackIntervalMultiplier(
                        tower.type(),
                        targetContext));
        long interval = roundedPositiveLong(intervalValue);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, interval));
    }

    private int effectiveDamage(
            TowerRecord tower,
            int supportStacks,
            TacticalEffectSnapshot tacticalSnapshot,
            TacticalTargetContext targetContext) {
        double supportMultiplier = positiveMultiplier(tacticalSnapshot.supportBuffMultiplier());
        double damageValue = settings.towers().damageFor(tower.type())
                * Math.pow(
                        settings.towers().supportDamageMultiplier() * supportMultiplier,
                        supportStacks)
                * battleBoosts.multiplier(tower.id(), BattleBoostKind.POWER)
                * positiveMultiplier(tacticalSnapshot.damageMultiplier(
                        tower.type(),
                        targetContext));
        long damage = roundedPositiveLong(damageValue);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, damage));
    }

    private TacticalEffectSnapshot tacticalEffectsForEvent(Optional<UUID> eventId) {
        if (eventId.isEmpty()) {
            return EmptyTacticalEffectSnapshot.INSTANCE;
        }
        TacticalEffectSnapshot snapshot = tacticalEffects.currentForDefense(eventId.orElseThrow());
        return snapshot == null ? EmptyTacticalEffectSnapshot.INSTANCE : snapshot;
    }

    private TacticalEffectSnapshot tacticalEffectsForStatus(
            Optional<DefenseRuntimeStatus> runtimeStatus) {
        return runtimeStatus
                .map(status -> tacticalEffectsForEvent(Optional.of(status.eventId())))
                .orElse(EmptyTacticalEffectSnapshot.INSTANCE);
    }

    private double effectiveAreaRadius(
            TowerRecord tower,
            TacticalEffectSnapshot tacticalSnapshot) {
        double base = tower.type() == TowerType.CANNON
                ? settings.towers().cannonSplashRadius()
                : settings.towers().areaRadiusFor(tower.type());
        return positiveFiniteRange(
                base * positiveMultiplier(tacticalSnapshot.areaRadiusMultiplier(tower.type())));
    }

    private int effectiveChainCount(
            TowerRecord tower,
            TacticalEffectSnapshot tacticalSnapshot) {
        long count = (long) settings.towers().chainCountFor(tower.type())
                + tacticalSnapshot.chainCountAdd(tower.type());
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, count));
    }

    private int effectiveBurnDuration(
            TowerRecord tower,
            TacticalEffectSnapshot tacticalSnapshot) {
        double scaled = settings.towers().burnDurationTicksFor(tower.type())
                * positiveMultiplier(tacticalSnapshot.burnDurationMultiplier(tower.type()));
        if (!Double.isFinite(scaled) || scaled >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0L, Math.round(scaled));
    }

    private double effectiveSupportRadius(TacticalEffectSnapshot tacticalSnapshot) {
        return positiveFiniteRange(
                settings.towers().supportRadius() + tacticalSnapshot.rangeAdd(TowerType.SUPPORT));
    }

    private TacticalTargetContext tacticalTargetContext(
            LivingEntity target,
            DefenseRuntimeStatus runtimeStatus) {
        AttributeInstance maximumHealthAttribute = target.getAttribute(Attribute.MAX_HEALTH);
        double maximumHealth = maximumHealthAttribute == null
                ? 1.0d
                : maximumHealthAttribute.getValue();
        double targetFraction = maximumHealth > 0.0d && Double.isFinite(maximumHealth)
                ? target.getHealth() / maximumHealth
                : 1.0d;
        double coreFraction = runtimeStatus == null || runtimeStatus.coreMaximumHitPoints() <= 0L
                ? 1.0d
                : (double) runtimeStatus.coreHitPoints()
                        / runtimeStatus.coreMaximumHitPoints();
        Optional<TaggedEnemy> taggedEnemy = eventEnemyTagger.read(target);
        boolean boss = taggedEnemy.map(value -> value.role() == EnemyRole.BOSS).orElse(false);
        return new TacticalTargetContext(
                boundedFraction(targetFraction),
                boundedFraction(coreFraction),
                boss,
                target.hasPotionEffect(PotionEffectType.SLOWNESS),
                target.getFireTicks() > 0);
    }

    private static long tacticalRepairCost(
            long baseCost,
            TacticalEffectSnapshot tacticalSnapshot) {
        if (baseCost <= 0L) {
            return 0L;
        }
        double scaled = baseCost
                * positiveMultiplier(tacticalSnapshot.repairCostMultiplier());
        if (!Double.isFinite(scaled) || scaled >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, Math.round(scaled));
    }

    private static long roundedPositiveLong(double value) {
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, Math.round(value));
    }

    private static double positiveMultiplier(double value) {
        return Double.isFinite(value) && value > 0.0d ? value : 1.0d;
    }

    private static double positiveFiniteRange(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return 0.0d;
        }
        return Math.min(value, 1.0e6d);
    }

    private static double boundedFraction(double value) {
        if (!Double.isFinite(value)) {
            return 1.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private boolean touchesTower(Block block) {
        return towers.at(block).isPresent() || towers.at(block.getRelative(BlockFace.UP)).isPresent();
    }

    private boolean recoverPhysicalPlacement(TowerPlacement placement) {
        World world = Bukkit.getWorld(placement.worldId());
        if (world == null) {
            plugin.getLogger().severe(
                    "Cannot recover prepared tower placement " + placement.operationId()
                            + ": world " + placement.worldId() + " is not loaded");
            return false;
        }
        org.bukkit.Location location = new org.bukkit.Location(
                world, placement.blockX() + 0.5d, placement.blockY(), placement.blockZ() + 0.5d);
        for (Entity entity : world.getNearbyEntities(location, 1.0d, 1.0d, 1.0d)) {
            if (entityTagger.read(entity).map(identity ->
                    identity.towerId().equals(placement.towerId())).orElse(false)) {
                entity.remove();
                return true;
            }
        }
        return true;
    }

    private void removePhysicalEntity(UUID entityId, UUID towerId) {
        Entity direct = Bukkit.getEntity(entityId);
        if (direct != null && entityTagger.read(direct).map(identity ->
                identity.towerId().equals(towerId)).orElse(false)) {
            direct.remove();
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entityTagger.read(entity).map(identity ->
                        identity.towerId().equals(towerId)).orElse(false)) {
                    entity.remove();
                }
            }
        }
    }

    private void reconcileAppliedItems(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (itemTagger.read(item).map(identity -> appliedTowerIds.contains(identity.towerId()))
                    .orElse(false)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void removeMatchingItems(UUID towerId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            reconcileAppliedItems(player);
            removeMatchingItemsFromPlayer(player, towerId);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (itemTagger.hasTowerId(item.getItemStack(), towerId)) {
                    item.remove();
                }
            }
        }
    }

    private void removeMatchingItemsFromPlayer(Player player, UUID towerId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (itemTagger.hasTowerId(item, towerId)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private boolean giveOrDrop(Player player, ItemStack item) {
        if (!player.isOnline()) {
            return false;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        return true;
    }

    /** Adds a planned split remainder without ever converting overflow into a ground drop. */
    private boolean giveOrDropWithoutLoss(Player player, ItemStack item) {
        if (!player.isOnline()) {
            return false;
        }
        return player.getInventory().addItem(item).isEmpty();
    }

    private void runOnMainThread(Runnable action) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
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

    private record TowerGuiData(
            TowerRecord tower,
            int researchLevel,
            Optional<UUID> eventId,
            long battleFunds,
            List<BattleBoost> boosts,
            TeamResourceSnapshot resources) {
    }

    private record TowerDistance(
            TowerRecord tower,
            Entity entity,
            double distanceSquared) {
        private TowerDistance(TowerRecord tower, Entity entity) {
            this(tower, entity, Double.NaN);
        }
    }

    private record PendingTowerDamage(
            UUID operationId,
            UUID eventId,
            UUID teamId,
            UUID logicalEnemyId,
            UUID towerId,
            long damage,
            long retryAtTick,
            boolean submitted) {
        private PendingTowerDamage withSubmitted(boolean value) {
            return new PendingTowerDamage(
                    operationId,
                    eventId,
                    teamId,
                    logicalEnemyId,
                    towerId,
                    damage,
                    retryAtTick,
                    value);
        }

        private PendingTowerDamage retryAfter(long currentTick) {
            long retry = currentTick > Long.MAX_VALUE - 20L
                    ? Long.MAX_VALUE
                    : currentTick + 20L;
            return new PendingTowerDamage(
                    operationId,
                    eventId,
                    teamId,
                    logicalEnemyId,
                    towerId,
                    damage,
                    retry,
                    false);
        }
    }

    @Override
    public void close() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        attackSchedules.clear();
        enemyTowerAttackSchedules.clear();
        towerDamageInFlight.clear();
        upgradeInFlight.clear();
    }
}
