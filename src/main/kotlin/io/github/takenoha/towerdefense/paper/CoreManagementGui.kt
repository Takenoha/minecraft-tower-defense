package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.CoreRepairCost
import io.github.takenoha.towerdefense.domain.TeamProgress
import io.github.takenoha.towerdefense.persistence.CoreRecord
import io.github.takenoha.towerdefense.persistence.DefenseRepository
import io.github.takenoha.towerdefense.persistence.ResourceType
import io.github.takenoha.towerdefense.persistence.TeamRecord
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot
import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import java.util.OptionalLong
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Builds the first player-facing core management GUI. */
class CoreManagementGui private constructor() {
    companion object {
        const val SIZE: Int = 27
        const val TEAM_SLOT: Int = 0
        const val RESOURCE_VAULT_SLOT: Int = 1
        const val RESEARCH_DEPOSIT_SLOT: Int = 9
        const val TOWER_RESEARCH_SLOT: Int = 10
        const val REPAIR_SLOT: Int = 11
        const val LEGACY_REPAIR_SLOT: Int = 12
        const val START_SLOT: Int = 13
        const val RELOCATE_SLOT: Int = 15
        const val CLOSE_SLOT: Int = 22

        @JvmStatic
        fun create(
            core: CoreRecord,
            team: TeamRecord,
            progress: TeamProgress,
            repairCost: CoreRepairCost?,
            repairMaterialName: String?,
        ): Inventory = create(
            core,
            team,
            progress,
            repairCost,
            repairMaterialName,
            TeamResourceSnapshot(team.id(), 0L, 0L, 0L, 0L),
        )

        @JvmStatic
        fun create(
            core: CoreRecord,
            team: TeamRecord,
            progress: TeamProgress,
            repairCost: CoreRepairCost?,
            repairMaterialName: String?,
            resources: TeamResourceSnapshot,
        ): Inventory = create(
            core,
            team,
            progress,
            repairCost,
            repairMaterialName,
            resources,
            false,
        )

        @JvmStatic
        fun create(
            core: CoreRecord,
            team: TeamRecord,
            progress: TeamProgress,
            repairCost: CoreRepairCost?,
            repairMaterialName: String?,
            resources: TeamResourceSnapshot,
            legacyPaymentsEnabled: Boolean,
        ): Inventory {
            Objects.requireNonNull(core, "core")
            Objects.requireNonNull(team, "team")
            Objects.requireNonNull(progress, "progress")
            Objects.requireNonNull(resources, "resources")
            val holder = CoreManagementInventoryHolder(core.id())
            val inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("防衛コア管理", NamedTextColor.AQUA),
            )
            holder.attach(inventory)

            inventory.setItem(
                4,
                item(
                    CoreMaterialPolicy.CURRENT_BLOCK,
                    "コア状態",
                    listOf(
                        "HP: ${core.currentHitPoints()} / ${core.maximumHitPoints()}",
                        "位置: ${core.blockX()}, ${core.blockY()}, ${core.blockZ()}",
                        "最高クリアLv: ${progress.highestClearedLevel}",
                        "研究ポイント: ${progress.researchPoints}",
                    ),
                    NamedTextColor.AQUA,
                ),
            )

            val memberLore = ArrayList<String>()
            memberLore.add("チーム名: ${team.displayName()}")
            memberLore.add("オーナー: ${playerName(team.ownerId())}")
            memberLore.add(
                "メンバー: ${team.members().size} / ${DefenseRepository.MAX_TEAM_MEMBERS}人",
            )
            team.members().sorted()
                .map(::playerName)
                .forEach { memberLore.add(it) }
            memberLore.add("クリックでチーム管理を開きます。")
            inventory.setItem(
                TEAM_SLOT,
                item(Material.PLAYER_HEAD, "チーム", memberLore, NamedTextColor.GREEN),
            )
            inventory.setItem(
                RESOURCE_VAULT_SLOT,
                item(
                    Material.PRISMARINE_SHARD,
                    "コア資源庫",
                    listOf(
                        "防衛ポイント: ${resources.balance(ResourceType.DEFENSE_POINTS)}",
                        "強化ポイント: ${resources.balance(ResourceType.ENHANCEMENT_POINTS)}",
                        "今回のチーム仮確保（防衛）: " +
                            resources.teamProvisional(ResourceType.DEFENSE_POINTS),
                        "あなたの今回の仮確保（防衛）: " +
                            resources.provisional(ResourceType.DEFENSE_POINTS),
                        "今回のチーム仮確保（強化）: " +
                            resources.teamProvisional(ResourceType.ENHANCEMENT_POINTS),
                        "あなたの今回の仮確保（強化）: " +
                            resources.provisional(ResourceType.ENHANCEMENT_POINTS),
                        "クリックでコア資源庫を開きます。",
                    ),
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
            inventory.setItem(
                RESEARCH_DEPOSIT_SLOT,
                item(
                    Material.AMETHYST_SHARD,
                    "研究結晶を納品",
                    listOf(
                        "自分のインベントリ内の発行元チームの研究結晶を納品します。",
                        "納品数: 有効な全スタックの合計",
                        "現在の研究ポイント: ${progress.researchPoints}",
                        "クリックで納品",
                    ),
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
            inventory.setItem(
                TOWER_RESEARCH_SLOT,
                item(
                    Material.ENCHANTING_TABLE,
                    "タワー研究",
                    listOf(
                        "現在の研究ポイント: ${progress.researchPoints}",
                        "タワー種別ごとの研究Lvを上げます。",
                        "クリックで研究画面を開きます。",
                    ),
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )

            if (repairCost == null) {
                inventory.setItem(
                    REPAIR_SLOT,
                    item(Material.ANVIL, "修繕不要", listOf("コアは最大HPです。"), NamedTextColor.GRAY),
                )
            } else {
                inventory.setItem(
                    REPAIR_SLOT,
                    item(
                        Material.ANVIL,
                        "コアを全修繕",
                        listOf(
                            "不足HP: ${repairCost.repairAmount}",
                            "$repairMaterialName: ${repairCost.vanillaMaterialAmount}",
                            "防衛ポイント: ${repairCost.defenseShardAmount}（残高: " +
                                resources.balance(ResourceType.DEFENSE_POINTS) + ")",
                            "不足: ${maxOf(
                                0L,
                                repairCost.defenseShardAmount -
                                    resources.balance(ResourceType.DEFENSE_POINTS),
                            )}P",
                            "クリックで資源庫と材料を消費して修繕",
                        ),
                        NamedTextColor.YELLOW,
                    ),
                )
            }
            if (legacyPaymentsEnabled && repairCost != null) {
                inventory.setItem(
                    LEGACY_REPAIR_SLOT,
                    item(
                        Material.IRON_INGOT,
                        "旧素材でコアを修繕",
                        listOf(
                            "右クリックで旧素材支払いを明示的に選択します。",
                            "防衛ポイント残高があっても旧素材を使用します。",
                            "旧方式は廃止予定です。",
                        ),
                        NamedTextColor.YELLOW,
                    ),
                )
            }
            inventory.setItem(
                START_SLOT,
                item(
                    Material.ECHO_SHARD,
                    "所持中の最高ステージを開始",
                    listOf(
                        "所持中で最も高いステージの襲撃の印を1個消費します。",
                        "防衛範囲内にいるチームメンバーが参加します。",
                        "ステージを指定する場合は上段のステージボタンを押します。",
                    ),
                    NamedTextColor.GOLD,
                ),
            )
            inventory.setItem(
                RELOCATE_SLOT,
                item(
                    Material.COMPASS,
                    "コアを移設",
                    listOf(
                        "満タンのコアのみ移設できます。",
                        "先に移設先の固体ブロックを見てください。",
                        "設置タワーがある場合は移設できません。",
                    ),
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
            inventory.setItem(
                CLOSE_SLOT,
                item(Material.BARRIER, "閉じる", emptyList(), NamedTextColor.RED),
            )

            for (stageLevel in RaidSealCatalog.recipeStages()) {
                val unlocked = progress.unlockedLevel >= stageLevel
                inventory.setItem(
                    RaidSealCatalog.slotForStage(stageLevel),
                    item(
                        if (unlocked) Material.ECHO_SHARD else Material.GRAY_STAINED_GLASS_PANE,
                        if (unlocked) "ステージ$stageLevel" else "ステージ$stageLevel（未解放）",
                        if (unlocked) {
                            listOf(
                                "ステージ${stageLevel}の襲撃の印を消費して開始します。",
                                "クリックでこのステージを選択します。",
                            )
                        } else {
                            listOf("前のステージを勝利すると解放されます。")
                        },
                        if (unlocked) NamedTextColor.GOLD else NamedTextColor.DARK_GRAY,
                    ),
                )
            }
            return inventory
        }

        @JvmStatic
        fun stageLevelAt(rawSlot: Int): OptionalLong = RaidSealCatalog.stageAtSlot(rawSlot)

        private fun item(
            material: Material,
            name: String,
            lore: List<String>,
            color: NamedTextColor,
        ): ItemStack {
            val item = ItemStack(material)
            val meta: ItemMeta = Objects.requireNonNull(item.itemMeta, "GUI item metadata")
            meta.displayName(Component.text(name, color))
            meta.lore(
                Collections.unmodifiableList(
                    ArrayList(lore.map { line -> Component.text(line, NamedTextColor.GRAY) }),
                ),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            item.itemMeta = meta
            return item
        }

        private fun playerName(playerId: UUID): String {
            val player: OfflinePlayer = Bukkit.getOfflinePlayer(playerId)
            return player.name ?: playerId.toString().substring(0, 8)
        }
    }
}
