package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Marks vanilla repair material while its database receipt is unresolved. */
class CoreRepairReceiptTagger(plugin: Plugin) {
    private val operationIdKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        operationIdKey = NamespacedKey(plugin, "core_repair_receipt")
    }

    fun tag(itemStack: ItemStack, operationId: UUID): ItemStack {
        Objects.requireNonNull(itemStack, "itemStack")
        Objects.requireNonNull(operationId, "operationId")
        val tagged = itemStack.clone()
        val meta = tagged.itemMeta
            ?: throw IllegalArgumentException("The repair receipt item has no metadata holder")
        meta.persistentDataContainer.set(operationIdKey, PersistentDataType.STRING, operationId.toString())
        tagged.itemMeta = meta
        return tagged
    }

    fun strip(itemStack: ItemStack): ItemStack {
        Objects.requireNonNull(itemStack, "itemStack")
        val stripped = itemStack.clone()
        val meta = stripped.itemMeta ?: return stripped
        meta.persistentDataContainer.remove(operationIdKey)
        stripped.itemMeta = meta
        return stripped
    }

    fun read(itemStack: ItemStack?): Optional<UUID> {
        if (itemStack == null) {
            return Optional.empty()
        }
        val meta = itemStack.itemMeta ?: return Optional.empty()
        val value = meta.persistentDataContainer.get(operationIdKey, PersistentDataType.STRING)
            ?: return Optional.empty()
        return try {
            Optional.of(UUID.fromString(value))
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    fun isTagged(itemStack: ItemStack?): Boolean = read(itemStack).isPresent

    fun isFor(itemStack: ItemStack?, operationId: UUID): Boolean =
        read(itemStack).filter { operationId == it }.isPresent
}
