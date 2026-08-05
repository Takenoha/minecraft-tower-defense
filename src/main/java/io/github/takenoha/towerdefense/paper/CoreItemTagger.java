package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Adds and validates the stable identity carried by public core items. */
public final class CoreItemTagger {
    public static final int ITEM_VERSION = 1;

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey teamIdKey;
    private final NamespacedKey coreIdKey;

    public CoreItemTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        markerKey = new NamespacedKey(plugin, "core_item");
        versionKey = new NamespacedKey(plugin, "core_item_version");
        itemIdKey = new NamespacedKey(plugin, "core_item_id");
        teamIdKey = new NamespacedKey(plugin, "core_team_id");
        coreIdKey = new NamespacedKey(plugin, "core_id");
    }

    /** Returns the recipe result template. A craft event replaces its temporary marker with a UUID. */
    public ItemStack recipeTemplate() {
        ItemStack item = new ItemStack(CoreMaterialPolicy.CURRENT_ITEM);
        ItemMeta meta = requireMeta(item);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        meta.displayName(Component.text("コア", NamedTextColor.AQUA));
        meta.lore(java.util.List.of(
                Component.text("右クリックした固体ブロックをコアへ置換します", NamedTextColor.GRAY),
                Component.text("設置者がチームオーナーになります", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createUnbound(UUID itemId) {
        return create(itemId, Optional.empty(), Optional.empty());
    }

    public ItemStack createBound(UUID itemId, UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return create(itemId, Optional.of(teamId), Optional.empty());
    }

    public ItemStack createBound(UUID itemId, UUID teamId, UUID coreId) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(coreId, "coreId");
        return create(itemId, Optional.of(teamId), Optional.of(coreId));
    }

    public Optional<CoreItemIdentity> read(ItemStack item) {
        if (item == null
                || !CoreMaterialPolicy.isCoreItemMaterial(item.getType())
                || item.getAmount() != 1) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        return read(meta.getPersistentDataContainer());
    }

    public boolean isRecipeTemplate(ItemStack item) {
        if (item == null || !CoreMaterialPolicy.isCoreItemMaterial(item.getType())) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        return data.get(markerKey, PersistentDataType.BYTE) != null
                && version != null
                && version == ITEM_VERSION
                && data.get(itemIdKey, PersistentDataType.STRING) == null;
    }

    public boolean hasItemId(ItemStack item, UUID itemId) {
        return read(item).map(identity -> identity.itemId().equals(itemId)).orElse(false);
    }

    private ItemStack create(
            UUID itemId,
            Optional<UUID> teamId,
            Optional<UUID> coreId) {
        Objects.requireNonNull(itemId, "itemId");
        ItemStack item = recipeTemplate();
        ItemMeta meta = requireMeta(item);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(itemIdKey, PersistentDataType.STRING, itemId.toString());
        teamId.ifPresent(value -> data.set(teamIdKey, PersistentDataType.STRING, value.toString()));
        coreId.ifPresent(value -> data.set(coreIdKey, PersistentDataType.STRING, value.toString()));
        if (teamId.isPresent()) {
            meta.displayName(Component.text("移設用コア", NamedTextColor.AQUA));
            meta.lore(java.util.List.of(
                    Component.text("同じチームのコアを別の位置へ移設します", NamedTextColor.GRAY),
                    Component.text("防衛戦外・コアHP満タン時のみ使用できます", NamedTextColor.GRAY)));
        }
        item.setItemMeta(meta);
        item.setAmount(1);
        return item;
    }

    private Optional<CoreItemIdentity> read(PersistentDataContainer data) {
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String itemId = data.get(itemIdKey, PersistentDataType.STRING);
        if (marker == null || marker != 1 || version == null || version != ITEM_VERSION
                || itemId == null) {
            return Optional.empty();
        }
        try {
            String teamId = data.get(teamIdKey, PersistentDataType.STRING);
            String coreId = data.get(coreIdKey, PersistentDataType.STRING);
            return Optional.of(new CoreItemIdentity(
                    UUID.fromString(itemId),
                    teamId == null ? Optional.empty() : Optional.of(UUID.fromString(teamId)),
                    coreId == null ? Optional.empty() : Optional.of(UUID.fromString(coreId))));
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
    }

    private static ItemMeta requireMeta(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("The core item has no metadata holder");
        }
        return meta;
    }
}
