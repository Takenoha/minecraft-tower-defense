package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.domain.CoreRepairCost;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.persistence.CoreMutationResult;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Player-facing core management screen for the repair and relocation slice. */
public final class CoreManagementListener implements Listener {
    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final DefenseRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final DefenseSessionManager sessions;
    private final CoreRegistry cores;
    private final CoreItemListener coreItems;
    private final DefenseShardTagger shardTagger;
    private final Set<UUID> repairInFlight = new java.util.HashSet<>();

    public CoreManagementListener(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            CoreItemListener coreItems,
            DefenseShardTagger shardTagger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.coreItems = Objects.requireNonNull(coreItems, "coreItems");
        this.shardTagger = Objects.requireNonNull(shardTagger, "shardTagger");
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CoreManagementInventoryHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() >= top.getSize()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() == CoreManagementGui.CLOSE_SLOT) {
            player.closeInventory();
        } else if (event.getRawSlot() == CoreManagementGui.REPAIR_SLOT) {
            beginRepair(player, holder.coreId());
        } else if (event.getRawSlot() == CoreManagementGui.RELOCATE_SLOT) {
            beginRelocation(player, holder.coreId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CoreManagementInventoryHolder) {
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
            return new CoreGuiData(core, team, progress, repairCost);
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
                    settings.core().repairMaterial()));
        }));
    }

    private void beginRepair(Player player, UUID coreId) {
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
                            settings.core()));
        }).whenComplete((data, lookupFailure) -> runOnMainThread(() -> {
            if (lookupFailure != null) {
                finishRepair(player, rootMessage(lookupFailure));
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
            RemovedItems removed = removeCostItems(
                    player,
                    repairMaterial,
                    data.repairCost().vanillaMaterialAmount(),
                    data.repairCost().defenseShardAmount());
            if (removed == null) {
                finishRepair(player, "修繕に必要な素材が不足しています。");
                return;
            }
            UUID operationId = UUID.randomUUID();
            databaseExecutor.submit(() -> repository.repairCore(
                            data.core().id(),
                            player.getUniqueId(),
                            data.repairCost().repairAmount(),
                            operationId,
                            Instant.now()))
                    .whenComplete((result, failure) -> runOnMainThread(() -> {
                        if (failure != null) {
                            refund(player, removed.items());
                            finishRepair(player, "修繕を永続化できなかったため素材を返却しました: "
                                    + rootMessage(failure));
                            return;
                        }
                        CoreRecord repaired = result.core().orElseThrow(
                                () -> new IllegalStateException("修繕結果にコアがありません"));
                        cores.replace(repaired);
                        repairInFlight.remove(player.getUniqueId());
                        player.sendMessage(Component.text(
                                "コアを修繕しました。HP: " + repaired.currentHitPoints()
                                        + " / " + repaired.maximumHitPoints(),
                                NamedTextColor.GREEN));
                        openCoreGui(player, repaired.id());
                    }));
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

    private RemovedItems removeCostItems(
            Player player,
            Material vanillaMaterial,
            long materialAmount,
            long shardAmount) {
        if (materialAmount > Integer.MAX_VALUE || shardAmount > Integer.MAX_VALUE) {
            return null;
        }
        long materialRemaining = materialAmount;
        long shardsRemaining = shardAmount;
        List<ItemStack> removed = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            boolean isVanilla = item.getType() == vanillaMaterial && !shardTagger.isShard(item);
            boolean isShard = shardTagger.isShard(item);
            long remaining = isVanilla ? materialRemaining : (isShard ? shardsRemaining : 0L);
            if (remaining <= 0L) {
                continue;
            }
            int quantity = (int) Math.min((long) item.getAmount(), remaining);
            ItemStack taken = item.clone();
            taken.setAmount(quantity);
            removed.add(taken);
            int left = item.getAmount() - quantity;
            player.getInventory().setItem(slot, left == 0 ? null : item.clone());
            if (left > 0) {
                player.getInventory().getItem(slot).setAmount(left);
            }
            if (isVanilla) {
                materialRemaining -= quantity;
            } else {
                shardsRemaining -= quantity;
            }
        }
        if (materialRemaining > 0L || shardsRemaining > 0L) {
            refund(player, removed);
            return null;
        }
        return new RemovedItems(List.copyOf(removed));
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

    private record CoreGuiData(
            CoreRecord core,
            TeamRecord team,
            TeamProgress progress,
            CoreRepairCost repairCost) {
    }

    private record RemovedItems(List<ItemStack> items) {
    }

    private record MapResult(boolean complete) {
    }
}
