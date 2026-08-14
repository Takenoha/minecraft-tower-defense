package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.tactical.TacticalBuildDefinition
import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet
import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Small, one-screen candidate selection UI used before the existing start transaction. */
class TacticalBuildSelectionGui private constructor() {
    companion object {
        const val SIZE: Int = 27
        @JvmField
        val CANDIDATE_SLOTS: IntArray = intArrayOf(11, 13, 15)
        @JvmField
        val BRANCH_SLOTS: IntArray = intArrayOf(3, 5)
        const val CONFIRM_SLOT: Int = 22
        const val CLOSE_SLOT: Int = 26

        @JvmStatic
        fun create(holder: TacticalBuildSelectionInventoryHolder): Inventory {
            Objects.requireNonNull(holder, "holder")
            val inventory = Bukkit.createInventory(
                holder,
                SIZE,
                Component.text("戦術ビルドを選択", NamedTextColor.DARK_PURPLE),
            )
            holder.attach(inventory)
            refresh(
                inventory,
                holder.candidates(),
                holder.selectedBuildId().orElse(null),
                holder.selectedBranchId().orElse(null),
            )
            inventory.setItem(
                CONFIRM_SLOT,
                item(
                    Material.LIME_CONCRETE,
                    "このビルドで開始",
                    listOf("選択後にクリックすると開始確認へ進みます。"),
                    NamedTextColor.GREEN,
                ),
            )
            inventory.setItem(
                CLOSE_SLOT,
                item(
                    Material.BARRIER,
                    "キャンセル",
                    listOf("閉じても開始印は消費されません。"),
                    NamedTextColor.RED,
                ),
            )
            return inventory
        }

        @JvmStatic
        fun refresh(
            inventory: Inventory,
            candidates: TacticalCandidateSet,
            selectedBuildId: String?,
        ) {
            refresh(inventory, candidates, selectedBuildId, null)
        }

        @JvmStatic
        fun refresh(
            inventory: Inventory,
            candidates: TacticalCandidateSet,
            selectedBuildId: String?,
            selectedBranchId: String?,
        ) {
            for (branchSlot in BRANCH_SLOTS) {
                inventory.setItem(branchSlot, null)
            }
            for (slot in CANDIDATE_SLOTS.indices) {
                val definition = candidates.candidates().get(slot).definition()
                val selected = definition.id() == selectedBuildId
                inventory.setItem(
                    CANDIDATE_SLOTS[slot],
                    item(
                        material(definition.iconMaterial()),
                        (if (selected) "▶ " else "") + definition.displayName(),
                        listOf(
                            definition.description(),
                            "カテゴリ: ${definition.category()}",
                            "レアリティ: ${definition.rarity()}",
                            if (selected) "選択中" else "クリックで選択",
                        ),
                        if (selected) NamedTextColor.GREEN else NamedTextColor.AQUA,
                    ),
                )
            }
            val selectedBuild = selectedBuildId ?: return
            val selected = candidates.requireBuild(selectedBuild)
            val branchIds = selected.branchIds()
            for (index in 0 until minOf(branchIds.size, BRANCH_SLOTS.size)) {
                val branchId = branchIds.get(index)
                val branchSelected = branchId == selectedBranchId
                inventory.setItem(
                    BRANCH_SLOTS[index],
                    item(
                        branchMaterial(branchId),
                        (if (branchSelected) "▶ " else "") + branchName(branchId),
                        branchLore(selected, branchId, branchSelected),
                        if (branchSelected) NamedTextColor.GREEN else NamedTextColor.LIGHT_PURPLE,
                    ),
                )
            }
        }

        @JvmStatic
        fun candidateIndexAt(rawSlot: Int): Int {
            for (index in CANDIDATE_SLOTS.indices) {
                if (CANDIDATE_SLOTS[index] == rawSlot) {
                    return index
                }
            }
            return -1
        }

        @JvmStatic
        fun branchIndexAt(rawSlot: Int): Int {
            for (index in BRANCH_SLOTS.indices) {
                if (BRANCH_SLOTS[index] == rawSlot) {
                    return index
                }
            }
            return -1
        }

        private fun branchLore(
            definition: TacticalBuildDefinition,
            branchId: String,
            selected: Boolean,
        ): List<String> {
            val lore = ArrayList<String>()
            lore.add("この枝のTier効果:")
            definition.nodes().asSequence()
                .filter { node ->
                    node.branchId().filter { value -> branchId == value }.isPresent
                }
                .sortedBy { node -> node.tier() }
                .forEach { node -> lore.add("Tier ${node.tier()}: ${node.description()}") }
            lore.add(if (selected) "選択中" else "クリックで選択")
            return lore
        }

        private fun branchName(branchId: String): String = when (branchId) {
            "rapid-fire" -> "連射ルート"
            "range" -> "射程ルート"
            else -> "${branchId}ルート"
        }

        private fun branchMaterial(branchId: String): Material = when (branchId) {
            "rapid-fire" -> Material.SPECTRAL_ARROW
            "range" -> Material.SPYGLASS
            else -> Material.PAPER
        }

        private fun material(name: String): Material = Material.matchMaterial(name) ?: Material.PAPER

        private fun item(
            material: Material,
            name: String,
            lore: List<String>,
            color: NamedTextColor,
        ): ItemStack {
            val item = ItemStack(material)
            val meta: ItemMeta = Objects.requireNonNull(item.itemMeta, "selection item metadata")
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
