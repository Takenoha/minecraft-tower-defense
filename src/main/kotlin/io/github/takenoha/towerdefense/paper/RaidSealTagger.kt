package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.StageWaveSchedule
import java.util.Objects
import java.util.Optional
import java.util.OptionalLong
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

/** Creates and validates the non-stackable, database-backed raid-start token. */
class RaidSealTagger(plugin: Plugin) {
    private val markerKey: NamespacedKey
    private val versionKey: NamespacedKey
    private val sealIdKey: NamespacedKey
    private val stageLevelKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        markerKey = NamespacedKey(plugin, "raid_seal")
        versionKey = NamespacedKey(plugin, "raid_seal_version")
        sealIdKey = NamespacedKey(plugin, "raid_seal_id")
        stageLevelKey = NamespacedKey(plugin, "raid_seal_stage")
    }

    /** Template used by the stage-1 recipe; a craft event replaces its empty UUID marker. */
    fun recipeTemplate(): ItemStack = recipeTemplate(FOUNDATION_STAGE)

    /** Template used by a stage-specific recipe before craft-time UUID registration. */
    fun recipeTemplate(stageLevel: Long): ItemStack = createTemplate(stageLevel)

    fun create(sealId: UUID, stageLevel: Long): ItemStack {
        Objects.requireNonNull(sealId, "sealId")
        requireStage(stageLevel)
        val item = createTemplate(stageLevel)
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        data.set(sealIdKey, PersistentDataType.STRING, sealId.toString())
        data.set(stageLevelKey, PersistentDataType.LONG, stageLevel)
        meta.displayName(Component.text("襲撃の印", NamedTextColor.GOLD))
        meta.lore(
            listOf(
                Component.text("ステージ${stageLevel}の防衛戦を開始します", NamedTextColor.GRAY),
                Component.text("右クリックでチームのコアへ使用", NamedTextColor.GRAY),
                Component.text("真正性はサーバーの永続データで検証されます", NamedTextColor.DARK_GRAY),
            )
        )
        item.itemMeta = meta
        item.amount = 1
        return item
    }

    fun read(item: ItemStack?): Optional<RaidSealItemIdentity> {
        if (item == null || !isSupportedMaterial(item.type) || item.amount != 1) {
            return Optional.empty()
        }
        val meta = item.itemMeta
        return if (meta == null) Optional.empty() else read(meta.persistentDataContainer)
    }

    /** Returns whether a valid seal still uses the pre-UX ENDER_EYE material. */
    fun isLegacyMaterial(item: ItemStack?): Boolean =
        item != null && item.type == LEGACY_ITEM_MATERIAL && read(item).isPresent

    fun isRecipeTemplate(item: ItemStack?): Boolean = templateStage(item).isPresent

    /** Reads the stage encoded in a registered recipe result. */
    fun templateStage(item: ItemStack?): OptionalLong {
        if (item == null || !isSupportedMaterial(item.type) || item.amount != 1) {
            return OptionalLong.empty()
        }
        val meta = item.itemMeta ?: return OptionalLong.empty()
        val data = meta.persistentDataContainer
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        val marker = data.get(markerKey, PersistentDataType.BYTE)
        if (marker == null || marker != 1.toByte() || version == null ||
            version != ITEM_VERSION || data.get(sealIdKey, PersistentDataType.STRING) != null
        ) {
            return OptionalLong.empty()
        }
        val stage = data.get(stageLevelKey, PersistentDataType.LONG)
            ?: return OptionalLong.empty()
        return try {
            StageWaveSchedule.requireValidStageLevel(stage)
            OptionalLong.of(stage)
        } catch (_: IllegalArgumentException) {
            OptionalLong.empty()
        }
    }

    fun hasSealId(item: ItemStack?, sealId: UUID): Boolean {
        Objects.requireNonNull(sealId, "sealId")
        return read(item).map { identity -> identity.sealId == sealId }.orElse(false)
    }

    private fun createTemplate(stageLevel: Long): ItemStack {
        requireStage(stageLevel)
        val item = ItemStack(ITEM_MATERIAL, 1)
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        data.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION)
        data.set(stageLevelKey, PersistentDataType.LONG, stageLevel)
        meta.displayName(Component.text("襲撃の印", NamedTextColor.GOLD))
        meta.lore(
            listOf(
                Component.text("防衛戦を開始するための印", NamedTextColor.GRAY),
                Component.text("ステージ${stageLevel}用", NamedTextColor.GRAY),
            )
        )
        item.itemMeta = meta
        return item
    }

    private fun read(data: PersistentDataContainer): Optional<RaidSealItemIdentity> {
        val marker = data.get(markerKey, PersistentDataType.BYTE)
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        val sealId = data.get(sealIdKey, PersistentDataType.STRING)
        val stage = data.get(stageLevelKey, PersistentDataType.LONG)
        if (marker == null || marker != 1.toByte() || version == null ||
            version != ITEM_VERSION || sealId == null || stage == null
        ) {
            return Optional.empty()
        }
        try {
            StageWaveSchedule.requireValidStageLevel(stage)
        } catch (_: IllegalArgumentException) {
            return Optional.empty()
        }
        return try {
            Optional.of(RaidSealItemIdentity(UUID.fromString(sealId), stage))
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    private fun requireMeta(item: ItemStack): ItemMeta =
        Objects.requireNonNull(item.itemMeta, "raid seal metadata")

    private fun requireStage(stageLevel: Long): Long =
        StageWaveSchedule.requireValidStageLevel(stageLevel)

    private fun isSupportedMaterial(material: Material): Boolean =
        RaidSealMaterialPolicy.supports(material.name)

    companion object {
        const val ITEM_VERSION: Int = 1
        const val FOUNDATION_STAGE: Long = 1L

        @JvmField
        val ITEM_MATERIAL: Material = Material.valueOf(RaidSealMaterialPolicy.CURRENT_MATERIAL)

        @JvmField
        val LEGACY_ITEM_MATERIAL: Material = Material.valueOf(RaidSealMaterialPolicy.LEGACY_MATERIAL)
    }
}
