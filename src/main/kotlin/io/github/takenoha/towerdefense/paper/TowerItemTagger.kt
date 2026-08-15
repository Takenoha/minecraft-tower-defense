package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerTargetPriority
import io.github.takenoha.towerdefense.domain.TowerType
import java.util.Objects
import java.util.Optional
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Adds and validates the unique identity carried by an uninstalled tower item. */
class TowerItemTagger(plugin: Plugin) {
    private val markerKey: NamespacedKey
    private val versionKey: NamespacedKey
    private val towerIdKey: NamespacedKey
    private val typeKey: NamespacedKey
    private val levelKey: NamespacedKey
    private val targetPriorityKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        markerKey = NamespacedKey(plugin, "tower_item")
        versionKey = NamespacedKey(plugin, "tower_item_version")
        towerIdKey = NamespacedKey(plugin, "tower_id")
        typeKey = NamespacedKey(plugin, "tower_type")
        levelKey = NamespacedKey(plugin, "tower_level")
        targetPriorityKey = NamespacedKey(plugin, "tower_target_priority")
    }

    /** Template used by the first Arrow tower recipe; a craft event fills its UUID. */
    fun recipeTemplate(): ItemStack = recipeTemplate(TowerType.ARROW)

    /** Creates a recipe result template for the supplied tower type. */
    fun recipeTemplate(type: TowerType): ItemStack {
        Objects.requireNonNull(type, "type")
        val item = ItemStack(materialFor(type))
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        data.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION)
        data.set(typeKey, PersistentDataType.STRING, type.id())
        data.set(levelKey, PersistentDataType.INTEGER, 1)
        data.set(targetPriorityKey, PersistentDataType.STRING, TowerTargetPriority.CORE_NEAREST.id())
        setDisplay(meta, type, 1, TowerTargetPriority.CORE_NEAREST)
        item.itemMeta = meta
        return item
    }

    fun create(towerId: UUID, type: TowerType, individualLevel: Int): ItemStack =
        create(towerId, type, individualLevel, TowerTargetPriority.CORE_NEAREST)

    fun create(
        towerId: UUID,
        type: TowerType,
        individualLevel: Int,
        targetPriority: TowerTargetPriority,
    ): ItemStack {
        Objects.requireNonNull(towerId, "towerId")
        Objects.requireNonNull(type, "type")
        Objects.requireNonNull(targetPriority, "targetPriority")
        require(individualLevel > 0) { "individualLevel must be positive" }
        val item = ItemStack(materialFor(type))
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        data.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION)
        data.set(towerIdKey, PersistentDataType.STRING, towerId.toString())
        data.set(typeKey, PersistentDataType.STRING, type.id())
        data.set(levelKey, PersistentDataType.INTEGER, individualLevel)
        data.set(targetPriorityKey, PersistentDataType.STRING, targetPriority.id())
        setDisplay(meta, type, individualLevel, targetPriority)
        item.itemMeta = meta
        item.amount = 1
        return item
    }

    fun read(item: ItemStack?): Optional<TowerItemIdentity> {
        if (item == null || item.amount != 1) {
            return Optional.empty()
        }
        val meta = item.itemMeta ?: return Optional.empty()
        val type = readType(meta.persistentDataContainer)
        return if (type.isPresent && item.type == materialFor(type.orElseThrow())) {
            read(meta.persistentDataContainer)
        } else {
            Optional.empty()
        }
    }

    fun isRecipeTemplate(item: ItemStack?): Boolean = recipeType(item).isPresent

    /** Returns the type encoded in a recipe template, if it is still a valid template. */
    fun recipeType(item: ItemStack?): Optional<TowerType> {
        if (item == null || item.amount != 1) {
            return Optional.empty()
        }
        val meta = item.itemMeta ?: return Optional.empty()
        val data = meta.persistentDataContainer
        val marker = data.get(markerKey, PersistentDataType.BYTE)
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        if (marker == null || marker != 1.toByte() || version == null ||
            version != ITEM_VERSION || data.get(towerIdKey, PersistentDataType.STRING) != null ||
            data.get(levelKey, PersistentDataType.INTEGER) == null
        ) {
            return Optional.empty()
        }
        val type = readType(data)
        return if (type.isPresent && item.type == materialFor(type.orElseThrow())) {
            type
        } else {
            Optional.empty()
        }
    }

    fun hasTowerId(item: ItemStack?, towerId: UUID): Boolean {
        Objects.requireNonNull(towerId, "towerId")
        return read(item).map { identity -> identity.towerId == towerId }.orElse(false)
    }

    private fun read(data: PersistentDataContainer): Optional<TowerItemIdentity> {
        val marker = data.get(markerKey, PersistentDataType.BYTE)
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        val towerId = data.get(towerIdKey, PersistentDataType.STRING)
        val type = data.get(typeKey, PersistentDataType.STRING)
        val level = data.get(levelKey, PersistentDataType.INTEGER)
        if (marker == null || marker != 1.toByte() || version == null ||
            version != ITEM_VERSION || towerId == null || type == null ||
            level == null || level <= 0
        ) {
            return Optional.empty()
        }
        return try {
            val priority = data.get(targetPriorityKey, PersistentDataType.STRING)
            Optional.of(
                TowerItemIdentity(
                    UUID.fromString(towerId),
                    TowerType.fromId(type),
                    level,
                    if (priority == null) TowerTargetPriority.CORE_NEAREST
                    else TowerTargetPriority.fromId(priority),
                )
            )
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    private fun readType(data: PersistentDataContainer): Optional<TowerType> {
        val type = data.get(typeKey, PersistentDataType.STRING) ?: return Optional.empty()
        return try {
            Optional.of(TowerType.fromId(type))
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    private fun setDisplay(
        meta: ItemMeta,
        type: TowerType,
        level: Int,
        targetPriority: TowerTargetPriority,
    ) {
        meta.displayName(Component.text("${type.displayName()}タワー", NamedTextColor.GREEN))
        meta.lore(
            listOf(
                Component.text("個体Lv$level", NamedTextColor.GRAY),
                Component.text("対象優先: ${targetPriority.displayName()}", NamedTextColor.GRAY),
                Component.text("設置後に自動でイベント敵を攻撃します", NamedTextColor.GRAY),
                Component.text("固有ID付き・1個のみ", NamedTextColor.DARK_GRAY),
            )
        )
    }

    private fun requireMeta(item: ItemStack): ItemMeta =
        item.itemMeta ?: throw IllegalStateException("The tower item has no metadata holder")

    companion object {
        const val ITEM_VERSION: Int = 1

        @JvmStatic
        fun materialFor(type: TowerType): Material = when (Objects.requireNonNull(type, "type")) {
            TowerType.ARROW -> Material.BOW
            TowerType.CANNON -> Material.DISPENSER
            TowerType.FROST -> Material.PACKED_ICE
            TowerType.LIGHTNING -> Material.LIGHTNING_ROD
            TowerType.SUPPORT -> Material.AMETHYST_BLOCK
            TowerType.SNIPER -> Material.CROSSBOW
            TowerType.FLAME -> Material.BLAZE_ROD
        }
    }
}
