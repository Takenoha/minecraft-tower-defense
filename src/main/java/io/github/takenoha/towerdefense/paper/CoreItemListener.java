package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.persistence.CorePlacement;
import io.github.takenoha.towerdefense.persistence.CorePlacementResult;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import io.github.takenoha.towerdefense.runtime.TerrainMutationPolicy;
import java.time.Instant;
import java.util.HashSet;
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
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Public core recipe and the durable main-thread physical placement bridge. */
public final class CoreItemListener implements Listener {
    private static final Material CORE_BLOCK = Material.BEACON;

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final DefenseRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final DefenseSessionManager sessions;
    private final CoreRegistry cores;
    private final ThirdPartyRegionProtectionAdapter regionProtection;
    private final CoreItemTagger itemTagger;
    private final CoreBlockTagger blockTagger;
    private final Set<UUID> placementInFlight = new HashSet<>();
    private final Set<UUID> appliedItemIds;
    private final CombatAreaContext combatArea;

    public CoreItemListener(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            ThirdPartyRegionProtectionAdapter regionProtection,
            CoreItemTagger itemTagger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.regionProtection = Objects.requireNonNull(regionProtection, "regionProtection");
        this.itemTagger = Objects.requireNonNull(itemTagger, "itemTagger");
        blockTagger = new CoreBlockTagger(plugin);
        appliedItemIds = new HashSet<>(repository.loadAppliedCorePlacementItemIds());
        combatArea = new CombatAreaContext(settings);
    }

    /** Registers the initial heavy-cost vanilla recipe for an unbound core item. */
    public void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "core");
        ShapedRecipe recipe = new ShapedRecipe(key, itemTagger.recipeTemplate());
        recipe.shape("DDD", "DND", "DDD");
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('N', Material.NETHER_STAR);
        Bukkit.addRecipe(recipe);
    }

    /** Restores prepared physical placement intents before normal gameplay begins. */
    public void recoverPreparedPlacements() {
        for (CorePlacement placement : repository.loadPendingCorePlacements()) {
            if (recoverPhysicalPlacement(placement)) {
                databaseExecutor.execute(() -> repository.rollbackCorePlacement(
                        placement.operationId(), Instant.now()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        if (itemTagger.isRecipeTemplate(result)) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(Component.text(
                        "コアは1個ずつクラフトしてください。", NamedTextColor.YELLOW));
                return;
            }
            event.setCurrentItem(itemTagger.createUnbound(UUID.randomUUID()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        reconcileAppliedItems(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack held = event.getItem();
        Optional<CoreItemIdentity> identity = itemTagger.read(held);
        if (identity.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Block target = event.getClickedBlock();
        if (target == null) {
            return;
        }
        beginPlacement(event.getPlayer(), target, identity.orElseThrow(), true);
    }

    /** Starts a GUI-confirmed relocation without exposing a transient duplicate item. */
    void beginGuiRelocation(Player player, Block target, CoreRecord core) {
        CoreItemIdentity identity = new CoreItemIdentity(
                UUID.randomUUID(),
                Optional.of(core.teamId()),
                Optional.of(core.id()));
        beginPlacement(player, target, identity, false);
    }

    private void beginPlacement(
            Player player,
            Block target,
            CoreItemIdentity identity,
            boolean requiresHeldItem) {
        if (sessions.hasActiveSession()) {
            player.sendMessage(Component.text("防衛戦中はコアを設置できません。", NamedTextColor.RED));
            return;
        }
        if (!placementInFlight.add(player.getUniqueId())) {
            player.sendMessage(Component.text("コア設置を処理中です。", NamedTextColor.YELLOW));
            return;
        }
        String previousBlockData = target.getBlockData().getAsString();
        UUID worldId = target.getWorld().getUID();
        int blockX = target.getX();
        int blockY = target.getY();
        int blockZ = target.getZ();
        if (!isValidTarget(player, target)) {
            placementInFlight.remove(player.getUniqueId());
            return;
        }
        player.sendMessage(Component.text("コア設置位置を検証しています…", NamedTextColor.GRAY));
        UUID actorId = player.getUniqueId();
        databaseExecutor.submit(() -> preparePlan(
                        actorId,
                        identity,
                        worldId,
                        blockX,
                        blockY,
                        blockZ,
                        previousBlockData))
                .whenComplete((placement, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        placementInFlight.remove(actorId);
                        player.sendMessage(Component.text(
                                "コアを設置できません: " + rootMessage(failure), NamedTextColor.RED));
                        return;
                    }
                    applyPhysicalBlock(
                            player,
                            target,
                            placement.orElseThrow(),
                            identity.itemId(),
                            requiresHeldItem);
                }));
    }

    private Optional<CorePlacement> preparePlan(
            UUID actorId,
            CoreItemIdentity identity,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            String previousBlockData) {
        boolean relocating = identity.isBound();
        TeamRecord team;
        Optional<CoreRecord> current;
        if (relocating) {
            UUID teamId = identity.teamId().orElseThrow(
                    () -> new IllegalStateException("移設用コアにチームIDがありません"));
            team = repository.findTeam(teamId).orElseThrow(
                    () -> new IllegalStateException("移設用コアのチームが存在しません"));
            if (!team.members().contains(actorId)) {
                throw new IllegalStateException("このチームのコアへアクセスできません");
            }
            UUID coreId = identity.coreId().orElseGet(() -> repository.findCoreByTeam(team.id())
                    .map(CoreRecord::id)
                    .orElseThrow(() -> new IllegalStateException("移設用コアのコアIDがありません")));
            current = repository.findCore(coreId);
            if (current.isEmpty() || !current.orElseThrow().teamId().equals(team.id())) {
                throw new IllegalStateException("移設対象のコアが存在しません");
            }
            if (current.orElseThrow().currentHitPoints()
                    != current.orElseThrow().maximumHitPoints()) {
                throw new IllegalStateException("コアHPが満タンのときだけ移設できます");
            }
        } else {
            team = repository.findTeamByMember(actorId).orElseGet(
                    () -> repository.createSoloTeam(
                            soloTeamId(actorId), actorId, Instant.now()));
            if (!team.ownerId().equals(actorId)) {
                throw new IllegalStateException("コアの設置者はチームオーナーである必要があります");
            }
            current = repository.findCoreByTeam(team.id());
        }
        boolean rebuilding = !relocating
                && current.isPresent()
                && current.orElseThrow().currentHitPoints() == 0L;
        UUID coreId = relocating
                ? identity.coreId().orElseThrow()
                : (rebuilding ? current.orElseThrow().id() : identity.itemId());
        long maximumHitPoints = relocating
                ? current.orElseThrow().maximumHitPoints()
                : settings.core().maxHealth();
        CorePlacement placement = CorePlacement.prepared(
                UUID.randomUUID(),
                identity.itemId(),
                coreId,
                actorId,
                team.id(),
                worldId,
                blockX,
                blockY,
                blockZ,
                maximumHitPoints,
                settings.combat().minimumCoreDistance(),
                rebuilding,
                relocating,
                previousBlockData,
                Instant.now());
        return Optional.of(repository.prepareCorePlacement(placement));
    }

    private void applyPhysicalBlock(
            Player player,
            Block target,
            CorePlacement placement,
            UUID expectedItemId,
            boolean requiresHeldItem) {
        UUID actorId = player.getUniqueId();
        if ((requiresHeldItem
                && !itemTagger.hasItemId(
                        player.getInventory().getItemInMainHand(), expectedItemId))
                || !isStillOriginal(target, placement.previousBlockData())
                || sessions.hasActiveSession()
                || !isValidTarget(player, target)) {
            rollbackPrepared(
                    player,
                    target,
                    placement,
                    null,
                    "設置前に対象ブロックまたはインベントリが変更されました。");
            return;
        }
        RelocationPhysicalState relocationState = null;
        if (placement.relocatingExistingCore()) {
            relocationState = detachSourceCore(placement);
            if (relocationState == null) {
                rollbackPrepared(
                        player,
                        target,
                        placement,
                        null,
                        "移設元のコア状態を確認できないため、移設を取り消しました。");
                return;
            }
        }
        target.setType(CORE_BLOCK, false);
        if (!blockTagger.tag(target, placement)) {
            restore(target, placement.previousBlockData());
            rollbackPrepared(
                    player,
                    target,
                    placement,
                    relocationState,
                    "このPaperブロックはコア情報を保持できません。");
            return;
        }
        RelocationPhysicalState physicalState = relocationState;
        databaseExecutor.submit(() -> repository.applyCorePlacement(
                        placement.operationId(), Instant.now()))
                .whenComplete((result, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        restore(target, placement.previousBlockData());
                        rollbackPrepared(
                                player,
                                target,
                                placement,
                                physicalState,
                                "永続化に失敗したため設置を取り消しました。");
                        return;
                    }
                    finishPlacement(player, target, placement, result, expectedItemId);
                }));
    }

    private void finishPlacement(
            Player player,
            Block target,
            CorePlacement placement,
            CorePlacementResult result,
            UUID itemId) {
        placementInFlight.remove(player.getUniqueId());
        try {
            cores.replace(result.core());
        } catch (RuntimeException registryFailure) {
            plugin.getLogger().severe(
                    "Core placement applied but could not refresh the main-thread registry: "
                            + registryFailure.getMessage());
        }
        appliedItemIds.add(itemId);
        removeMatchingItems(itemId);
        player.sendMessage(Component.text(
                placement.relocatingExistingCore()
                        ? "コアを移設しました。チームの防衛戦拠点を更新しました。"
                        : "コアを設置しました。チームの防衛戦拠点として登録されています。",
                NamedTextColor.GREEN));
    }

    private void rollbackPrepared(
            Player player,
            Block target,
            CorePlacement placement,
            RelocationPhysicalState relocationState,
            String message) {
        boolean targetRestored = true;
        try {
            if (blockTagger.matches(target, placement)) {
                restore(target, placement.previousBlockData());
            }
            targetRestored = placement.previousBlockData().equals(
                    target.getBlockData().getAsString());
        } catch (RuntimeException failure) {
            targetRestored = false;
            plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not restore the target block for failed core placement",
                    failure);
        }
        boolean sourceRestored = true;
        if (relocationState != null) {
            sourceRestored = restoreSourceCore(relocationState);
        }
        placementInFlight.remove(player.getUniqueId());
        if (targetRestored && sourceRestored) {
            databaseExecutor.execute(() -> repository.rollbackCorePlacement(
                    placement.operationId(), Instant.now()));
        } else {
            plugin.getLogger().severe(
                    "Keeping failed core placement " + placement.operationId()
                            + " PREPARED because its physical state could not be restored");
        }
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private boolean isValidTarget(Player player, Block target) {
        if (!target.getWorld().equals(player.getWorld())
                || target.getWorld().getEnvironment() != World.Environment.NORMAL) {
            player.sendMessage(Component.text(
                    "コアはプレイヤーと同じOverworldへ設置してください。", NamedTextColor.RED));
            return false;
        }
        if (!target.getType().isSolid() || target.getState() instanceof TileState
                || cores.isCore(target)
                || TerrainMutationPolicy.isRequiredMaterial(target.getType().getKey().toString())) {
            player.sendMessage(Component.text(
                    "そのブロックはコア設置先にできません。通常の固体ブロックを選んでください。",
                    NamedTextColor.RED));
            return false;
        }
        List<String> violations = PaperCombatAreaSafetyValidator.violations(
                target.getWorld(),
                target.getX() + 0.5d,
                target.getZ() + 0.5d,
                combatArea.value(),
                settings.protection(),
                regionProtection);
        if (!violations.isEmpty()) {
            player.sendMessage(Component.text(
                    "コア周辺が保護境界を満たしません: " + String.join("; ", violations),
                    NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private static boolean isStillOriginal(Block block, String previousBlockData) {
        return block.getType().isSolid()
                && !(block.getState() instanceof TileState)
                && previousBlockData.equals(block.getBlockData().getAsString());
    }

    private static void restore(Block block, String previousBlockData) {
        block.setBlockData(Bukkit.createBlockData(previousBlockData), false);
    }

    private void reconcileAppliedItems(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (itemTagger.read(item).map(identity -> appliedItemIds.contains(identity.itemId()))
                    .orElse(false)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void removeMatchingItems(UUID itemId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                if (itemTagger.hasItemId(item, itemId)) {
                    player.getInventory().setItem(slot, null);
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (itemTagger.hasItemId(item.getItemStack(), itemId)) {
                    item.remove();
                }
            }
        }
    }

    private static UUID soloTeamId(UUID ownerId) {
        return UUID.nameUUIDFromBytes(("minecraft-tower-defense:solo:" + ownerId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private boolean recoverPhysicalPlacement(CorePlacement placement) {
        if (placement.relocatingExistingCore()) {
            return recoverPreparedRelocation(placement);
        }
        World world = Bukkit.getWorld(placement.worldId());
        if (world == null) {
            plugin.getLogger().severe(
                    "Cannot recover prepared core placement " + placement.operationId()
                            + ": world " + placement.worldId() + " is not loaded");
            return false;
        }
        Block block = world.getBlockAt(placement.blockX(), placement.blockY(), placement.blockZ());
        if (blockTagger.matches(block, placement)) {
            try {
                restore(block, placement.previousBlockData());
                if (placement.previousBlockData().equals(block.getBlockData().getAsString())) {
                    return true;
                }
            } catch (RuntimeException recoveryFailure) {
                plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Cannot restore prepared core placement " + placement.operationId(),
                        recoveryFailure);
                return false;
            }
            plugin.getLogger().severe(
                    "Prepared core placement " + placement.operationId()
                            + " did not restore its original block data");
            return false;
        }
        if (placement.previousBlockData().equals(block.getBlockData().getAsString())) {
            return true;
        }
        plugin.getLogger().severe(
                "Prepared core placement " + placement.operationId()
                        + " has an unknown physical block state; leaving it PREPARED");
        return false;
    }

    private RelocationPhysicalState detachSourceCore(CorePlacement placement) {
        CoreRecord core = repository.findCore(placement.coreId()).orElse(null);
        CorePlacement original = repository.findAppliedCorePlacementByCore(placement.coreId())
                .orElse(null);
        if (core == null || original == null) {
            return null;
        }
        World world = Bukkit.getWorld(core.worldId());
        if (world == null) {
            return null;
        }
        Block source = world.getBlockAt(core.blockX(), core.blockY(), core.blockZ());
        if (!cores.at(source).map(value -> value.id().equals(core.id())).orElse(false)
                || !blockTagger.matches(source, original)) {
            return null;
        }
        try {
            restore(source, original.previousBlockData());
        } catch (RuntimeException failure) {
            plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Could not restore the source block for core relocation",
                    failure);
            return null;
        }
        if (!original.previousBlockData().equals(source.getBlockData().getAsString())) {
            return null;
        }
        cores.unregister(core.id());
        return new RelocationPhysicalState(source, original);
    }

    private boolean restoreSourceCore(RelocationPhysicalState relocationState) {
        try {
            relocationState.source().setType(CORE_BLOCK, false);
            if (!blockTagger.tag(relocationState.source(), relocationState.originalPlacement())) {
                throw new IllegalStateException("the source beacon could not be tagged");
            }
            if (!blockTagger.matches(relocationState.source(), relocationState.originalPlacement())) {
                throw new IllegalStateException("the source beacon tag could not be verified");
            }
            cores.register(repository.findCore(relocationState.originalPlacement().coreId())
                    .orElseThrow(() -> new IllegalStateException("the source core row is missing")));
            return true;
        } catch (RuntimeException failure) {
            plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not restore the source core after relocation failure",
                    failure);
            return false;
        }
    }

    private boolean recoverPreparedRelocation(CorePlacement placement) {
        try {
            CoreRecord core = repository.findCore(placement.coreId()).orElse(null);
            CorePlacement original = repository.findAppliedCorePlacementByCore(placement.coreId())
                    .orElse(null);
            World targetWorld = Bukkit.getWorld(placement.worldId());
            if (core == null || original == null || targetWorld == null) {
                plugin.getLogger().severe(
                        "Cannot recover prepared core relocation " + placement.operationId()
                                + ": durable source state is incomplete");
                return false;
            }
            Block target = targetWorld.getBlockAt(
                    placement.blockX(), placement.blockY(), placement.blockZ());
            if (blockTagger.matches(target, placement)) {
                restore(target, placement.previousBlockData());
            } else if (!placement.previousBlockData().equals(target.getBlockData().getAsString())) {
                plugin.getLogger().severe(
                        "Prepared core relocation " + placement.operationId()
                                + " has an unknown target block state");
                return false;
            }
            World sourceWorld = Bukkit.getWorld(core.worldId());
            if (sourceWorld == null) {
                return false;
            }
            Block source = sourceWorld.getBlockAt(core.blockX(), core.blockY(), core.blockZ());
            if (blockTagger.matches(source, original)) {
                return true;
            }
            if (!original.previousBlockData().equals(source.getBlockData().getAsString())) {
                plugin.getLogger().severe(
                        "Prepared core relocation " + placement.operationId()
                                + " has an unknown source block state");
                return false;
            }
            source.setType(CORE_BLOCK, false);
            return blockTagger.tag(source, original);
        } catch (RuntimeException recoveryFailure) {
            plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not recover prepared core relocation " + placement.operationId(),
                    recoveryFailure);
            return false;
        }
    }

    private record RelocationPhysicalState(
            Block source,
            CorePlacement originalPlacement) {
    }

    private record CombatAreaContext(io.github.takenoha.towerdefense.domain.CombatArea value) {
        private CombatAreaContext(PluginSettings settings) {
            this(new io.github.takenoha.towerdefense.domain.CombatArea(
                    settings.combat().radius(),
                    settings.combat().spawnInner(),
                    settings.combat().spawnOuter(),
                    settings.combat().minimumCoreDistance(),
                    settings.combat().coreGap()));
        }
    }
}
