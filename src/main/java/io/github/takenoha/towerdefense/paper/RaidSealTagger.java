package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.StageWaveSchedule;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
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

/** Creates and validates the non-stackable, database-backed raid-start token. */
public final class RaidSealTagger {
    public static final int ITEM_VERSION = 1;
    public static final long FOUNDATION_STAGE = 1L;
    public static final Material ITEM_MATERIAL = Material.valueOf(
            RaidSealMaterialPolicy.CURRENT_MATERIAL);
    public static final Material LEGACY_ITEM_MATERIAL = Material.valueOf(
            RaidSealMaterialPolicy.LEGACY_MATERIAL);

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey sealIdKey;
    private final NamespacedKey stageLevelKey;

    public RaidSealTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        markerKey = new NamespacedKey(plugin, "raid_seal");
        versionKey = new NamespacedKey(plugin, "raid_seal_version");
        sealIdKey = new NamespacedKey(plugin, "raid_seal_id");
        stageLevelKey = new NamespacedKey(plugin, "raid_seal_stage");
    }

    /** Template used by the stage-1 recipe; a craft event replaces its empty UUID marker. */
    public ItemStack recipeTemplate() {
        return recipeTemplate(FOUNDATION_STAGE);
    }

    /** Template used by a stage-specific recipe before craft-time UUID registration. */
    public ItemStack recipeTemplate(long stageLevel) {
        return createTemplate(stageLevel);
    }

    public ItemStack create(UUID sealId, long stageLevel) {
        Objects.requireNonNull(sealId, "sealId");
        requireStage(stageLevel);
        ItemStack item = createTemplate(stageLevel);
        ItemMeta meta = requireMeta(item);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(sealIdKey, PersistentDataType.STRING, sealId.toString());
        data.set(stageLevelKey, PersistentDataType.LONG, stageLevel);
        meta.displayName(Component.text("襲撃の印", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("ステージ" + stageLevel + "の防衛戦を開始します", NamedTextColor.GRAY),
                Component.text("右クリックでチームのコアへ使用", NamedTextColor.GRAY),
                Component.text("真正性はサーバーの永続データで検証されます", NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
        item.setAmount(1);
        return item;
    }

    public Optional<RaidSealItemIdentity> read(ItemStack item) {
        if (item == null || !isSupportedMaterial(item.getType()) || item.getAmount() != 1) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? Optional.empty() : read(meta.getPersistentDataContainer());
    }

    /** Returns whether a valid seal still uses the pre-UX ENDER_EYE material. */
    public boolean isLegacyMaterial(ItemStack item) {
        return item != null
                && item.getType() == LEGACY_ITEM_MATERIAL
                && read(item).isPresent();
    }

    public boolean isRecipeTemplate(ItemStack item) {
        return templateStage(item).isPresent();
    }

    /** Reads the stage encoded in a registered recipe result. */
    public OptionalLong templateStage(ItemStack item) {
        if (item == null || !isSupportedMaterial(item.getType()) || item.getAmount() != 1) {
            return OptionalLong.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return OptionalLong.empty();
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        if (marker == null
                || marker != 1
                || version == null
                || version != ITEM_VERSION
                || data.get(sealIdKey, PersistentDataType.STRING) != null) {
            return OptionalLong.empty();
        }
        Long stage = data.get(stageLevelKey, PersistentDataType.LONG);
        if (stage == null) {
            return OptionalLong.empty();
        }
        try {
            StageWaveSchedule.requireValidStageLevel(stage);
            return OptionalLong.of(stage);
        } catch (IllegalArgumentException invalidStage) {
            return OptionalLong.empty();
        }
    }

    public boolean hasSealId(ItemStack item, UUID sealId) {
        Objects.requireNonNull(sealId, "sealId");
        return read(item).map(identity -> identity.sealId().equals(sealId)).orElse(false);
    }

    private ItemStack createTemplate(long stageLevel) {
        requireStage(stageLevel);
        ItemStack item = new ItemStack(ITEM_MATERIAL, 1);
        ItemMeta meta = requireMeta(item);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        data.set(stageLevelKey, PersistentDataType.LONG, stageLevel);
        meta.displayName(Component.text("襲撃の印", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("防衛戦を開始するための印", NamedTextColor.GRAY),
                Component.text("ステージ" + stageLevel + "用", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private Optional<RaidSealItemIdentity> read(PersistentDataContainer data) {
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String sealId = data.get(sealIdKey, PersistentDataType.STRING);
        Long stage = data.get(stageLevelKey, PersistentDataType.LONG);
        if (marker == null || marker != 1 || version == null || version != ITEM_VERSION
                || sealId == null || stage == null) {
            return Optional.empty();
        }
        try {
            StageWaveSchedule.requireValidStageLevel(stage);
        } catch (IllegalArgumentException invalidStage) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RaidSealItemIdentity(UUID.fromString(sealId), stage));
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
    }

    private static ItemMeta requireMeta(ItemStack item) {
        return Objects.requireNonNull(item.getItemMeta(), "raid seal metadata");
    }

    private static long requireStage(long stageLevel) {
        return StageWaveSchedule.requireValidStageLevel(stageLevel);
    }

    private static boolean isSupportedMaterial(Material material) {
        return RaidSealMaterialPolicy.supports(material.name());
    }
}
