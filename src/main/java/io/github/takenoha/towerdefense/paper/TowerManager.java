package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.BattleBoost;
import io.github.takenoha.towerdefense.persistence.BattleBoostKind;
import io.github.takenoha.towerdefense.persistence.PersistenceConflictException;
import io.github.takenoha.towerdefense.persistence.TowerPlacement;
import io.github.takenoha.towerdefense.persistence.TowerRecord;
import io.github.takenoha.towerdefense.persistence.TowerRemoval;
import io.github.takenoha.towerdefense.persistence.TowerRemovalState;
import io.github.takenoha.towerdefense.persistence.TowerRepository;
import io.github.takenoha.towerdefense.persistence.TowerUpgrade;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeResult;
import io.github.takenoha.towerdefense.persistence.TowerUpgradeState;
import io.github.takenoha.towerdefense.runtime.CoreAttackSchedule;
import io.github.takenoha.towerdefense.runtime.BattleBoostRegistry;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import io.github.takenoha.towerdefense.runtime.TaggedEnemy;
import io.github.takenoha.towerdefense.runtime.TowerRegistry;
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
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
    private final DefenseShardTagger shardTagger;
    private final EnhancementCoreTagger enhancementCoreTagger;
    private final CombatArea combatArea;
    private final NamespacedKey towerDamageKey;
    private final Set<UUID> placementInFlight = new HashSet<>();
    private final Set<UUID> removalInFlight = new HashSet<>();
    private final Set<UUID> priorityInFlight = new HashSet<>();
    private final Set<UUID> upgradeInFlight = new HashSet<>();
    private final Set<UUID> pendingRemovalTowerIds = new HashSet<>();
    private final Set<UUID> appliedTowerIds;
    private final Map<UUID, CoreAttackSchedule> attackSchedules = new HashMap<>();
    private final BattleBoostRegistry battleBoosts = new BattleBoostRegistry();
    private final Set<UUID> boostInFlight = new HashSet<>();

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
        shardTagger = new DefenseShardTagger(plugin);
        enhancementCoreTagger = new EnhancementCoreTagger(plugin);
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
        NamespacedKey arrowKey = new NamespacedKey(plugin, "tower_arrow");
        ShapedRecipe arrowRecipe = new ShapedRecipe(
                arrowKey, itemTagger.recipeTemplate(TowerType.ARROW));
        arrowRecipe.shape("IRI", "RDR", "IRI");
        arrowRecipe.setIngredient('I', Material.IRON_INGOT);
        arrowRecipe.setIngredient('R', Material.REDSTONE);
        arrowRecipe.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(arrowRecipe);

        NamespacedKey cannonKey = new NamespacedKey(plugin, "tower_cannon");
        ShapedRecipe cannonRecipe = new ShapedRecipe(
                cannonKey, itemTagger.recipeTemplate(TowerType.CANNON));
        cannonRecipe.shape("CGC", "GIG", "CGC");
        cannonRecipe.setIngredient('C', Material.COBBLESTONE);
        cannonRecipe.setIngredient('G', Material.GUNPOWDER);
        cannonRecipe.setIngredient('I', Material.IRON_INGOT);
        Bukkit.addRecipe(cannonRecipe);

        ShapedRecipe frostRecipe = new ShapedRecipe(
                new NamespacedKey(plugin, "tower_frost"),
                itemTagger.recipeTemplate(TowerType.FROST));
        frostRecipe.shape("PIP", "IDI", "PIP");
        frostRecipe.setIngredient('P', Material.PACKED_ICE);
        frostRecipe.setIngredient('I', Material.IRON_INGOT);
        frostRecipe.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(frostRecipe);

        ShapedRecipe lightningRecipe = new ShapedRecipe(
                new NamespacedKey(plugin, "tower_lightning"),
                itemTagger.recipeTemplate(TowerType.LIGHTNING));
        lightningRecipe.shape("RER", "ECE", "RER");
        lightningRecipe.setIngredient('R', Material.REDSTONE);
        lightningRecipe.setIngredient('E', Material.ENDER_PEARL);
        lightningRecipe.setIngredient('C', Material.COPPER_INGOT);
        Bukkit.addRecipe(lightningRecipe);

        ShapedRecipe supportRecipe = new ShapedRecipe(
                new NamespacedKey(plugin, "tower_support"),
                itemTagger.recipeTemplate(TowerType.SUPPORT));
        supportRecipe.shape("GAG", "AEA", "GAG");
        supportRecipe.setIngredient('G', Material.GOLD_INGOT);
        supportRecipe.setIngredient('A', Material.AMETHYST_SHARD);
        supportRecipe.setIngredient('E', Material.EMERALD);
        Bukkit.addRecipe(supportRecipe);

        ShapedRecipe sniperRecipe = new ShapedRecipe(
                new NamespacedKey(plugin, "tower_sniper"),
                itemTagger.recipeTemplate(TowerType.SNIPER));
        sniperRecipe.shape("FIF", "IEI", "FIF");
        sniperRecipe.setIngredient('F', Material.FEATHER);
        sniperRecipe.setIngredient('I', Material.IRON_INGOT);
        sniperRecipe.setIngredient('E', Material.ENDER_EYE);
        Bukkit.addRecipe(sniperRecipe);

        ShapedRecipe flameRecipe = new ShapedRecipe(
                new NamespacedKey(plugin, "tower_flame"),
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
        reconcileAppliedItems(event.getPlayer());
        for (UUID towerId : pendingRemovalTowerIds) {
            removeMatchingItemsFromPlayer(event.getPlayer(), towerId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
            beginUpgrade(player, holder.towerId());
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
            return new TowerGuiData(tower, research.researchLevel(), eventId, battleFunds, boosts);
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
                    repairCost));
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
        long cost = Math.multiplyExact(repaired, settings.towers().battleRepairFundsPerHealth());
        if (!boostInFlight.add(towerId)) {
            player.sendMessage(Component.text("タワー修理を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("タワー修理を処理しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            UUID eventId = defenseRepository.activeEventId().orElseThrow(
                    () -> new IllegalStateException("防衛戦が見つかりません"));
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

    private void beginUpgrade(Player player, UUID towerId) {
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
        TowerUpgrade request = TowerUpgrade.prepared(
                UUID.randomUUID(),
                tower,
                player.getUniqueId(),
                shardCost,
                coreCost,
                Instant.now());
        player.sendMessage(Component.text("個体Lv強化を準備しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> repository.prepareTowerUpgrade(request))
                .whenComplete((prepared, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        finishUpgrade(player, towerId,
                                "タワーを強化できません: " + rootMessage(failure));
                        return;
                    }
                    if (prepared.state() != TowerUpgradeState.PREPARED
                            || !sessions.mayUpgradeTower(tower.teamId())
                            || !currentTowerEntityMatches(tower)
                            || !player.isOnline()) {
                        rollbackUpgrade(
                                player,
                                prepared,
                                null,
                                "強化前に対象または防衛フェーズが変わったため取り消しました。");
                        return;
                    }
                    RemovedItems removed = removeUpgradeItems(
                            player,
                            prepared.defenseShardCost(),
                            prepared.enhancementCoreCost());
                    if (removed == null) {
                        rollbackUpgrade(
                                player,
                                prepared,
                                null,
                                "強化に必要な素材が不足しています。");
                        return;
                    }
                    databaseExecutor.submit(() -> repository.applyTowerUpgrade(
                                    prepared.operationId(), Instant.now()))
                            .whenComplete((result, applyFailure) -> runOnMainThread(() -> {
                                if (applyFailure != null) {
                                    rollbackUpgrade(
                                            player,
                                            prepared,
                                            removed,
                                            "強化を永続化できなかったため素材を返却します: "
                                                    + rootMessage(applyFailure));
                                    return;
                                }
                                TowerRecord updated = result.tower().orElse(null);
                                if (updated == null) {
                                    finishUpgrade(player, towerId,
                                            "強化結果のタワーを確認できません。管理者へ連絡してください。");
                                    return;
                                }
                                upgradeInFlight.remove(towerId);
                                towers.replace(updated);
                                Entity entity = Bukkit.getEntity(updated.entityId());
                                if (entity != null) {
                                    entityTagger.tag(entity, new TowerEntityIdentity(
                                            updated.id(),
                                            updated.teamId(),
                                            updated.type(),
                                            updated.individualLevel()));
                                }
                                player.sendMessage(Component.text(
                                        "タワーを個体Lv" + updated.individualLevel()
                                                + "へ強化しました。",
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
                }));
    }

    private void rollbackUpgrade(
            Player player,
            TowerUpgrade upgrade,
            RemovedItems removed,
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
                    if (removed != null
                            && result.isPresent()
                            && result.orElseThrow().state() == TowerUpgradeState.ROLLED_BACK) {
                        for (ItemStack item : removed.items()) {
                            if (!giveOrDrop(player, item)) {
                                plugin.getLogger().warning(
                                        "Could not refund a tower upgrade material");
                            }
                        }
                    }
                    player.sendMessage(Component.text(message, NamedTextColor.RED));
                }));
    }

    private void finishUpgrade(Player player, UUID towerId, String message) {
        upgradeInFlight.remove(towerId);
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private RemovedItems removeUpgradeItems(
            Player player,
            int shardCost,
            int enhancementCoreCost) {
        int shardsRemaining = shardCost;
        int coresRemaining = enhancementCoreCost;
        List<ItemStack> removed = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null) {
                continue;
            }
            boolean shard = shardTagger.isShard(item);
            boolean core = enhancementCoreTagger.isEnhancementCore(item);
            int remaining = shard ? shardsRemaining : (core ? coresRemaining : 0);
            if (remaining <= 0) {
                continue;
            }
            int quantity = Math.min(item.getAmount(), remaining);
            ItemStack taken = item.clone();
            taken.setAmount(quantity);
            removed.add(taken);
            int left = item.getAmount() - quantity;
            player.getInventory().setItem(slot, left == 0 ? null : item.clone());
            if (left > 0) {
                player.getInventory().getItem(slot).setAmount(left);
            }
            if (shard) {
                shardsRemaining -= quantity;
            } else {
                coresRemaining -= quantity;
            }
        }
        if (shardsRemaining > 0 || coresRemaining > 0) {
            for (ItemStack item : removed) {
                giveOrDrop(player, item);
            }
            return null;
        }
        return new RemovedItems(List.copyOf(removed));
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
            Optional<LivingEntity> target = findTarget(tower, stand);
            if (target.isEmpty()) {
                continue;
            }
            int supportStacks = supportStacksFor(tower, stand);
            CoreAttackSchedule schedule = attackSchedules.computeIfAbsent(
                    tower.id(), ignored -> new CoreAttackSchedule(
                            effectiveAttackInterval(tower, supportStacks)));
            schedule.updateInterval(effectiveAttackInterval(tower, supportStacks), currentTick);
            if (schedule.tryClaim(currentTick)) {
                LivingEntity center = target.orElseThrow();
                switch (tower.type()) {
                    case ARROW, SNIPER -> center.damage(effectiveDamage(tower, supportStacks), stand);
                    case CANNON -> damageCannonTargets(tower, stand, center, supportStacks);
                    case FROST -> damageFrostTarget(tower, stand, center, supportStacks);
                    case LIGHTNING -> damageLightningTargets(tower, stand, center, supportStacks);
                    case FLAME -> damageFlameTargets(tower, stand, center, supportStacks);
                    case SUPPORT -> throw new IllegalStateException("support tower reached attack path");
                }
            }
        }
    }

    private Optional<LivingEntity> findTarget(TowerRecord tower, ArmorStand stand) {
        double range = effectiveRange(tower, stand);
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

    private void damageCannonTargets(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity center,
            int supportStacks) {
        double radius = settings.towers().cannonSplashRadius();
        EventEnemyTagger eventTagger = new EventEnemyTagger(plugin);
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
            monster.damage(effectiveDamage(tower, supportStacks), stand);
        }
    }

    private void damageFrostTarget(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity target,
            int supportStacks) {
        TowerSettings towerSettings = settings.towers();
        target.damage(effectiveDamage(tower, supportStacks), stand);
        int duration = towerSettings.slowDurationTicksFor(tower.type());
        if (duration <= 0) {
            return;
        }
        int amplifier = Math.max(
                0,
                (int) Math.ceil(towerSettings.slowPercentFor(tower.type()) * 4.0d) - 1);
        target.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                duration,
                amplifier,
                true,
                true,
                true));
    }

    private void damageLightningTargets(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity center,
            int supportStacks) {
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
        candidates.stream()
                .skip(1)
                .sorted(Comparator.comparingDouble(
                        candidate -> candidate.getLocation().distanceSquared(center.getLocation())))
                .limit(Math.max(0, towerSettings.chainCountFor(tower.type()) - 1L))
                .forEach(candidate -> candidate.damage(
                        effectiveDamage(tower, supportStacks), stand));
        center.damage(effectiveDamage(tower, supportStacks), stand);
    }

    private void damageFlameTargets(
            TowerRecord tower,
            ArmorStand stand,
            LivingEntity center,
            int supportStacks) {
        TowerSettings towerSettings = settings.towers();
        double radius = towerSettings.areaRadiusFor(tower.type());
        EventEnemyTagger eventTagger = new EventEnemyTagger(plugin);
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
            monster.damage(effectiveDamage(tower, supportStacks), stand);
            monster.setFireTicks(Math.max(
                    monster.getFireTicks(), towerSettings.burnDurationTicksFor(tower.type())));
        }
    }

    private int supportStacksFor(TowerRecord tower, ArmorStand stand) {
        TowerSettings towerSettings = settings.towers();
        if (towerSettings.supportStackLimit() <= 0) {
            return 0;
        }
        double radiusSquared = towerSettings.supportRadius() * towerSettings.supportRadius();
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

    private double effectiveRange(TowerRecord tower, ArmorStand stand) {
        int supportStacks = supportStacksFor(tower, stand);
        return settings.towers().rangeFor(tower.type())
                * Math.pow(settings.towers().supportRangeMultiplier(), supportStacks)
                * battleBoosts.multiplier(tower.id(), BattleBoostKind.RANGE);
    }

    private int effectiveAttackInterval(TowerRecord tower, int supportStacks) {
        long interval = Math.round(settings.towers().attackIntervalTicksFor(tower.type())
                * Math.pow(settings.towers().supportSpeedMultiplier(), supportStacks)
                * battleBoosts.multiplier(tower.id(), BattleBoostKind.SPEED));
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, interval));
    }

    private int effectiveDamage(TowerRecord tower, int supportStacks) {
        long damage = Math.round(settings.towers().damageFor(tower.type())
                * Math.pow(settings.towers().supportDamageMultiplier(), supportStacks)
                * battleBoosts.multiplier(tower.id(), BattleBoostKind.POWER));
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, damage));
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
            List<BattleBoost> boosts) {
    }

    private record RemovedItems(List<ItemStack> items) {
    }

    @Override
    public void close() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        attackSchedules.clear();
        upgradeInFlight.clear();
    }
}
