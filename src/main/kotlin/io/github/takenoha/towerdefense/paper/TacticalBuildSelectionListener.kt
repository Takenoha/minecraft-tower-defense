package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.DefenseRepository
import io.github.takenoha.towerdefense.persistence.OperationOutcome
import io.github.takenoha.towerdefense.persistence.TacticalBuildRepository
import io.github.takenoha.towerdefense.persistence.TacticalSelectionResult
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor
import io.github.takenoha.towerdefense.tactical.TacticalBuildCatalog
import io.github.takenoha.towerdefense.tactical.TacticalCandidateGenerator
import java.time.Instant
import java.util.Objects
import java.util.UUID
import java.util.concurrent.CompletionException
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/** Paper adapter for non-consumptive candidate selection before the existing start transaction. */
class TacticalBuildSelectionListener(
    plugin: JavaPlugin,
    defenses: DefenseRepository,
    tactical: TacticalBuildRepository,
    databaseExecutor: DatabaseExecutor,
    catalog: TacticalBuildCatalog,
    generator: TacticalCandidateGenerator,
    command: TowerDefenseCommand,
) : Listener {
    private companion object {
        @JvmStatic
        private fun rootMessage(failure: Throwable): String {
            var root = failure
            if (root is CompletionException && root.cause != null) {
                root = root.cause!!
            }
            while (root.cause != null) {
                root = root.cause!!
            }
            return root.message ?: root.javaClass.simpleName
        }
    }

    private val pluginValue = Objects.requireNonNull(plugin, "plugin")
    private val defensesValue = Objects.requireNonNull(defenses, "defenses")
    private val tacticalValue = Objects.requireNonNull(tactical, "tactical")
    private val databaseExecutorValue = Objects.requireNonNull(databaseExecutor, "databaseExecutor")
    private val catalogValue = Objects.requireNonNull(catalog, "catalog")
    private val generatorValue = Objects.requireNonNull(generator, "generator")
    private val commandValue = Objects.requireNonNull(command, "command")

    /** Creates or reloads one persisted candidate set and opens the selection screen. */
    fun beginSelection(player: Player, coreId: UUID, stage: Long, sealId: UUID) {
        Objects.requireNonNull(player, "player")
        Objects.requireNonNull(coreId, "coreId")
        Objects.requireNonNull(sealId, "sealId")
        player.sendMessage(Component.text("戦術候補を準備しています…", NamedTextColor.GRAY))
        databaseExecutorValue.submit {
            val team = defensesValue.findTeamByMember(player.uniqueId).orElseThrow {
                IllegalStateException("先にコアを設置したチームへ参加してください")
            }
            if (!team.ownerId().equals(player.uniqueId)) {
                throw IllegalStateException("戦術ビルドを確定できるのはチームオーナーだけです")
            }
            val core = defensesValue.findCore(coreId).orElseThrow {
                IllegalStateException("コアが見つかりません")
            }
            if (!core.teamId().equals(team.id())) {
                throw IllegalStateException("このチームのコアではありません")
            }
            if (defensesValue.loadTeamProgress(team.id()).unlockedLevel < stage) {
                throw IllegalStateException("このステージはまだ解放されていません")
            }
            val existing = tacticalValue.findGeneratedByTeamAndStage(
                team.id(),
                Math.toIntExact(stage),
            )
            if (existing.isPresent) {
                return@submit existing.orElseThrow()
            }
            val tacticalSessionId = UUID.randomUUID()
            val startOperationId = UUID.randomUUID()
            val candidates = generatorValue.generate(
                tacticalSessionId,
                startOperationId,
                team.id(),
                Math.toIntExact(stage),
                TacticalBuildCatalog.GENERATOR_VERSION,
                catalogValue.definitions(),
                Instant.now(),
            )
            tacticalValue.createCandidates(candidates)
        }.whenComplete { candidates, failure ->
            runOnMainThread {
                if (failure != null) {
                    player.sendMessage(
                        Component.text(
                            "戦術候補を準備できません: ${rootMessage(failure)}",
                            NamedTextColor.RED,
                        ),
                    )
                    return@runOnMainThread
                }
                val completedCandidates = Objects.requireNonNull(candidates, "candidates")
                val holder = TacticalBuildSelectionInventoryHolder(
                    completedCandidates.tacticalSessionId(),
                    coreId,
                    stage,
                    sealId,
                    player.uniqueId,
                    completedCandidates,
                )
                player.openInventory(TacticalBuildSelectionGui.create(holder))
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val topInventory = event.view.topInventory
        val holder = topInventory.holder
        if (holder !is TacticalBuildSelectionInventoryHolder) {
            return
        }
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (!holder.ownerId().equals(player.uniqueId) ||
            event.rawSlot < 0 ||
            event.rawSlot >= topInventory.size
        ) {
            return
        }
        val candidateIndex = TacticalBuildSelectionGui.candidateIndexAt(event.rawSlot)
        if (candidateIndex >= 0) {
            holder.select(holder.candidates().candidates()[candidateIndex].definition.id())
            TacticalBuildSelectionGui.refresh(
                topInventory,
                holder.candidates(),
                holder.selectedBuildId().orElse(null),
                holder.selectedBranchId().orElse(null),
            )
            return
        }
        val branchIndex = TacticalBuildSelectionGui.branchIndexAt(event.rawSlot)
        if (branchIndex >= 0) {
            val buildId = holder.selectedBuildId().orElse(null)
            if (buildId == null) {
                player.sendMessage(
                    Component.text("先にビルドを選択してください。", NamedTextColor.YELLOW),
                )
                return
            }
            val definition = holder.candidates().requireBuild(buildId)
            if (branchIndex >= definition.branchIds().size) {
                return
            }
            holder.selectBranch(definition.branchIds()[branchIndex])
            TacticalBuildSelectionGui.refresh(
                topInventory,
                holder.candidates(),
                buildId,
                holder.selectedBranchId().orElse(null),
            )
            return
        }
        if (event.rawSlot == TacticalBuildSelectionGui.CLOSE_SLOT) {
            player.closeInventory()
            return
        }
        if (event.rawSlot != TacticalBuildSelectionGui.CONFIRM_SLOT) {
            return
        }
        val buildId = holder.selectedBuildId().orElse(null)
        if (buildId == null) {
            player.sendMessage(Component.text("先に候補を1つ選択してください。", NamedTextColor.YELLOW))
            return
        }
        if (holder.branchRequired() && holder.selectedBranchId().isEmpty) {
            player.sendMessage(
                Component.text(
                    "このビルドは連射／射程の枝を1つ選択してください。",
                    NamedTextColor.YELLOW,
                ),
            )
            return
        }
        holder.markConfirming()
        player.closeInventory()
        val operationId = UUID.randomUUID()
        databaseExecutorValue.submit {
            tacticalValue.selectBuild(
                holder.tacticalSessionId(),
                holder.ownerId(),
                buildId,
                holder.selectedBranchId().orElse(null),
                operationId,
                Instant.now(),
            )
        }.whenComplete { result, failure ->
            runOnMainThread {
                if (failure != null) {
                    cancelAfterSelectionFailure(holder, player, failure)
                    return@runOnMainThread
                }
                val selected = Objects.requireNonNull(result, "result") as TacticalSelectionResult
                if (selected.outcome != OperationOutcome.APPLIED &&
                    selected.outcome != OperationOutcome.ALREADY_APPLIED
                ) {
                    cancelAfterSelectionFailure(
                        holder,
                        player,
                        IllegalStateException("戦術選択が適用されませんでした"),
                    )
                    return@runOnMainThread
                }
                player.sendMessage(
                    Component.text(
                        "戦術ビルド「$buildId" +
                            holder.selectedBranchId()
                                .map { branchId -> "（${branchId}ルート）" }
                                .orElse("") +
                            "」を確定しました。防衛を開始します。",
                        NamedTextColor.GREEN,
                    ),
                )
                commandValue.startWithSeal(
                    player,
                    holder.coreId(),
                    holder.stage(),
                    holder.sealId(),
                    holder.tacticalSessionId(),
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is TacticalBuildSelectionInventoryHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if (holder !is TacticalBuildSelectionInventoryHolder || holder.confirming()) {
            return
        }
        databaseExecutorValue.execute {
            tacticalValue.cancelBeforeSelection(
                holder.tacticalSessionId(),
                holder.ownerId(),
                UUID.randomUUID(),
                Instant.now(),
            )
        }.exceptionally { failure ->
            pluginValue.logger.warning(
                "Could not cancel closed tactical selection ${holder.tacticalSessionId()}: " +
                    rootMessage(failure),
            )
            null
        }
    }

    private fun cancelAfterSelectionFailure(
        holder: TacticalBuildSelectionInventoryHolder,
        player: Player,
        failure: Throwable,
    ) {
        databaseExecutorValue.execute {
            tacticalValue.cancelBeforeSelection(
                holder.tacticalSessionId(),
                holder.ownerId(),
                UUID.randomUUID(),
                Instant.now(),
            )
        }.whenComplete { _, cancelFailure ->
            runOnMainThread {
                player.sendMessage(
                    Component.text(
                        "戦術選択を確定できなかったため開始を取り消しました: ${rootMessage(failure)}",
                        NamedTextColor.RED,
                    ),
                )
                if (cancelFailure != null) {
                    pluginValue.logger.warning(
                        "Could not cancel tactical selection after failure: ${rootMessage(cancelFailure)}",
                    )
                }
            }
        }
    }

    private fun runOnMainThread(action: Runnable) {
        if (pluginValue.isEnabled) {
            Bukkit.getScheduler().runTask(pluginValue, action)
        }
    }
}
