package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Adds and validates the unique identity carried by an uninstalled tower item. */
public final class TowerItemTagger {
    public static final int ITEM_VERSION = 1;

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey towerIdKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey targetPriorityKey;

    public TowerItemTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        markerKey = new NamespacedKey(plugin, "tower_item");
        versionKey = new NamespacedKey(plugin, "tower_item_version");
        towerIdKey = new NamespacedKey(plugin, "tower_id");
        typeKey = new NamespacedKey(plugin, "tower_type");
        levelKey = new NamespacedKey(plugin, "tower_level");
        targetPriorityKey = new NamespacedKey(plugin, "tower_target_priority");
    }

    /** Template used by the first Arrow tower recipe; a craft event fills its UUID. */
    public ItemStack recipeTemplate() {
        return recipeTemplate(TowerType.ARROW);
    }

    /** Creates a recipe result template for the supplied tower type. */
    public ItemStack recipeTemplate(TowerType type) {
        Objects.requireNonNull(type, "type");
        ItemStack item = new ItemStack(materialFor(type));
        ItemMeta meta = requireMeta(item);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        data.set(typeKey, PersistentDataType.STRING, type.id());
        data.set(levelKey, PersistentDataType.INTEGER, 1);
        data.set(
                targetPriorityKey,
                PersistentDataType.STRING,
                TowerTargetPriority.CORE_NEAREST.id());
        setDisplay(meta, type, 1, TowerTargetPriority.CORE_NEAREST);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack create(UUID towerId, TowerType type, int individualLevel) {
        return create(towerId, type, individualLevel, TowerTargetPriority.CORE_NEAREST);
    }

    public ItemStack create(
            UUID towerId,
            TowerType type,
            int individualLevel,
            TowerTargetPriority targetPriority) {
        Objects.requireNonNull(towerId, "towerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetPriority, "targetPriority");
        if (individualLevel <= 0) {
            throw new IllegalArgumentException("individualLevel must be positive");
        }
        ItemStack item = new ItemStack(materialFor(type));
        ItemMeta meta = requireMeta(item);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        data.set(towerIdKey, PersistentDataType.STRING, towerId.toString());
        data.set(typeKey, PersistentDataType.STRING, type.id());
        data.set(levelKey, PersistentDataType.INTEGER, individualLevel);
        data.set(targetPriorityKey, PersistentDataType.STRING, targetPriority.id());
        setDisplay(meta, type, individualLevel, targetPriority);
        item.setItemMeta(meta);
        item.setAmount(1);
        return item;
    }

    public Optional<TowerItemIdentity> read(ItemStack item) {
        if (item == null || item.getAmount() != 1) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        Optional<TowerType> type = readType(meta.getPersistentDataContainer());
        return type.isPresent() && item.getType() == materialFor(type.orElseThrow())
                ? read(meta.getPersistentDataContainer())
                : Optional.empty();
    }

    public boolean isRecipeTemplate(ItemStack item) {
        return recipeType(item).isPresent();
    }

    /** Returns the type encoded in a recipe template, if it is still a valid template. */
    public Optional<TowerType> recipeType(ItemStack item) {
        if (item == null || item.getAmount() != 1) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.get(markerKey, PersistentDataType.BYTE) == null
                || data.get(markerKey, PersistentDataType.BYTE) != 1
                || data.get(versionKey, PersistentDataType.INTEGER) == null
                || data.get(versionKey, PersistentDataType.INTEGER) != ITEM_VERSION
                || data.get(towerIdKey, PersistentDataType.STRING) != null
                || data.get(levelKey, PersistentDataType.INTEGER) == null) {
            return Optional.empty();
        }
        Optional<TowerType> type = readType(data);
        return type.isPresent() && item.getType() == materialFor(type.orElseThrow())
                ? type
                : Optional.empty();
    }

    public boolean hasTowerId(ItemStack item, UUID towerId) {
        Objects.requireNonNull(towerId, "towerId");
        return read(item).map(identity -> identity.towerId().equals(towerId)).orElse(false);
    }

    private Optional<TowerItemIdentity> read(PersistentDataContainer data) {
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String towerId = data.get(towerIdKey, PersistentDataType.STRING);
        String type = data.get(typeKey, PersistentDataType.STRING);
        Integer level = data.get(levelKey, PersistentDataType.INTEGER);
        if (marker == null || marker != 1 || version == null || version != ITEM_VERSION
                || towerId == null || type == null || level == null || level <= 0) {
            return Optional.empty();
        }
        try {
            String priority = data.get(targetPriorityKey, PersistentDataType.STRING);
            return Optional.of(new TowerItemIdentity(
                    UUID.fromString(towerId),
                    TowerType.fromId(type),
                    level,
                    priority == null
                            ? TowerTargetPriority.CORE_NEAREST
                            : TowerTargetPriority.fromId(priority)));
        } catch (IllegalArgumentException invalidIdentity) {
            return Optional.empty();
        }
    }

    private Optional<TowerType> readType(PersistentDataContainer data) {
        String type = data.get(typeKey, PersistentDataType.STRING);
        if (type == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TowerType.fromId(type));
        } catch (IllegalArgumentException invalidType) {
            return Optional.empty();
        }
    }

    public static Material materialFor(TowerType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case ARROW -> Material.BOW;
            case CANNON -> Material.DISPENSER;
            case FROST -> Material.PACKED_ICE;
            case LIGHTNING -> Material.LIGHTNING_ROD;
            case SUPPORT -> Material.AMETHYST_BLOCK;
            case SNIPER -> Material.CROSSBOW;
            case FLAME -> Material.BLAZE_ROD;
        };
    }

    private static void setDisplay(
            ItemMeta meta,
            TowerType type,
            int level,
            TowerTargetPriority targetPriority) {
        meta.displayName(Component.text(type.displayName() + "タワー", NamedTextColor.GREEN));
        meta.lore(List.of(
                Component.text("個体Lv" + level, NamedTextColor.GRAY),
                Component.text("対象優先: " + targetPriority.displayName(), NamedTextColor.GRAY),
                Component.text("設置後に自動でイベント敵を攻撃します", NamedTextColor.GRAY),
                Component.text("固有ID付き・1個のみ", NamedTextColor.DARK_GRAY)));
    }

    private static ItemMeta requireMeta(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("The tower item has no metadata holder");
        }
        return meta;
    }
}
