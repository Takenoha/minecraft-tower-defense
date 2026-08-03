package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Adds and removes the short-lived receipt used to reconcile an inventory/database stop window. */
public final class RewardQueueReceiptTagger {
    private final NamespacedKey queueIdKey;
    private final NamespacedKey operationIdKey;

    public RewardQueueReceiptTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        queueIdKey = new NamespacedKey(plugin, "reward_queue_id");
        operationIdKey = new NamespacedKey(plugin, "reward_delivery_operation_id");
    }

    public ItemStack tag(ItemStack itemStack, RewardQueueReceipt receipt) {
        Objects.requireNonNull(itemStack, "itemStack");
        Objects.requireNonNull(receipt, "receipt");
        ItemStack tagged = itemStack.clone();
        ItemMeta meta = tagged.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("The reward item has no metadata holder");
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(queueIdKey, PersistentDataType.STRING, receipt.queueId().toString());
        data.set(operationIdKey, PersistentDataType.STRING, receipt.operationId().toString());
        tagged.setItemMeta(meta);
        return tagged;
    }

    /** Removes only this delivery system's receipt metadata. */
    public ItemStack strip(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        ItemStack stripped = itemStack.clone();
        ItemMeta meta = stripped.getItemMeta();
        if (meta == null) {
            return stripped;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.remove(queueIdKey);
        data.remove(operationIdKey);
        stripped.setItemMeta(meta);
        return stripped;
    }

    public Optional<RewardQueueReceipt> read(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        ItemMeta meta = itemStack.getItemMeta();
        return meta == null ? Optional.empty() : read(meta.getPersistentDataContainer());
    }

    public boolean isTagged(ItemStack itemStack) {
        return itemStack != null && read(itemStack).isPresent();
    }

    private Optional<RewardQueueReceipt> read(PersistentDataContainer data) {
        String queueId = data.get(queueIdKey, PersistentDataType.STRING);
        String operationId = data.get(operationIdKey, PersistentDataType.STRING);
        if (queueId == null || operationId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RewardQueueReceipt(
                    UUID.fromString(queueId), UUID.fromString(operationId)));
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
    }
}
