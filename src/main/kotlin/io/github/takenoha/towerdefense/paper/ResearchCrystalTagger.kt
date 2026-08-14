package io.github.takenoha.towerdefense.paper

import java.util.Locale
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

/** Creates and validates the team-bound research crystal used by the core deposit flow. */
class ResearchCrystalTagger private constructor(
    normalizedNamespace: String,
    normalized: Boolean,
) {
    private val markerKey: NamespacedKey
    private val versionKey: NamespacedKey
    private val batchIdKey: NamespacedKey
    private val teamIdKey: NamespacedKey
    private val issuedQuantityKey: NamespacedKey
    private val segmentOffsetKey: NamespacedKey
    private val segmentQuantityKey: NamespacedKey
    private val redemptionOperationKey: NamespacedKey

    /** Creates a tagger using the plugin name as the lower-case key namespace. */
    constructor(plugin: Plugin) : this(
        Objects.requireNonNull(plugin, "plugin").name.lowercase(Locale.ROOT),
        true,
    )

    /** Compatibility constructor for package-local namespace tests and callers. */
    constructor(namespace: String) : this(
        Objects.requireNonNull(namespace, "namespace").lowercase(Locale.ROOT),
        true,
    )

    init {
        markerKey = NamespacedKey(normalizedNamespace, "research_crystal")
        versionKey = NamespacedKey(normalizedNamespace, "research_crystal_version")
        batchIdKey = NamespacedKey(normalizedNamespace, "research_crystal_batch_id")
        teamIdKey = NamespacedKey(normalizedNamespace, "research_crystal_team_id")
        issuedQuantityKey = NamespacedKey(normalizedNamespace, "research_crystal_issued_quantity")
        segmentOffsetKey = NamespacedKey(normalizedNamespace, "research_crystal_segment_offset")
        segmentQuantityKey = NamespacedKey(normalizedNamespace, "research_crystal_segment_quantity")
        redemptionOperationKey = NamespacedKey(normalizedNamespace, "research_crystal_redemption")
    }

    /** Creates one stack unit; the delivery bridge splits the queue quantity safely. */
    fun create(batchId: UUID, teamId: UUID, issuedQuantity: Int): ItemStack =
        create(batchId, teamId, issuedQuantity, null, null)

    /** Creates one deterministic delivery segment within an issuance batch. */
    fun create(
        batchId: UUID,
        teamId: UUID,
        issuedQuantity: Int,
        segmentOffset: Int?,
        segmentQuantity: Int?,
    ): ItemStack {
        Objects.requireNonNull(batchId, "batchId")
        Objects.requireNonNull(teamId, "teamId")
        if (issuedQuantity <= 0) {
            throw IllegalArgumentException("issuedQuantity must be positive")
        }
        if ((segmentOffset == null) != (segmentQuantity == null)) {
            throw IllegalArgumentException("segmentOffset and segmentQuantity must be supplied together")
        }
        if (segmentOffset != null &&
            (segmentOffset < 0 ||
                segmentQuantity!! <= 0 ||
                segmentQuantity > STACK_LIMIT ||
                segmentOffset.toLong() + segmentQuantity > issuedQuantity)
        ) {
            throw IllegalArgumentException("research crystal segment is invalid")
        }
        val item = ItemStack(Material.AMETHYST_SHARD)
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        data.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION)
        data.set(batchIdKey, PersistentDataType.STRING, batchId.toString())
        data.set(teamIdKey, PersistentDataType.STRING, teamId.toString())
        data.set(issuedQuantityKey, PersistentDataType.INTEGER, issuedQuantity)
        if (segmentOffset != null) {
            val segmentCount = segmentQuantity!!
            data.set(segmentOffsetKey, PersistentDataType.INTEGER, segmentOffset)
            data.set(segmentQuantityKey, PersistentDataType.INTEGER, segmentCount)
        }
        meta.displayName(Component.text("研究結晶", NamedTextColor.LIGHT_PURPLE))
        meta.lore(
            listOf(
                Component.text("発行元チーム専用", NamedTextColor.GRAY),
                Component.text("コアGUIから納品して研究ポイントへ変換", NamedTextColor.GRAY),
                Component.text("発行バッチ: $batchId", NamedTextColor.DARK_GRAY),
            )
        )
        item.itemMeta = meta
        item.amount = 1
        return item
    }

    fun read(item: ItemStack?): Optional<ResearchCrystalItemIdentity> {
        if (hasRedemptionReceipt(item)) {
            return Optional.empty()
        }
        return readIdentity(item)
    }

    /** Reads the crystal identity while a durable physical redemption receipt is attached. */
    fun readWithRedemptionReceipt(item: ItemStack?): Optional<ResearchCrystalItemIdentity> =
        readIdentity(item)

    /** Adds the redemption operation id before the database apply phase. */
    fun tagRedemption(item: ItemStack, operationId: UUID) {
        Objects.requireNonNull(item, "item")
        Objects.requireNonNull(operationId, "operationId")
        val meta = requireMeta(item)
        meta.persistentDataContainer.set(
            redemptionOperationKey,
            PersistentDataType.STRING,
            operationId.toString(),
        )
        item.itemMeta = meta
    }

    fun hasRedemptionReceipt(item: ItemStack?): Boolean {
        if (item == null) {
            return false
        }
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.keys.contains(redemptionOperationKey)
    }

    fun redemptionOperationId(item: ItemStack?): Optional<UUID> {
        if (item == null) {
            return Optional.empty()
        }
        val meta = item.itemMeta ?: return Optional.empty()
        val value = meta.persistentDataContainer.get(
            redemptionOperationKey,
            PersistentDataType.STRING,
        ) ?: return Optional.empty()
        return try {
            Optional.of(UUID.fromString(value))
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    /** Removes a receipt after its operation was rolled back or physically consumed. */
    fun clearRedemptionReceipt(item: ItemStack?) {
        if (item == null) {
            return
        }
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.remove(redemptionOperationKey)
        item.itemMeta = meta
    }

    private fun readIdentity(item: ItemStack?): Optional<ResearchCrystalItemIdentity> {
        if (item == null || item.type != Material.AMETHYST_SHARD || item.amount <= 0) {
            return Optional.empty()
        }
        val meta = item.itemMeta ?: return Optional.empty()
        val data = meta.persistentDataContainer
        val marker: Byte? = data.get(markerKey, PersistentDataType.BYTE)
        val version: Int? = data.get(versionKey, PersistentDataType.INTEGER)
        val batchId: String? = data.get(batchIdKey, PersistentDataType.STRING)
        val teamId: String? = data.get(teamIdKey, PersistentDataType.STRING)
        val issuedQuantity: Int? = data.get(issuedQuantityKey, PersistentDataType.INTEGER)
        val segmentOffset: Int? = data.get(segmentOffsetKey, PersistentDataType.INTEGER)
        val segmentQuantity: Int? = data.get(segmentQuantityKey, PersistentDataType.INTEGER)
        if (marker == null || marker != 1.toByte() ||
            version == null || version != ITEM_VERSION ||
            batchId == null || teamId == null || issuedQuantity == null || issuedQuantity <= 0
        ) {
            return Optional.empty()
        }
        if ((segmentOffset == null) != (segmentQuantity == null) ||
            (segmentOffset != null &&
                (segmentOffset < 0 ||
                    segmentQuantity!! <= 0 ||
                    segmentQuantity > STACK_LIMIT ||
                    segmentOffset.toLong() + segmentQuantity > issuedQuantity))
        ) {
            return Optional.empty()
        }
        return try {
            Optional.of(
                ResearchCrystalItemIdentity(
                    UUID.fromString(batchId),
                    UUID.fromString(teamId),
                    issuedQuantity,
                    segmentOffset,
                    segmentQuantity,
                )
            )
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    private fun requireMeta(item: ItemStack): ItemMeta =
        Objects.requireNonNull(item.itemMeta, "research crystal metadata")

    companion object {
        const val ITEM_VERSION: Int = 1
        const val STACK_LIMIT: Int = 64
    }
}
