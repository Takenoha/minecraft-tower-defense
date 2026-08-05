package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.ResourceType;
import io.github.takenoha.towerdefense.persistence.ResourceVoucher;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Creates canonical, non-stackable voucher items and owns their receipt PDC keys. */
public final class ResourceVoucherTagger {
    private static final int VERSION = 1;

    private final NamespacedKey versionKey;
    private final NamespacedKey voucherIdKey;
    private final NamespacedKey teamIdKey;
    private final NamespacedKey resourceTypeKey;
    private final NamespacedKey quantityKey;
    private final NamespacedKey deliveryOperationKey;
    private final NamespacedKey redeemOperationKey;

    public ResourceVoucherTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        versionKey = new NamespacedKey(plugin, "resource_voucher_version");
        voucherIdKey = new NamespacedKey(plugin, "resource_voucher_id");
        teamIdKey = new NamespacedKey(plugin, "resource_voucher_team_id");
        resourceTypeKey = new NamespacedKey(plugin, "resource_voucher_type");
        quantityKey = new NamespacedKey(plugin, "resource_voucher_quantity");
        deliveryOperationKey = new NamespacedKey(plugin, "resource_voucher_delivery_operation");
        redeemOperationKey = new NamespacedKey(plugin, "resource_voucher_redeem_operation");
    }

    public ItemStack create(ResourceVoucher voucher) {
        Objects.requireNonNull(voucher, "voucher");
        ItemStack item = new ItemStack(Material.PRISMARINE_CRYSTALS, 1);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "voucher item metadata");
        meta.setMaxStackSize(1);
        meta.displayName(Component.text("携帯ポイント証票", NamedTextColor.LIGHT_PURPLE));
        meta.lore(java.util.List.of(
                Component.text("資源: " + voucher.resourceType().displayName(), NamedTextColor.GRAY),
                Component.text("数量: " + voucher.quantity() + "P", NamedTextColor.GRAY),
                Component.text("発行元チーム: " + voucher.teamId(), NamedTextColor.GRAY),
                Component.text("同じチームのコアへ預け入れできます。", NamedTextColor.YELLOW)));
        writeCanonical(meta.getPersistentDataContainer(), voucher);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack tagDelivery(ItemStack item, UUID operationId) {
        return tagReceipt(item, operationId, deliveryOperationKey, redeemOperationKey);
    }

    public ItemStack tagRedeem(ItemStack item, UUID operationId) {
        return tagReceipt(item, operationId, redeemOperationKey, deliveryOperationKey);
    }

    public ItemStack stripReceipts(ItemStack item) {
        Objects.requireNonNull(item, "item");
        ItemStack stripped = item.clone();
        ItemMeta meta = stripped.getItemMeta();
        if (meta == null) {
            return stripped;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.remove(deliveryOperationKey);
        data.remove(redeemOperationKey);
        stripped.setItemMeta(meta);
        return stripped;
    }

    /** Strips only the matching redeem receipt, leaving any unrelated receipt untouched. */
    public ItemStack stripRedeemReceipt(ItemStack item, UUID operationId) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(operationId, "operationId");
        ItemStack stripped = item.clone();
        ItemMeta meta = stripped.getItemMeta();
        if (meta == null) {
            return stripped;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String persistedOperation = data.get(redeemOperationKey, PersistentDataType.STRING);
        if (operationId.toString().equals(persistedOperation)) {
            data.remove(redeemOperationKey);
            stripped.setItemMeta(meta);
        }
        return stripped;
    }

    public Optional<ResourceVoucherItemData> read(ItemStack item) {
        if (item == null || item.getType() != Material.PRISMARINE_CRYSTALS) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String voucherId = data.get(voucherIdKey, PersistentDataType.STRING);
        String teamId = data.get(teamIdKey, PersistentDataType.STRING);
        String type = data.get(resourceTypeKey, PersistentDataType.STRING);
        Long quantity = data.get(quantityKey, PersistentDataType.LONG);
        if (version == null || version != VERSION || voucherId == null || teamId == null
                || type == null || quantity == null || quantity <= 0L) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResourceVoucherItemData(
                    UUID.fromString(voucherId),
                    UUID.fromString(teamId),
                    ResourceType.valueOf(type),
                    quantity,
                    optionalUuid(data.get(deliveryOperationKey, PersistentDataType.STRING)),
                    optionalUuid(data.get(redeemOperationKey, PersistentDataType.STRING))));
        } catch (IllegalArgumentException invalidPdc) {
            return Optional.empty();
        }
    }

    public boolean isVoucher(ItemStack item) {
        return read(item).isPresent();
    }

    public boolean isFor(ItemStack item, UUID voucherId) {
        return read(item).map(value -> value.voucherId().equals(voucherId)).orElse(false);
    }

    public boolean isDeliveryReceipt(ItemStack item) {
        return read(item).map(value -> value.deliveryOperationId().isPresent()).orElse(false);
    }

    public boolean isRedeemReceipt(ItemStack item) {
        return read(item).map(value -> value.redeemOperationId().isPresent()).orElse(false);
    }

    public boolean matchesCanonical(ItemStack item, ResourceVoucher voucher) {
        if (item == null || item.getAmount() != 1 || item.getMaxStackSize() != 1) {
            return false;
        }
        return read(item).map(value -> value.voucherId().equals(voucher.voucherId())
                && value.teamId().equals(voucher.teamId())
                && value.resourceType() == voucher.resourceType()
                && value.quantity() == voucher.quantity()).orElse(false);
    }

    private ItemStack tagReceipt(
            ItemStack item,
            UUID operationId,
            NamespacedKey targetKey,
            NamespacedKey otherKey) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(operationId, "operationId");
        ItemStack tagged = item.clone();
        tagged.setAmount(1);
        ItemMeta meta = Objects.requireNonNull(tagged.getItemMeta(), "voucher metadata");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.remove(otherKey);
        data.set(targetKey, PersistentDataType.STRING, operationId.toString());
        tagged.setItemMeta(meta);
        return tagged;
    }

    private void writeCanonical(PersistentDataContainer data, ResourceVoucher voucher) {
        data.set(versionKey, PersistentDataType.INTEGER, VERSION);
        data.set(voucherIdKey, PersistentDataType.STRING, voucher.voucherId().toString());
        data.set(teamIdKey, PersistentDataType.STRING, voucher.teamId().toString());
        data.set(resourceTypeKey, PersistentDataType.STRING, voucher.resourceType().name());
        data.set(quantityKey, PersistentDataType.LONG, voucher.quantity());
    }

    private static Optional<UUID> optionalUuid(String value) {
        return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
    }
}
