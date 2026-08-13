package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.TacticalBuildRepository;
import io.github.takenoha.towerdefense.persistence.TacticalSelectionResult;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.tactical.TacticalBuildCatalog;
import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition;
import io.github.takenoha.towerdefense.tactical.TacticalCandidateGenerator;
import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper adapter for non-consumptive candidate selection before the existing start transaction. */
public final class TacticalBuildSelectionListener implements Listener {
    private final JavaPlugin plugin;
    private final DefenseRepository defenses;
    private final TacticalBuildRepository tactical;
    private final DatabaseExecutor databaseExecutor;
    private final TacticalBuildCatalog catalog;
    private final TacticalCandidateGenerator generator;
    private final TowerDefenseCommand command;

    public TacticalBuildSelectionListener(
            JavaPlugin plugin,
            DefenseRepository defenses,
            TacticalBuildRepository tactical,
            DatabaseExecutor databaseExecutor,
            TacticalBuildCatalog catalog,
            TacticalCandidateGenerator generator,
            TowerDefenseCommand command) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.defenses = Objects.requireNonNull(defenses, "defenses");
        this.tactical = Objects.requireNonNull(tactical, "tactical");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.command = Objects.requireNonNull(command, "command");
    }

    /** Creates or reloads one persisted candidate set and opens the selection screen. */
    public void beginSelection(Player player, UUID coreId, long stage, UUID sealId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(sealId, "sealId");
        player.sendMessage(Component.text("戦術候補を準備しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            TeamRecord team = defenses.findTeamByMember(player.getUniqueId())
                    .orElseThrow(() -> new IllegalStateException(
                            "先にコアを設置したチームへ参加してください"));
            if (!team.ownerId().equals(player.getUniqueId())) {
                throw new IllegalStateException("戦術ビルドを確定できるのはチームオーナーだけです");
            }
            CoreRecord core = defenses.findCore(coreId)
                    .orElseThrow(() -> new IllegalStateException("コアが見つかりません"));
            if (!core.teamId().equals(team.id())) {
                throw new IllegalStateException("このチームのコアではありません");
            }
            if (defenses.loadTeamProgress(team.id()).unlockedLevel() < stage) {
                throw new IllegalStateException("このステージはまだ解放されていません");
            }
            Optional<TacticalCandidateSet> existing = tactical.findGeneratedByTeamAndStage(
                    team.id(), Math.toIntExact(stage));
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            UUID tacticalSessionId = UUID.randomUUID();
            UUID startOperationId = UUID.randomUUID();
            TacticalCandidateSet candidates = generator.generate(
                    tacticalSessionId,
                    startOperationId,
                    team.id(),
                    Math.toIntExact(stage),
                    TacticalBuildCatalog.GENERATOR_VERSION,
                    catalog.definitions(),
                    Instant.now());
            return tactical.createCandidates(candidates);
        }).whenComplete((candidates, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(
                        "戦術候補を準備できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            TacticalBuildSelectionInventoryHolder holder =
                    new TacticalBuildSelectionInventoryHolder(
                            candidates.tacticalSessionId(),
                            coreId,
                            stage,
                            sealId,
                            player.getUniqueId(),
                            candidates);
            player.openInventory(TacticalBuildSelectionGui.create(holder));
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof TacticalBuildSelectionInventoryHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.ownerId().equals(player.getUniqueId())
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        int candidateIndex = TacticalBuildSelectionGui.candidateIndexAt(event.getRawSlot());
        if (candidateIndex >= 0) {
            holder.select(holder.candidates().candidates().get(candidateIndex).definition().id());
            TacticalBuildSelectionGui.refresh(
                    event.getView().getTopInventory(),
                    holder.candidates(),
                    holder.selectedBuildId().orElse(null),
                    holder.selectedBranchId().orElse(null));
            return;
        }
        int branchIndex = TacticalBuildSelectionGui.branchIndexAt(event.getRawSlot());
        if (branchIndex >= 0) {
            String buildId = holder.selectedBuildId().orElse(null);
            if (buildId == null) {
                player.sendMessage(Component.text(
                        "先にビルドを選択してください。",
                        NamedTextColor.YELLOW));
                return;
            }
            TacticalBuildDefinition definition = holder.candidates().requireBuild(buildId);
            if (branchIndex >= definition.branchIds().size()) {
                return;
            }
            holder.selectBranch(definition.branchIds().get(branchIndex));
            TacticalBuildSelectionGui.refresh(
                    event.getView().getTopInventory(),
                    holder.candidates(),
                    buildId,
                    holder.selectedBranchId().orElse(null));
            return;
        }
        if (event.getRawSlot() == TacticalBuildSelectionGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (event.getRawSlot() != TacticalBuildSelectionGui.CONFIRM_SLOT) {
            return;
        }
        String buildId = holder.selectedBuildId().orElse(null);
        if (buildId == null) {
            player.sendMessage(Component.text("先に候補を1つ選択してください。", NamedTextColor.YELLOW));
            return;
        }
        if (holder.branchRequired() && holder.selectedBranchId().isEmpty()) {
            player.sendMessage(Component.text(
                    "このビルドは連射／射程の枝を1つ選択してください。",
                    NamedTextColor.YELLOW));
            return;
        }
        holder.markConfirming();
        player.closeInventory();
        UUID operationId = UUID.randomUUID();
        databaseExecutor.submit(() -> tactical.selectBuild(
                holder.tacticalSessionId(),
                holder.ownerId(),
                buildId,
                holder.selectedBranchId().orElse(null),
                operationId,
                Instant.now())).whenComplete((result, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        cancelAfterSelectionFailure(holder, player, failure);
                        return;
                    }
                    TacticalSelectionResult selected = result;
                    if (selected.outcome() != OperationOutcome.APPLIED
                            && selected.outcome() != OperationOutcome.ALREADY_APPLIED) {
                        cancelAfterSelectionFailure(
                                holder,
                                player,
                                new IllegalStateException("戦術選択が適用されませんでした"));
                        return;
                    }
                    player.sendMessage(Component.text(
                            "戦術ビルド「" + buildId + "」"
                                    + holder.selectedBranchId()
                                            .map(branchId -> "（" + branchId + "ルート）")
                                            .orElse("")
                                    + "を確定しました。防衛を開始します。",
                            NamedTextColor.GREEN));
                    command.startWithSeal(
                            player,
                            holder.coreId(),
                            holder.stage(),
                            holder.sealId(),
                            holder.tacticalSessionId());
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof TacticalBuildSelectionInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder()
                instanceof TacticalBuildSelectionInventoryHolder holder)
                || holder.confirming()) {
            return;
        }
        databaseExecutor.execute(() -> tactical.cancelBeforeSelection(
                holder.tacticalSessionId(),
                holder.ownerId(),
                UUID.randomUUID(),
                Instant.now())).exceptionally(failure -> {
                    plugin.getLogger().warning(
                            "Could not cancel closed tactical selection "
                                    + holder.tacticalSessionId() + ": " + rootMessage(failure));
                    return null;
                });
    }

    private void cancelAfterSelectionFailure(
            TacticalBuildSelectionInventoryHolder holder,
            Player player,
            Throwable failure) {
        databaseExecutor.execute(() -> tactical.cancelBeforeSelection(
                holder.tacticalSessionId(),
                holder.ownerId(),
                UUID.randomUUID(),
                Instant.now())).whenComplete((ignored, cancelFailure) -> runOnMainThread(() -> {
                    player.sendMessage(Component.text(
                            "戦術選択を確定できなかったため開始を取り消しました: "
                                    + rootMessage(failure),
                            NamedTextColor.RED));
                    if (cancelFailure != null) {
                        plugin.getLogger().warning(
                                "Could not cancel tactical selection after failure: "
                                        + rootMessage(cancelFailure));
                    }
                }));
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
}
