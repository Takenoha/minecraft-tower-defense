package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.ResourceType
import io.github.takenoha.towerdefense.persistence.ResourceVoucher
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

/** Creates canonical, non-stackable voucher items and owns their receipt PDC keys. */
class ResourceVoucherTagger(plugin: Plugin) {
    private val versionKey: NamespacedKey
    private val voucherIdKey: NamespacedKey
    private val teamIdKey: NamespacedKey
    private val resourceTypeKey: NamespacedKey
    private val quantityKey: NamespacedKey
    private val deliveryOperationKey: NamespacedKey
    private val redeemOperationKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        versionKey = NamespacedKey(plugin, "resource_voucher_version")
        voucherIdKey = NamespacedKey(plugin, "resource_voucher_id")
        teamIdKey = NamespacedKey(plugin, "resource_voucher_team_id")
        resourceTypeKey = NamespacedKey(plugin, "resource_voucher_type")
        quantityKey = NamespacedKey(plugin, "resource_voucher_quantity")
        deliveryOperationKey = NamespacedKey(plugin, "resource_voucher_delivery_operation")
        redeemOperationKey = NamespacedKey(plugin, "resource_voucher_redeem_operation")
    }

    fun create(voucher: ResourceVoucher): ItemStack {
        Objects.requireNonNull(voucher, "voucher")
        val item = ItemStack(Material.PRISMARINE_CRYSTALS, 1)
        val meta = Objects.requireNonNull(item.itemMeta, "voucher item metadata")
        meta.setMaxStackSize(1)
        meta.displayName(Component.text("携帯ポイント証票", NamedTextColor.LIGHT_PURPLE))
        meta.lore(
            listOf(
                Component.text("資源: ${voucher.resourceType().displayName()}", NamedTextColor.GRAY),
                Component.text("数量: ${voucher.quantity()}P", NamedTextColor.GRAY),
                Component.text("発行元チーム: ${voucher.teamId()}", NamedTextColor.GRAY),
                Component.text("同じチームのコアへ預け入れできます。", NamedTextColor.YELLOW),
            )
        )
        writeCanonical(meta.persistentDataContainer, voucher)
        item.itemMeta = meta
        return item
    }

    fun tagDelivery(item: ItemStack, operationId: UUID): ItemStack =
        tagReceipt(item, operationId, deliveryOperationKey, redeemOperationKey)

    fun tagRedeem(item: ItemStack, operationId: UUID): ItemStack =
        tagReceipt(item, operationId, redeemOperationKey, deliveryOperationKey)

    fun stripReceipts(item: ItemStack): ItemStack {
        Objects.requireNonNull(item, "item")
        val stripped = item.clone()
        val meta = stripped.itemMeta ?: return stripped
        val data = meta.persistentDataContainer
        data.remove(deliveryOperationKey)
        data.remove(redeemOperationKey)
        stripped.itemMeta = meta
        return stripped
    }

    /** Strips only the matching redeem receipt, leaving any unrelated receipt untouched. */
    fun stripRedeemReceipt(item: ItemStack, operationId: UUID): ItemStack {
        Objects.requireNonNull(item, "item")
        Objects.requireNonNull(operationId, "operationId")
        val stripped = item.clone()
        val meta = stripped.itemMeta ?: return stripped
        val data = meta.persistentDataContainer
        val persistedOperation = data.get(redeemOperationKey, PersistentDataType.STRING)
        if (operationId.toString() == persistedOperation) {
            data.remove(redeemOperationKey)
            stripped.itemMeta = meta
        }
        return stripped
    }

    fun read(item: ItemStack?): Optional<ResourceVoucherItemData> {
        if (item == null || item.type != Material.PRISMARINE_CRYSTALS) {
            return Optional.empty()
        }
        val meta = item.itemMeta ?: return Optional.empty()
        val data = meta.persistentDataContainer
        val version: Int? = data.get(versionKey, PersistentDataType.INTEGER)
        val voucherId: String? = data.get(voucherIdKey, PersistentDataType.STRING)
        val teamId: String? = data.get(teamIdKey, PersistentDataType.STRING)
        val type: String? = data.get(resourceTypeKey, PersistentDataType.STRING)
        val quantity: Long? = data.get(quantityKey, PersistentDataType.LONG)
        if (version == null || version != VERSION || voucherId == null || teamId == null ||
            type == null || quantity == null || quantity <= 0L
        ) {
            return Optional.empty()
        }
        return try {
            Optional.of(
                ResourceVoucherItemData(
                    UUID.fromString(voucherId),
                    UUID.fromString(teamId),
                    ResourceType.valueOf(type),
                    quantity,
                    optionalUuid(data.get(deliveryOperationKey, PersistentDataType.STRING)),
                    optionalUuid(data.get(redeemOperationKey, PersistentDataType.STRING)),
                )
            )
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    fun isVoucher(item: ItemStack?): Boolean = read(item).isPresent

    fun isFor(item: ItemStack?, voucherId: UUID?): Boolean =
        read(item).map { value -> value.voucherId == voucherId }.orElse(false)

    fun isDeliveryReceipt(item: ItemStack?): Boolean =
        read(item).map { value -> value.deliveryOperationId.isPresent }.orElse(false)

    fun isRedeemReceipt(item: ItemStack?): Boolean =
        read(item).map { value -> value.redeemOperationId.isPresent }.orElse(false)

    fun matchesCanonical(item: ItemStack?, voucher: ResourceVoucher?): Boolean {
        if (item == null || item.amount != 1 || item.maxStackSize != 1) {
            return false
        }
        return read(item).map { value ->
            value.voucherId == voucher!!.voucherId() &&
                value.teamId == voucher.teamId() &&
                value.resourceType == voucher.resourceType() &&
                value.quantity == voucher.quantity()
        }.orElse(false)
    }

    private fun tagReceipt(
        item: ItemStack,
        operationId: UUID,
        targetKey: NamespacedKey,
        otherKey: NamespacedKey,
    ): ItemStack {
        Objects.requireNonNull(item, "item")
        Objects.requireNonNull(operationId, "operationId")
        val tagged = item.clone()
        tagged.amount = 1
        val meta = Objects.requireNonNull(tagged.itemMeta, "voucher metadata")
        val data = meta.persistentDataContainer
        data.remove(otherKey)
        data.set(targetKey, PersistentDataType.STRING, operationId.toString())
        tagged.itemMeta = meta
        return tagged
    }

    private fun writeCanonical(data: PersistentDataContainer, voucher: ResourceVoucher) {
        data.set(versionKey, PersistentDataType.INTEGER, VERSION)
        data.set(voucherIdKey, PersistentDataType.STRING, voucher.voucherId().toString())
        data.set(teamIdKey, PersistentDataType.STRING, voucher.teamId().toString())
        data.set(resourceTypeKey, PersistentDataType.STRING, voucher.resourceType().name)
        data.set(quantityKey, PersistentDataType.LONG, voucher.quantity())
    }

    private fun optionalUuid(value: String?): Optional<UUID> =
        if (value == null) Optional.empty() else Optional.of(UUID.fromString(value))

    companion object {
        private const val VERSION: Int = 1
    }
}
