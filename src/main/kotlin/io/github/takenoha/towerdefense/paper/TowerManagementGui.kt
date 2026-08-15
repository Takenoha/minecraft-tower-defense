package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerTargetPriority
import io.github.takenoha.towerdefense.persistence.BattleBoost
import io.github.takenoha.towerdefense.persistence.BattleBoostKind
import io.github.takenoha.towerdefense.persistence.ResourceType
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot
import io.github.takenoha.towerdefense.persistence.TowerRecord
import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import java.util.Optional
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Builds the tower-management screen for inspection, targeting, and retrieval. */
class TowerManagementGui private constructor() {
    companion object {
        const val SIZE: Int = 27
        const val PRIORITY_START_SLOT: Int = 9
        const val BOOST_POWER_SLOT: Int = 1
        const val BOOST_SPEED_SLOT: Int = 2
        const val BOOST_RANGE_SLOT: Int = 3
        const val REPAIR_SLOT: Int = 5
        const val UPGRADE_SLOT: Int = 18
        const val LEGACY_UPGRADE_SLOT: Int = 19
        const val REMOVE_SLOT: Int = 20
        const val HELP_SLOT: Int = 22
        const val CLOSE_SLOT: Int = 26

        private val PRIORITIES: List<TowerTargetPriority> = Collections.unmodifiableList(
            ArrayList(TowerTargetPriority.values().toList()),
        )

        @JvmStatic
        fun create(
            tower: TowerRecord,
            canRemove: Boolean,
            removalReason: String,
        ): Inventory = create(
            tower,
            canRemove,
            removalReason,
            tower.individualLevel(),
            0,
            0,
        )

        @JvmStatic
        fun create(
            tower: TowerRecord,
            canRemove: Boolean,
            removalReason: String,
            researchLevel: Int,
            shardCost: Int,
            enhancementCoreCost: Int,
        ): Inventory = create(
            tower,
            canRemove,
            removalReason,
            researchLevel,
            shardCost,
            enhancementCoreCost,
            false,
            0L,
            emptyMap(),
            0,
            0,
            0,
            false,
            0L,
            0L,
            0,
        )

        @JvmStatic
        fun create(
            tower: TowerRecord,
            canRemove: Boolean,
            removalReason: String,
            researchLevel: Int,
            shardCost: Int,
            enhancementCoreCost: Int,
            canBuyBoost: Boolean,
            battleFunds: Long,
            boosts: Map<BattleBoostKind, BattleBoost>,
            powerCost: Int,
            speedCost: Int,
            rangeCost: Int,
            canRepair: Boolean,
            currentHitPoints: Long,
            maximumHitPoints: Long,
            repairCost: Int,
        ): Inventory = create(
            tower,
            canRemove,
            removalReason,
            researchLevel,
            shardCost,
            enhancementCoreCost,
            canBuyBoost,
            battleFunds,
            boosts,
            powerCost,
            speedCost,
            rangeCost,
            canRepair,
            currentHitPoints,
            maximumHitPoints,
            repairCost,
            TeamResourceSnapshot(tower.teamId(), 0L, 0L, 0L, 0L),
        )

        @JvmStatic
        fun create(
            tower: TowerRecord,
            canRemove: Boolean,
            removalReason: String,
            researchLevel: Int,
            shardCost: Int,
            enhancementCoreCost: Int,
            canBuyBoost: Boolean,
            battleFunds: Long,
            boosts: Map<BattleBoostKind, BattleBoost>,
            powerCost: Int,
            speedCost: Int,
            rangeCost: Int,
            canRepair: Boolean,
            currentHitPoints: Long,
            maximumHitPoints: Long,
            repairCost: Int,
            resources: TeamResourceSnapshot,
        ): Inventory = create(
            tower,
            canRemove,
            removalReason,
            researchLevel,
            shardCost,
            enhancementCoreCost,
            canBuyBoost,
            battleFunds,
            boosts,
            powerCost,
            speedCost,
            rangeCost,
            canRepair,
            currentHitPoints,
            maximumHitPoints,
            repairCost,
            resources,
            false,
        )

        @JvmStatic
        fun create(
            tower: TowerRecord,
            canRemove: Boolean,
            removalReason: String,
            researchLevel: Int,
            shardCost: Int,
            enhancementCoreCost: Int,
            canBuyBoost: Boolean,
            battleFunds: Long,
            boosts: Map<BattleBoostKind, BattleBoost>,
            powerCost: Int,
            speedCost: Int,
            rangeCost: Int,
            canRepair: Boolean,
            currentHitPoints: Long,
            maximumHitPoints: Long,
            repairCost: Int,
            resources: TeamResourceSnapshot,
            legacyPaymentsEnabled: Boolean,
        ): Inventory {
            Objects.requireNonNull(tower, "tower")
            Objects.requireNonNull(removalReason, "removalReason")
            Objects.requireNonNull(boosts, "boosts")
            Objects.requireNonNull(resources, "resources")
            if (researchLevel <= 0 || shardCost < 0 || enhancementCoreCost < 0) {
                throw IllegalArgumentException("tower management values are invalid")
            }
            val holder = TowerManagementInventoryHolder(tower.id())
            val inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("タワー管理", NamedTextColor.GREEN),
            )
            holder.attach(inventory)

            inventory.setItem(
                4,
                item(
                    TowerItemTagger.materialFor(tower.type()),
                    tower.type().displayName() + "タワー",
                    listOf(
                        "個体Lv: ${tower.individualLevel()}",
                        "研究上限: $researchLevel",
                        "対象優先: ${tower.targetPriority().displayName()}",
                        "座標: ${tower.blockX()}, ${tower.blockY()}, ${tower.blockZ()}",
                        "右クリックしたタワーの操作画面",
                    ),
                    NamedTextColor.AQUA,
                ),
            )
            setBoostItem(
                inventory,
                BOOST_POWER_SLOT,
                BattleBoostKind.POWER,
                boosts,
                powerCost,
                canBuyBoost,
                battleFunds,
            )
            setBoostItem(
                inventory,
                BOOST_SPEED_SLOT,
                BattleBoostKind.SPEED,
                boosts,
                speedCost,
                canBuyBoost,
                battleFunds,
            )
            setBoostItem(
                inventory,
                BOOST_RANGE_SLOT,
                BattleBoostKind.RANGE,
                boosts,
                rangeCost,
                canBuyBoost,
                battleFunds,
            )
            val repairAvailable = canRepair && repairCost > 0 && currentHitPoints < maximumHitPoints
            inventory.setItem(
                REPAIR_SLOT,
                item(
                    if (repairAvailable) Material.ANVIL else Material.GRAY_DYE,
                    if (repairAvailable) "タワーを修理" else "タワー修理（現在不可）",
                    if (repairAvailable) {
                        listOf(
                            "HP: $currentHitPoints / $maximumHitPoints",
                            "戦闘資金: $battleFunds",
                            "費用: $repairCost",
                            "クリックでHPを回復",
                        )
                    } else {
                        listOf(
                            "HP: $currentHitPoints / $maximumHitPoints",
                            if (canRepair) "修理できるHPがありません。"
                            else "準備時間・ウェーブ間のみ修理できます。",
                        )
                    },
                    if (repairAvailable) NamedTextColor.YELLOW else NamedTextColor.GRAY,
                ),
            )
            for (index in PRIORITIES.indices) {
                val priority = PRIORITIES[index]
                val selected = priority == tower.targetPriority()
                inventory.setItem(
                    PRIORITY_START_SLOT + index,
                    item(
                        priorityMaterial(priority),
                        (if (selected) "▶ " else "") + priority.displayName(),
                        if (selected) listOf("現在の対象優先", "クリックで変更できます")
                        else listOf("クリックで対象優先を変更"),
                        if (selected) NamedTextColor.GREEN else NamedTextColor.YELLOW,
                    ),
                )
            }
            val enoughWallet = resources.balance(ResourceType.DEFENSE_POINTS) >= shardCost &&
                resources.balance(ResourceType.ENHANCEMENT_POINTS) >= enhancementCoreCost
            val canUpgrade = shardCost > 0 &&
                enhancementCoreCost > 0 &&
                tower.individualLevel() < researchLevel &&
                enoughWallet
            inventory.setItem(
                UPGRADE_SLOT,
                item(
                    if (canUpgrade) Material.NETHER_STAR else Material.GRAY_DYE,
                    if (canUpgrade) "個体Lvを強化" else "個体Lv強化（現在不可）",
                    if (canUpgrade) {
                        listOf(
                            "次の個体Lv: ${tower.individualLevel() + 1}",
                            "防衛ポイント: $shardCost / 残高 " +
                                resources.balance(ResourceType.DEFENSE_POINTS),
                            "強化ポイント: $enhancementCoreCost / 残高 " +
                                resources.balance(ResourceType.ENHANCEMENT_POINTS),
                            "クリックで資源庫ポイントを消費して強化",
                        )
                    } else if (tower.individualLevel() >= researchLevel) {
                        listOf("チーム研究Lvが上限です。")
                    } else {
                        listOf(
                            "防衛ポイント: $shardCost / 残高 " +
                                resources.balance(ResourceType.DEFENSE_POINTS),
                            "強化ポイント: $enhancementCoreCost / 残高 " +
                                resources.balance(ResourceType.ENHANCEMENT_POINTS),
                            "資源庫残高が不足しています。",
                        )
                    },
                    if (canUpgrade) NamedTextColor.AQUA else NamedTextColor.GRAY,
                ),
            )
            val legacyUpgradeAvailable = legacyPaymentsEnabled &&
                shardCost > 0 &&
                enhancementCoreCost > 0 &&
                tower.individualLevel() < researchLevel
            if (legacyUpgradeAvailable) {
                inventory.setItem(
                    LEGACY_UPGRADE_SLOT,
                    item(
                        Material.ENCHANTED_BOOK,
                        "旧素材で個体Lvを強化",
                        listOf(
                            "次の個体Lv: ${tower.individualLevel() + 1}",
                            "防衛の欠片: $shardCost",
                            "強化コア: $enhancementCoreCost",
                            "クリックで旧素材支払いを明示的に選択",
                            "旧方式は廃止予定です。",
                        ),
                        NamedTextColor.YELLOW,
                    ),
                )
            }
            inventory.setItem(
                REMOVE_SLOT,
                item(
                    if (canRemove) Material.EMERALD else Material.GRAY_DYE,
                    if (canRemove) "回収・移設" else "回収・移設（現在不可）",
                    if (canRemove) {
                        listOf(
                            "タワーアイテムを返却します。",
                            "返却したアイテムを別の場所へ設置できます。",
                            "防衛戦開始後は実行できません。",
                        )
                    } else {
                        listOf(removalReason)
                    },
                    if (canRemove) NamedTextColor.GREEN else NamedTextColor.GRAY,
                ),
            )
            inventory.setItem(
                CLOSE_SLOT,
                item(Material.BARRIER, "閉じる", emptyList(), NamedTextColor.RED),
            )
            inventory.setItem(
                HELP_SLOT,
                item(
                    Material.BOOK,
                    "操作方法",
                    listOf(
                        "回収したアイテムは個体Lvを保持します。",
                        "対象優先は回収・再設置後も保持します。",
                        "プレイヤー採掘・爆発・ピストンでは移動しません。",
                    ),
                    NamedTextColor.YELLOW,
                ),
            )
            return inventory
        }

        private fun setBoostItem(
            inventory: Inventory,
            slot: Int,
            kind: BattleBoostKind,
            boosts: Map<BattleBoostKind, BattleBoost>,
            cost: Int,
            canBuyBoost: Boolean,
            battleFunds: Long,
        ) {
            val boost = boosts[kind]
            val level = boost?.level() ?: 0
            val available = canBuyBoost && cost > 0
            val name = when (kind) {
                BattleBoostKind.POWER -> "戦闘ブースト: 威力"
                BattleBoostKind.SPEED -> "戦闘ブースト: 攻撃速度"
                BattleBoostKind.RANGE -> "戦闘ブースト: 射程"
            }
            inventory.setItem(
                slot,
                item(
                    if (available) Material.NETHER_STAR else Material.GRAY_DYE,
                    "$name Lv$level",
                    if (available) {
                        listOf(
                            "戦闘資金: $battleFunds",
                            "費用: $cost",
                            "クリックでこのタワーへ一段付与",
                        )
                    } else {
                        listOf(
                            "戦闘資金: $battleFunds",
                            if (canBuyBoost) "現在購入できません。"
                            else "準備時間・ウェーブ間のみ購入できます。",
                        )
                    },
                    if (available) NamedTextColor.GOLD else NamedTextColor.GRAY,
                ),
            )
        }

        @JvmStatic
        fun priorityAt(slot: Int): Optional<TowerTargetPriority> {
            val index = slot - PRIORITY_START_SLOT
            if (index < 0 || index >= PRIORITIES.size) {
                return Optional.empty()
            }
            return Optional.of(PRIORITIES[index])
        }

        private fun priorityMaterial(priority: TowerTargetPriority): Material = when (priority) {
            TowerTargetPriority.CORE_NEAREST -> Material.COMPASS
            TowerTargetPriority.NEAREST -> Material.CLOCK
            TowerTargetPriority.HEALTH_HIGH -> Material.DIAMOND
            TowerTargetPriority.HEALTH_LOW -> Material.REDSTONE
            TowerTargetPriority.BOSS -> Material.BEACON
        }

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
    }
}
