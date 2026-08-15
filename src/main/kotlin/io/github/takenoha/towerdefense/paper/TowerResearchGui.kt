package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.config.TowerSettings
import io.github.takenoha.towerdefense.domain.TeamProgress
import io.github.takenoha.towerdefense.domain.TowerResearch
import io.github.takenoha.towerdefense.domain.TowerType
import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import java.util.Optional
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Builds the team-shared tower research purchase screen. */
class TowerResearchGui private constructor() {
    companion object {
        const val SIZE: Int = 27
        const val RESEARCH_START_SLOT: Int = 10
        const val CLOSE_SLOT: Int = 22

        @JvmStatic
        fun create(
            coreId: UUID,
            progress: TeamProgress,
            research: List<TowerResearch>,
            settings: TowerSettings,
        ): Inventory {
            Objects.requireNonNull(coreId, "coreId")
            Objects.requireNonNull(progress, "progress")
            Objects.requireNonNull(research, "research")
            Objects.requireNonNull(settings, "settings")
            val holder = TowerResearchInventoryHolder(coreId)
            val inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("タワー研究", NamedTextColor.LIGHT_PURPLE),
            )
            holder.attach(inventory)
            inventory.setItem(
                4,
                item(
                    Material.ENCHANTING_TABLE,
                    "チーム研究",
                    listOf(
                        "研究ポイント: ${progress.researchPoints}",
                        "研究Lvは同種タワーの個体Lv上限です。",
                    ),
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
            for (value in research) {
                val slot = RESEARCH_START_SLOT + value.towerType.ordinal
                val cost = settings.researchCost(value.researchLevel)
                val canPurchase = progress.researchPoints >= cost &&
                    value.researchLevel < Int.MAX_VALUE
                inventory.setItem(
                    slot,
                    item(
                        TowerItemTagger.materialFor(value.towerType),
                        "${value.towerType.displayName()}研究Lv${value.researchLevel}",
                        if (canPurchase) {
                            listOf(
                                "次の研究Lv: ${value.researchLevel + 1}",
                                "必要研究ポイント: $cost",
                                "クリックで研究を購入",
                            )
                        } else {
                            listOf(
                                "次の研究Lv費用: $cost",
                                if (progress.researchPoints < cost) {
                                    "研究ポイントが不足しています。"
                                } else {
                                    "これ以上研究できません。"
                                },
                            )
                        },
                        if (canPurchase) NamedTextColor.GREEN else NamedTextColor.GRAY,
                    ),
                )
            }
            inventory.setItem(
                CLOSE_SLOT,
                item(Material.BARRIER, "閉じる", emptyList(), NamedTextColor.RED),
            )
            return inventory
        }

        @JvmStatic
        fun towerTypeAt(slot: Int): Optional<TowerType> {
            val index = slot - RESEARCH_START_SLOT
            val types = TowerType.values()
            return if (index < 0 || index >= types.size) {
                Optional.empty()
            } else {
                Optional.of(types[index])
            }
        }

        private fun item(
            material: Material,
            name: String,
            lore: List<String>,
            color: NamedTextColor,
        ): ItemStack {
            val item = ItemStack(material)
            val meta: ItemMeta = Objects.requireNonNull(item.itemMeta, "research GUI metadata")
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
