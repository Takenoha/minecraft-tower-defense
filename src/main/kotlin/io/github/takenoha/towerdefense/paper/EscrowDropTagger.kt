package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.EscrowDrop
import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Reads and writes escrow identity on both the display entity and its ItemStack. */
class EscrowDropTagger(plugin: Plugin) {
    private val eventIdKey: NamespacedKey
    private val dropIdKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        eventIdKey = NamespacedKey(plugin, "escrow_event_id")
        dropIdKey = NamespacedKey(plugin, "escrow_drop_id")
    }

    fun tag(item: Item, drop: EscrowDrop) {
        Objects.requireNonNull(item, "item")
        Objects.requireNonNull(drop, "drop")
        tag(item as Entity, TaggedEscrowDrop(drop.eventId, drop.dropId))
        item.itemStack = tag(item.itemStack, drop)
    }

    fun tag(entity: Entity, drop: TaggedEscrowDrop) {
        Objects.requireNonNull(entity, "entity")
        Objects.requireNonNull(drop, "drop")
        write(entity.persistentDataContainer, drop)
    }

    fun tag(itemStack: ItemStack, drop: EscrowDrop): ItemStack {
        Objects.requireNonNull(itemStack, "itemStack")
        Objects.requireNonNull(drop, "drop")
        val tagged = itemStack.clone()
        val meta = tagged.itemMeta
            ?: throw IllegalArgumentException("The escrow display item has no metadata holder")
        write(meta.persistentDataContainer, TaggedEscrowDrop(drop.eventId, drop.dropId))
        tagged.itemMeta = meta
        return tagged
    }

    fun read(entity: Entity): Optional<TaggedEscrowDrop> {
        Objects.requireNonNull(entity, "entity")
        return read(entity.persistentDataContainer)
    }

    fun read(itemStack: ItemStack): Optional<TaggedEscrowDrop> {
        Objects.requireNonNull(itemStack, "itemStack")
        val meta = itemStack.itemMeta
        return if (meta == null) Optional.empty() else read(meta.persistentDataContainer)
    }

    fun read(item: Item): Optional<TaggedEscrowDrop> {
        Objects.requireNonNull(item, "item")
        val entityTag = read(item as Entity)
        return if (entityTag.isPresent) entityTag else read(item.itemStack)
    }

    fun isTagged(itemStack: ItemStack?): Boolean = itemStack != null && read(itemStack).isPresent

    private fun read(data: PersistentDataContainer): Optional<TaggedEscrowDrop> {
        val eventId = data.get(eventIdKey, PersistentDataType.STRING)
        val dropId = data.get(dropIdKey, PersistentDataType.STRING)
        if (eventId == null || dropId == null) {
            return Optional.empty()
        }
        return try {
            Optional.of(TaggedEscrowDrop(UUID.fromString(eventId), UUID.fromString(dropId)))
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    private fun write(data: PersistentDataContainer, drop: TaggedEscrowDrop) {
        data.set(eventIdKey, PersistentDataType.STRING, drop.eventId.toString())
        data.set(dropIdKey, PersistentDataType.STRING, drop.dropId.toString())
    }
}
