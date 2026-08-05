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

/** Marks vanilla repair material while its database receipt is unresolved. */
public final class CoreRepairReceiptTagger {
    private final NamespacedKey operationIdKey;

    public CoreRepairReceiptTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        operationIdKey = new NamespacedKey(plugin, "core_repair_receipt");
    }

    public ItemStack tag(ItemStack itemStack, UUID operationId) {
        Objects.requireNonNull(itemStack, "itemStack");
        Objects.requireNonNull(operationId, "operationId");
        ItemStack tagged = itemStack.clone();
        ItemMeta meta = tagged.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("The repair receipt item has no metadata holder");
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(operationIdKey, PersistentDataType.STRING, operationId.toString());
        tagged.setItemMeta(meta);
        return tagged;
    }

    public ItemStack strip(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        ItemStack stripped = itemStack.clone();
        ItemMeta meta = stripped.getItemMeta();
        if (meta == null) {
            return stripped;
        }
        meta.getPersistentDataContainer().remove(operationIdKey);
        stripped.setItemMeta(meta);
        return stripped;
    }

    public Optional<UUID> read(ItemStack itemStack) {
        if (itemStack == null) {
            return Optional.empty();
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String value = meta.getPersistentDataContainer().get(
                operationIdKey, PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
    }

    public boolean isTagged(ItemStack itemStack) {
        return read(itemStack).isPresent();
    }

    public boolean isFor(ItemStack itemStack, UUID operationId) {
        return read(itemStack).filter(operationId::equals).isPresent();
    }
}
