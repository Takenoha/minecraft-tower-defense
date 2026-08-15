package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Adds and removes the short-lived receipt used to reconcile an inventory/database stop window. */
class RewardQueueReceiptTagger(plugin: Plugin) {
    private val queueIdKey: NamespacedKey
    private val operationIdKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        queueIdKey = NamespacedKey(plugin, "reward_queue_id")
        operationIdKey = NamespacedKey(plugin, "reward_delivery_operation_id")
    }

    fun tag(itemStack: ItemStack, receipt: RewardQueueReceipt): ItemStack {
        Objects.requireNonNull(itemStack, "itemStack")
        Objects.requireNonNull(receipt, "receipt")
        val tagged = itemStack.clone()
        val meta = tagged.itemMeta
            ?: throw IllegalArgumentException("The reward item has no metadata holder")
        val data = meta.persistentDataContainer
        data.set(queueIdKey, PersistentDataType.STRING, receipt.queueId.toString())
        data.set(operationIdKey, PersistentDataType.STRING, receipt.operationId.toString())
        tagged.itemMeta = meta
        return tagged
    }

    /** Removes only this delivery system's receipt metadata. */
    fun strip(itemStack: ItemStack): ItemStack {
        Objects.requireNonNull(itemStack, "itemStack")
        val stripped = itemStack.clone()
        val meta = stripped.itemMeta ?: return stripped
        val data = meta.persistentDataContainer
        data.remove(queueIdKey)
        data.remove(operationIdKey)
        stripped.itemMeta = meta
        return stripped
    }

    fun read(itemStack: ItemStack): Optional<RewardQueueReceipt> {
        Objects.requireNonNull(itemStack, "itemStack")
        val meta = itemStack.itemMeta
        return if (meta == null) Optional.empty() else read(meta.persistentDataContainer)
    }

    fun isTagged(itemStack: ItemStack?): Boolean = itemStack != null && read(itemStack).isPresent

    private fun read(data: PersistentDataContainer): Optional<RewardQueueReceipt> {
        val queueId = data.get(queueIdKey, PersistentDataType.STRING)
        val operationId = data.get(operationIdKey, PersistentDataType.STRING)
        if (queueId == null || operationId == null) {
            return Optional.empty()
        }
        return try {
            Optional.of(RewardQueueReceipt(UUID.fromString(queueId), UUID.fromString(operationId)))
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }
}
