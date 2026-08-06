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

/** Marks physical legacy tower-upgrade materials until their database receipt is cleared. */
public final class TowerUpgradeReceiptTagger {
    private final NamespacedKey operationIdKey;
    private final NamespacedKey materialKey;

    public TowerUpgradeReceiptTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        operationIdKey = new NamespacedKey(plugin, "tower_upgrade_receipt");
        materialKey = new NamespacedKey(plugin, "tower_upgrade_receipt_material");
    }

    public ItemStack tag(ItemStack item, UUID operationId, String material) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(material, "material");
        ItemStack tagged = item.clone();
        ItemMeta meta = tagged.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("The tower receipt item has no metadata holder");
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(operationIdKey, PersistentDataType.STRING, operationId.toString());
        data.set(materialKey, PersistentDataType.STRING, material);
        tagged.setItemMeta(meta);
        return tagged;
    }

    public ItemStack strip(ItemStack item) {
        ItemStack stripped = item.clone();
        ItemMeta meta = stripped.getItemMeta();
        if (meta != null) {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.remove(operationIdKey);
            data.remove(materialKey);
            stripped.setItemMeta(meta);
        }
        return stripped;
    }

    public Optional<UUID> operationId(ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return Optional.empty();
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(
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

    public Optional<String> material(ItemStack item) {
        if (item == null || item.getItemMeta() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(
                materialKey, PersistentDataType.STRING));
    }

    public boolean isTagged(ItemStack item) {
        return operationId(item).isPresent() && material(item).isPresent();
    }

    public boolean isFor(ItemStack item, UUID operationId, String material) {
        return operationId(item).filter(operationId::equals).isPresent()
                && material(item).filter(material::equals).isPresent();
    }
}
