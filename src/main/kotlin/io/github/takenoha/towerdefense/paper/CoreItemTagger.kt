package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.Optional
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Adds and validates the stable identity carried by public core items. */
class CoreItemTagger(plugin: Plugin) {
    private val markerKey: NamespacedKey
    private val versionKey: NamespacedKey
    private val itemIdKey: NamespacedKey
    private val teamIdKey: NamespacedKey
    private val coreIdKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        markerKey = NamespacedKey(plugin, "core_item")
        versionKey = NamespacedKey(plugin, "core_item_version")
        itemIdKey = NamespacedKey(plugin, "core_item_id")
        teamIdKey = NamespacedKey(plugin, "core_team_id")
        coreIdKey = NamespacedKey(plugin, "core_id")
    }

    /** Returns the recipe result template. A craft event replaces its temporary marker with a UUID. */
    fun recipeTemplate(): ItemStack {
        val item = ItemStack(CoreMaterialPolicy.CURRENT_ITEM)
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        data.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION)
        meta.displayName(Component.text("コア", NamedTextColor.AQUA))
        meta.lore(
            listOf(
                Component.text("右クリックした固体ブロックをコアへ置換します", NamedTextColor.GRAY),
                Component.text("設置者がチームオーナーになります", NamedTextColor.GRAY),
            )
        )
        item.itemMeta = meta
        return item
    }

    fun createUnbound(itemId: UUID): ItemStack =
        create(itemId, Optional.empty<UUID>(), Optional.empty<UUID>())

    fun createBound(itemId: UUID, teamId: UUID): ItemStack {
        Objects.requireNonNull(teamId, "teamId")
        return create(itemId, Optional.of(teamId), Optional.empty<UUID>())
    }

    fun createBound(itemId: UUID, teamId: UUID, coreId: UUID): ItemStack {
        Objects.requireNonNull(teamId, "teamId")
        Objects.requireNonNull(coreId, "coreId")
        return create(itemId, Optional.of(teamId), Optional.of(coreId))
    }

    fun read(item: ItemStack?): Optional<CoreItemIdentity> {
        if (item == null || !CoreMaterialPolicy.isCoreItemMaterial(item.type) || item.amount != 1) {
            return Optional.empty()
        }
        val meta = item.itemMeta ?: return Optional.empty()
        return read(meta.persistentDataContainer)
    }

    fun isRecipeTemplate(item: ItemStack?): Boolean {
        if (item == null || !CoreMaterialPolicy.isCoreItemMaterial(item.type)) {
            return false
        }
        val meta = item.itemMeta ?: return false
        val data = meta.persistentDataContainer
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        return data.get(markerKey, PersistentDataType.BYTE) != null &&
            version != null && version == ITEM_VERSION &&
            data.get(itemIdKey, PersistentDataType.STRING) == null
    }

    fun hasItemId(item: ItemStack?, itemId: UUID?): Boolean {
        return read(item).map { identity -> identity.itemId == itemId }.orElse(false)
    }

    private fun create(
        itemId: UUID,
        teamId: Optional<UUID>,
        coreId: Optional<UUID>,
    ): ItemStack {
        Objects.requireNonNull(itemId, "itemId")
        val item = recipeTemplate()
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        data.set(itemIdKey, PersistentDataType.STRING, itemId.toString())
        teamId.ifPresent { value -> data.set(teamIdKey, PersistentDataType.STRING, value.toString()) }
        coreId.ifPresent { value -> data.set(coreIdKey, PersistentDataType.STRING, value.toString()) }
        if (teamId.isPresent) {
            meta.displayName(Component.text("移設用コア", NamedTextColor.AQUA))
            meta.lore(
                listOf(
                    Component.text("同じチームのコアを別の位置へ移設します", NamedTextColor.GRAY),
                    Component.text("防衛戦外・コアHP満タン時のみ使用できます", NamedTextColor.GRAY),
                )
            )
        }
        item.itemMeta = meta
        item.amount = 1
        return item
    }

    private fun read(data: PersistentDataContainer): Optional<CoreItemIdentity> {
        val marker = data.get(markerKey, PersistentDataType.BYTE)
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        val itemId = data.get(itemIdKey, PersistentDataType.STRING)
        if (marker == null || marker != 1.toByte() || version == null ||
            version != ITEM_VERSION || itemId == null
        ) {
            return Optional.empty()
        }
        return try {
            val teamId = data.get(teamIdKey, PersistentDataType.STRING)
            val coreId = data.get(coreIdKey, PersistentDataType.STRING)
            Optional.of(
                CoreItemIdentity(
                    UUID.fromString(itemId),
                    if (teamId == null) Optional.empty() else Optional.of(UUID.fromString(teamId)),
                    if (coreId == null) Optional.empty() else Optional.of(UUID.fromString(coreId)),
                )
            )
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    private fun requireMeta(item: ItemStack): ItemMeta =
        item.itemMeta ?: throw IllegalStateException("The core item has no metadata holder")

    companion object {
        const val ITEM_VERSION: Int = 1
    }
}
