package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Creates and validates the plugin-owned individual-tower enhancement core. */
public final class EnhancementCoreTagger {
    public static final int ITEM_VERSION = 1;

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey itemIdKey;

    public EnhancementCoreTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        markerKey = new NamespacedKey(plugin, "enhancement_core");
        versionKey = new NamespacedKey(plugin, "enhancement_core_version");
        itemIdKey = new NamespacedKey(plugin, "enhancement_core_id");
    }

    public ItemStack create(UUID itemId, int amount) {
        Objects.requireNonNull(itemId, "itemId");
        if (amount <= 0 || amount > 64) {
            throw new IllegalArgumentException("amount must be between 1 and 64");
        }
        ItemStack item = new ItemStack(Material.NETHER_STAR, amount);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "enhancement core metadata");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        data.set(itemIdKey, PersistentDataType.STRING, itemId.toString());
        meta.displayName(net.kyori.adventure.text.Component.text(
                "強化コア", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text(
                        "タワーの個体Lv強化に使用します",
                        net.kyori.adventure.text.format.NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    public boolean isEnhancementCore(ItemStack item) {
        if (item == null || item.getType() != Material.NETHER_STAR || item.getAmount() <= 0) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String itemId = data.get(itemIdKey, PersistentDataType.STRING);
        if (marker == null || marker != 1 || version == null || version != ITEM_VERSION
                || itemId == null) {
            return false;
        }
        try {
            UUID.fromString(itemId);
            return true;
        } catch (IllegalArgumentException invalidId) {
            return false;
        }
    }
}
