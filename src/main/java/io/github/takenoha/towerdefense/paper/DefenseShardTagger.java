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

/** Creates and validates the plugin-owned "防衛の欠片" repair material. */
public final class DefenseShardTagger {
    public static final int ITEM_VERSION = 1;

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey itemIdKey;

    public DefenseShardTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        markerKey = new NamespacedKey(plugin, "defense_shard");
        versionKey = new NamespacedKey(plugin, "defense_shard_version");
        itemIdKey = new NamespacedKey(plugin, "defense_shard_id");
    }

    public ItemStack create(UUID itemId, int amount) {
        Objects.requireNonNull(itemId, "itemId");
        if (amount <= 0 || amount > 64) {
            throw new IllegalArgumentException("amount must be between 1 and 64");
        }
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD, amount);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "shard metadata");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        data.set(itemIdKey, PersistentDataType.STRING, itemId.toString());
        meta.displayName(net.kyori.adventure.text.Component.text(
                "防衛の欠片", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text(
                        "コアの修繕と将来の強化に使用します",
                        net.kyori.adventure.text.format.NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    public boolean isShard(ItemStack item) {
        if (item == null || item.getType() != Material.PRISMARINE_SHARD || item.getAmount() <= 0) {
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
