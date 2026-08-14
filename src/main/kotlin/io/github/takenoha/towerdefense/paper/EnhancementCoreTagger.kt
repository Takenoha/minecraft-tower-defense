package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Creates and validates the plugin-owned individual-tower enhancement core. */
class EnhancementCoreTagger(plugin: Plugin) {
    private val markerKey: NamespacedKey
    private val versionKey: NamespacedKey
    private val itemIdKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        markerKey = NamespacedKey(plugin, "enhancement_core")
        versionKey = NamespacedKey(plugin, "enhancement_core_version")
        itemIdKey = NamespacedKey(plugin, "enhancement_core_id")
    }

    fun create(itemId: UUID, amount: Int): ItemStack {
        Objects.requireNonNull(itemId, "itemId")
        require(amount in 1..64) { "amount must be between 1 and 64" }
        val item = ItemStack(Material.NETHER_STAR, amount)
        val meta = Objects.requireNonNull(item.itemMeta, "enhancement core metadata")
        val data = meta.persistentDataContainer
        data.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION)
        data.set(itemIdKey, PersistentDataType.STRING, itemId.toString())
        meta.displayName(Component.text("強化コア", NamedTextColor.AQUA))
        meta.lore(listOf(Component.text("タワーの個体Lv強化に使用します", NamedTextColor.GRAY)))
        item.itemMeta = meta
        return item
    }

    fun isEnhancementCore(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.NETHER_STAR || item.amount <= 0) {
            return false
        }
        val meta: ItemMeta = item.itemMeta ?: return false
        val data = meta.persistentDataContainer
        val marker = data.get(markerKey, PersistentDataType.BYTE)
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        val itemId = data.get(itemIdKey, PersistentDataType.STRING)
        if (marker == null || marker != 1.toByte() || version == null ||
            version != ITEM_VERSION || itemId == null
        ) {
            return false
        }
        return try {
            UUID.fromString(itemId)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    companion object {
        const val ITEM_VERSION: Int = 1
    }
}
