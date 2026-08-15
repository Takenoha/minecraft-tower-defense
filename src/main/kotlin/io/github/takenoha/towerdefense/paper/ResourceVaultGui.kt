package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.ResourceType
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot
import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Displays team balances and active-event provisional claims without exposing item drops. */
class ResourceVaultGui private constructor() {
    companion object {
        const val SIZE: Int = 27
        const val DEFENSE_SLOT: Int = 11
        const val ENHANCEMENT_SLOT: Int = 15
        const val DEFENSE_TEN_SLOT: Int = 10
        const val DEFENSE_HUNDRED_SLOT: Int = 12
        const val DEFENSE_ALL_SLOT: Int = 13
        const val ENHANCEMENT_ONE_SLOT: Int = 14
        const val ENHANCEMENT_TEN_SLOT: Int = 16
        const val ENHANCEMENT_ALL_SLOT: Int = 17
        const val CLOSE_SLOT: Int = 22

        @JvmStatic
        fun create(coreId: UUID, resources: TeamResourceSnapshot): Inventory =
            create(coreId, resources, false, false)

        @JvmStatic
        fun create(
            coreId: UUID,
            resources: TeamResourceSnapshot,
            owner: Boolean,
            canWithdraw: Boolean,
        ): Inventory {
            Objects.requireNonNull(coreId, "coreId")
            Objects.requireNonNull(resources, "resources")
            val holder = ResourceVaultInventoryHolder(coreId)
            val inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("コア資源庫", NamedTextColor.LIGHT_PURPLE),
            )
            holder.attach(inventory)
            inventory.setItem(
                DEFENSE_SLOT,
                item(
                    Material.PRISMARINE_SHARD,
                    ResourceType.DEFENSE_POINTS.displayName(),
                    resourceLore(resources, ResourceType.DEFENSE_POINTS),
                    NamedTextColor.AQUA,
                ),
            )
            inventory.setItem(
                ENHANCEMENT_SLOT,
                item(
                    Material.NETHER_STAR,
                    ResourceType.ENHANCEMENT_POINTS.displayName(),
                    resourceLore(resources, ResourceType.ENHANCEMENT_POINTS),
                    NamedTextColor.GOLD,
                ),
            )
            inventory.setItem(
                DEFENSE_TEN_SLOT,
                action(
                    Material.PAPER,
                    "防衛Pを10P引き出す",
                    owner && canWithdraw && resources.balance(ResourceType.DEFENSE_POINTS) >= 10L,
                ),
            )
            inventory.setItem(
                DEFENSE_HUNDRED_SLOT,
                action(
                    Material.MAP,
                    "防衛Pを100P引き出す",
                    owner && canWithdraw && resources.balance(ResourceType.DEFENSE_POINTS) >= 100L,
                ),
            )
            inventory.setItem(
                DEFENSE_ALL_SLOT,
                action(
                    Material.CHEST,
                    "防衛Pを全額引き出す",
                    owner && canWithdraw && resources.balance(ResourceType.DEFENSE_POINTS) > 0L,
                ),
            )
            inventory.setItem(
                ENHANCEMENT_ONE_SLOT,
                action(
                    Material.PAPER,
                    "強化Pを1P引き出す",
                    owner && canWithdraw &&
                        resources.balance(ResourceType.ENHANCEMENT_POINTS) >= 1L,
                ),
            )
            inventory.setItem(
                ENHANCEMENT_TEN_SLOT,
                action(
                    Material.MAP,
                    "強化Pを10P引き出す",
                    owner && canWithdraw &&
                        resources.balance(ResourceType.ENHANCEMENT_POINTS) >= 10L,
                ),
            )
            inventory.setItem(
                ENHANCEMENT_ALL_SLOT,
                action(
                    Material.CHEST,
                    "強化Pを全額引き出す",
                    owner && canWithdraw &&
                        resources.balance(ResourceType.ENHANCEMENT_POINTS) > 0L,
                ),
            )
            inventory.setItem(
                CLOSE_SLOT,
                item(
                    Material.BARRIER,
                    "戻る",
                    listOf("コア管理へ戻ります。"),
                    NamedTextColor.RED,
                ),
            )
            return inventory
        }

        private fun resourceLore(
            resources: TeamResourceSnapshot,
            type: ResourceType,
        ): List<String> = listOf(
            "チーム残高: ${resources.balance(type)}P",
            "今回のチーム仮確保: ${resources.teamProvisional(type)}P",
            "今回のあなたの仮確保: ${resources.provisional(type)}P",
            "防衛戦の正常終了時に残高へ確定します。",
            "仮確保分は終端処理まで消費できません。",
            "確定残高は準備時間・ウェーブ間の強化や修理に使用できます。",
            "証票はチームに拘束され、同じチームのコアへ戻せます。",
        )

        private fun action(material: Material, name: String, enabled: Boolean): ItemStack = item(
            if (enabled) material else Material.GRAY_DYE,
            name,
            if (enabled) {
                listOf("クリックで携帯ポイント証票を1個発行します。")
            } else {
                listOf("オーナー権限、戦闘外、残高を確認してください。")
            },
            if (enabled) NamedTextColor.YELLOW else NamedTextColor.GRAY,
        )

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
