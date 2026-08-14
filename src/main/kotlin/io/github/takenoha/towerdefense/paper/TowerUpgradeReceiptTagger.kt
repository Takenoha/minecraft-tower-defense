package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Marks physical legacy tower-upgrade materials until their database receipt is cleared. */
class TowerUpgradeReceiptTagger(plugin: Plugin) {
    private val operationIdKey: NamespacedKey
    private val materialKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        operationIdKey = NamespacedKey(plugin, "tower_upgrade_receipt")
        materialKey = NamespacedKey(plugin, "tower_upgrade_receipt_material")
    }

    fun tag(item: ItemStack, operationId: UUID, material: String): ItemStack {
        Objects.requireNonNull(item, "item")
        Objects.requireNonNull(operationId, "operationId")
        Objects.requireNonNull(material, "material")
        val tagged = item.clone()
        val meta = tagged.itemMeta
            ?: throw IllegalArgumentException("The tower receipt item has no metadata holder")
        val data = meta.persistentDataContainer
        data.set(operationIdKey, PersistentDataType.STRING, operationId.toString())
        data.set(materialKey, PersistentDataType.STRING, material)
        tagged.itemMeta = meta
        return tagged
    }

    fun strip(item: ItemStack): ItemStack {
        val stripped = item.clone()
        val meta = stripped.itemMeta
        if (meta != null) {
            val data = meta.persistentDataContainer
            data.remove(operationIdKey)
            data.remove(materialKey)
            stripped.itemMeta = meta
        }
        return stripped
    }

    fun operationId(item: ItemStack?): Optional<UUID> {
        if (item == null || item.itemMeta == null) {
            return Optional.empty()
        }
        val value = item.itemMeta!!.persistentDataContainer.get(
            operationIdKey,
            PersistentDataType.STRING,
        ) ?: return Optional.empty()
        return try {
            Optional.of(UUID.fromString(value))
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    fun material(item: ItemStack?): Optional<String> {
        if (item == null || item.itemMeta == null) {
            return Optional.empty()
        }
        return Optional.ofNullable(
            item.itemMeta!!.persistentDataContainer.get(materialKey, PersistentDataType.STRING),
        )
    }

    fun isTagged(item: ItemStack?): Boolean = operationId(item).isPresent && material(item).isPresent

    fun isFor(item: ItemStack?, operationId: UUID, material: String): Boolean =
        operationId(item).filter { operationId == it }.isPresent &&
            material(item).filter { material == it }.isPresent
}
