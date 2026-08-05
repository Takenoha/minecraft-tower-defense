package io.github.takenoha.towerdefense.paper;

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

/** Creates and validates the team-bound research crystal used by the core deposit flow. */
public final class ResearchCrystalTagger {
    public static final int ITEM_VERSION = 1;

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey batchIdKey;
    private final NamespacedKey teamIdKey;
    private final NamespacedKey issuedQuantityKey;

    public ResearchCrystalTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        markerKey = new NamespacedKey(plugin, "research_crystal");
        versionKey = new NamespacedKey(plugin, "research_crystal_version");
        batchIdKey = new NamespacedKey(plugin, "research_crystal_batch_id");
        teamIdKey = new NamespacedKey(plugin, "research_crystal_team_id");
        issuedQuantityKey = new NamespacedKey(plugin, "research_crystal_issued_quantity");
    }

    /** Creates one stack unit; the delivery bridge splits the queue quantity safely. */
    public ItemStack create(UUID batchId, UUID teamId, int issuedQuantity) {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(teamId, "teamId");
        if (issuedQuantity <= 0) {
            throw new IllegalArgumentException("issuedQuantity must be positive");
        }
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "research crystal metadata");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        data.set(batchIdKey, PersistentDataType.STRING, batchId.toString());
        data.set(teamIdKey, PersistentDataType.STRING, teamId.toString());
        data.set(issuedQuantityKey, PersistentDataType.INTEGER, issuedQuantity);
        meta.displayName(Component.text("研究結晶", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(
                Component.text("発行元チーム専用", NamedTextColor.GRAY),
                Component.text("コアGUIから納品して研究ポイントへ変換", NamedTextColor.GRAY),
                Component.text("発行バッチ: " + batchId, NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
        item.setAmount(1);
        return item;
    }

    public Optional<ResearchCrystalItemIdentity> read(ItemStack item) {
        if (item == null || item.getType() != Material.AMETHYST_SHARD || item.getAmount() <= 0) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String batchId = data.get(batchIdKey, PersistentDataType.STRING);
        String teamId = data.get(teamIdKey, PersistentDataType.STRING);
        Integer issuedQuantity = data.get(issuedQuantityKey, PersistentDataType.INTEGER);
        if (marker == null || marker != 1 || version == null || version != ITEM_VERSION
                || batchId == null || teamId == null || issuedQuantity == null
                || issuedQuantity <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResearchCrystalItemIdentity(
                    UUID.fromString(batchId), UUID.fromString(teamId), issuedQuantity));
        } catch (IllegalArgumentException invalidIdentity) {
            return Optional.empty();
        }
    }
}
