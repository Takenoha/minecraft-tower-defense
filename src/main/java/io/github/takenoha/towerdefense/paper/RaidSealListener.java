package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.RaidSeal;
import io.github.takenoha.towerdefense.persistence.RaidSealRepository;
import io.github.takenoha.towerdefense.persistence.RaidSealStatus;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
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
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper-facing craft, reconciliation, and player start flow for raid seals. */
public final class RaidSealListener implements Listener {
    private final JavaPlugin plugin;
    private final RaidSealRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final CoreRegistry cores;
    private final TowerDefenseCommand command;
    private final RaidSealTagger tagger;

    public RaidSealListener(
            JavaPlugin plugin,
            RaidSealRepository repository,
            DatabaseExecutor databaseExecutor,
            CoreRegistry cores,
            TowerDefenseCommand command,
            RaidSealTagger tagger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.command = Objects.requireNonNull(command, "command");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
    }

    /** Registers one vanilla-material recipe for each of the first ten stages. */
    public void registerRecipe() {
        for (long stageLevel : RaidSealCatalog.recipeStages()) {
            NamespacedKey key = new NamespacedKey(plugin, "raid_seal_stage_" + stageLevel);
            ShapedRecipe recipe = configureRecipe(
                    new ShapedRecipe(key, tagger.recipeTemplate(stageLevel)),
                    Material.valueOf(RaidSealCatalog.ingredientNameFor(stageLevel)));
            Bukkit.removeRecipe(key);
            Bukkit.addRecipe(recipe);
        }
    }

    static ShapedRecipe configureRecipe(ShapedRecipe recipe, Material stageMaterial) {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(stageMaterial, "stageMaterial");
        recipe.shape(RaidSealRecipeDefinition.shape().toArray(String[]::new));
        recipe.setIngredient(
                'P',
                Material.valueOf(RaidSealRecipeDefinition.PAPER_MATERIAL));
        recipe.setIngredient('S', stageMaterial);
        return recipe;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (containsValidSeal(event.getInventory().getMatrix())) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Component.text(
                    "有効な襲撃の印はクラフト材料に使えません。", NamedTextColor.RED));
            return;
        }
        if (!tagger.isRecipeTemplate(event.getCurrentItem())) {
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Component.text(
                    "襲撃の印は1個ずつクラフトしてください。", NamedTextColor.YELLOW));
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        UUID sealId = UUID.randomUUID();
        long stageLevel = tagger.templateStage(event.getCurrentItem()).orElseThrow(
                () -> new IllegalStateException("raid seal recipe has no valid stage"));
        event.setCurrentItem(tagger.create(sealId, stageLevel));
        databaseExecutor.submit(() -> repository.register(
                        sealId, player.getUniqueId(), stageLevel, Instant.now()))
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        return;
                    }
                    runOnMainThread(() -> {
                        removeMatchingItems(sealId);
                        player.sendMessage(Component.text(
                                "襲撃の印を永続化できなかったため作成を取り消しました: "
                                        + rootMessage(failure),
                                NamedTextColor.RED));
                    });
                });
    }

    /** Vanilla Crafter has no player inventory matrix callback; keep plugin and seal paths closed. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (!shouldCancelCrafter(event.getRecipe(), event.getResult())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        reconcile(event.getPlayer());
    }

    /** Uses a physical seal directly on the registered core. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCoreInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Optional<RaidSealItemIdentity> identity = tagger.read(event.getItem());
        if (identity.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Optional<io.github.takenoha.towerdefense.persistence.CoreRecord> core = cores.at(clicked);
        if (core.isEmpty()) {
            return;
        }
        RaidSealItemIdentity value = identity.orElseThrow();
        command.startWithSeal(
                event.getPlayer(),
                core.orElseThrow().id(),
                value.stageLevel(),
                value.sealId());
    }

    /** Starts the selected stage while the physical item remains the authority for payment. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCoreGuiStart(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof CoreManagementInventoryHolder holder)
                || (event.getRawSlot() != CoreManagementGui.START_SLOT
                        && CoreManagementGui.stageLevelAt(event.getRawSlot()).isEmpty())
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        long requestedStage = CoreManagementGui.stageLevelAt(event.getRawSlot())
                .orElse(0L);
        Optional<RaidSealItemIdentity> seal = requestedStage > 0L
                ? findSeal(player, requestedStage)
                : findHighestSeal(player);
        if (seal.isEmpty()) {
            player.sendMessage(Component.text(
                    requestedStage > 0L
                            ? "選択したステージの襲撃の印を持っていません。"
                            : "使用可能な襲撃の印を持っていません。",
                    NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        RaidSealItemIdentity value = seal.orElseThrow();
        command.startWithSeal(
                player,
                holder.coreId(),
                value.stageLevel(),
                value.sealId());
    }

    private Optional<RaidSealItemIdentity> findSeal(Player player, long stageLevel) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            Optional<RaidSealItemIdentity> identity = tagger.read(
                    player.getInventory().getItem(slot));
            if (identity.isPresent() && identity.orElseThrow().stageLevel() == stageLevel) {
                return identity;
            }
        }
        return Optional.empty();
    }

    private Optional<RaidSealItemIdentity> findHighestSeal(Player player) {
        RaidSealItemIdentity highest = null;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            Optional<RaidSealItemIdentity> identity = tagger.read(
                    player.getInventory().getItem(slot));
            if (identity.isPresent()
                    && (highest == null
                            || identity.orElseThrow().stageLevel() > highest.stageLevel())) {
                highest = identity.orElseThrow();
            }
        }
        return Optional.ofNullable(highest);
    }

    private void reconcile(Player player) {
        UUID playerId = player.getUniqueId();
        databaseExecutor.submit(() -> {
            Map<UUID, RaidSeal> owned = new HashMap<>();
            for (RaidSeal seal : repository.loadForOwner(playerId)) {
                owned.put(seal.sealId(), seal);
            }
            Set<UUID> refunds = repository.loadAvailableRefunds(playerId).stream()
                    .map(RaidSeal::sealId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new Reconciliation(owned, refunds);
        }).whenComplete((data, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                plugin.getLogger().warning(
                        "Could not reconcile raid seals for " + playerId + ": "
                                + rootMessage(failure));
                return;
            }
            reconcileOwnedItems(player, data.owned());
            for (UUID refundId : data.refundIds()) {
                if (!hasPhysicalItem(refundId)) {
                    give(player, data.owned().get(refundId));
                }
            }
        }));
    }

    private void reconcileOwnedItems(Player player, Map<UUID, RaidSeal> owned) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            Optional<RaidSealItemIdentity> identity = tagger.read(item);
            if (identity.isEmpty()) {
                continue;
            }
            RaidSeal seal = owned.get(identity.orElseThrow().sealId());
            if (seal == null
                    || seal.status() != RaidSealStatus.AVAILABLE
                    || seal.stageLevel() != identity.orElseThrow().stageLevel()) {
                player.getInventory().setItem(slot, null);
            } else if (tagger.isLegacyMaterial(item)) {
                RaidSealItemIdentity value = identity.orElseThrow();
                player.getInventory().setItem(
                        slot,
                        tagger.create(value.sealId(), value.stageLevel()));
            }
        }
    }

    private boolean containsValidSeal(ItemStack[] matrix) {
        for (ItemStack item : matrix) {
            if (tagger.read(item).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldCancelCrafter(CraftingRecipe recipe, ItemStack result) {
        boolean pluginRecipe = recipe instanceof ShapedRecipe shaped
                && shaped.getKey().getNamespace().equals(plugin.getName().toLowerCase())
                && (shaped.getKey().getKey().equals("core")
                        || shaped.getKey().getKey().startsWith("raid_seal_stage_"));
        boolean resultTemplate = tagger.isRecipeTemplate(result);
        return RaidSealAutomationPolicy.cancelCrafter(
                pluginRecipe,
                resultTemplate,
                recipe instanceof ShapedRecipe shaped
                        && shaped.getChoiceMap().values().stream()
                                .filter(Objects::nonNull)
                                .anyMatch(choice -> choice.test(new ItemStack(Material.ECHO_SHARD))),
                recipe instanceof ShapedRecipe shaped
                        && shaped.getChoiceMap().values().stream()
                                .filter(Objects::nonNull)
                                .anyMatch(choice -> choice.test(new ItemStack(Material.ENDER_EYE))));
    }

    private void give(Player player, RaidSeal seal) {
        if (seal == null) {
            return;
        }
        ItemStack item = tagger.create(seal.sealId(), seal.stageLevel());
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(value -> player.getWorld().dropItemNaturally(
                player.getLocation(), value));
        player.sendMessage(Component.text(
                "技術的復旧で新しい襲撃の印が返却されました。", NamedTextColor.GREEN));
    }

    boolean hasPhysicalItem(UUID sealId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                if (tagger.hasSealId(player.getInventory().getItem(slot), sealId)) {
                    return true;
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (tagger.hasSealId(item.getItemStack(), sealId)) {
                    return true;
                }
            }
        }
        return false;
    }

    void removeMatchingItems(UUID sealId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                if (tagger.hasSealId(player.getInventory().getItem(slot), sealId)) {
                    player.getInventory().setItem(slot, null);
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (tagger.hasSealId(item.getItemStack(), sealId)) {
                    item.remove();
                }
            }
        }
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

    private record Reconciliation(Map<UUID, RaidSeal> owned, Set<UUID> refundIds) {
        private Reconciliation {
            owned = Map.copyOf(owned);
            refundIds = Set.copyOf(new HashSet<>(refundIds));
        }
    }
}
