package io.github.takenoha.towerdefense.paper;

import java.util.List;
import java.util.Locale;
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
    public static final int STACK_LIMIT = 64;

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey batchIdKey;
    private final NamespacedKey teamIdKey;
    private final NamespacedKey issuedQuantityKey;
    private final NamespacedKey segmentOffsetKey;
    private final NamespacedKey segmentQuantityKey;
    private final NamespacedKey redemptionOperationKey;

    public ResearchCrystalTagger(Plugin plugin) {
        this(Objects.requireNonNull(plugin, "plugin").getName());
    }

    ResearchCrystalTagger(String namespace) {
        String normalizedNamespace = Objects.requireNonNull(namespace, "namespace")
                .toLowerCase(Locale.ROOT);
        markerKey = new NamespacedKey(normalizedNamespace, "research_crystal");
        versionKey = new NamespacedKey(normalizedNamespace, "research_crystal_version");
        batchIdKey = new NamespacedKey(normalizedNamespace, "research_crystal_batch_id");
        teamIdKey = new NamespacedKey(normalizedNamespace, "research_crystal_team_id");
        issuedQuantityKey = new NamespacedKey(normalizedNamespace, "research_crystal_issued_quantity");
        segmentOffsetKey = new NamespacedKey(normalizedNamespace, "research_crystal_segment_offset");
        segmentQuantityKey = new NamespacedKey(normalizedNamespace, "research_crystal_segment_quantity");
        redemptionOperationKey = new NamespacedKey(normalizedNamespace, "research_crystal_redemption");
    }

    /** Creates one stack unit; the delivery bridge splits the queue quantity safely. */
    public ItemStack create(UUID batchId, UUID teamId, int issuedQuantity) {
        return create(batchId, teamId, issuedQuantity, null, null);
    }

    /** Creates one deterministic delivery segment within an issuance batch. */
    public ItemStack create(
            UUID batchId,
            UUID teamId,
            int issuedQuantity,
            Integer segmentOffset,
            Integer segmentQuantity) {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(teamId, "teamId");
        if (issuedQuantity <= 0) {
            throw new IllegalArgumentException("issuedQuantity must be positive");
        }
        if ((segmentOffset == null) != (segmentQuantity == null)) {
            throw new IllegalArgumentException(
                    "segmentOffset and segmentQuantity must be supplied together");
        }
        if (segmentOffset != null
                && (segmentOffset < 0
                        || segmentQuantity <= 0
                        || segmentQuantity > STACK_LIMIT
                        || (long) segmentOffset + segmentQuantity > issuedQuantity)) {
            throw new IllegalArgumentException("research crystal segment is invalid");
        }
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "research crystal metadata");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        data.set(batchIdKey, PersistentDataType.STRING, batchId.toString());
        data.set(teamIdKey, PersistentDataType.STRING, teamId.toString());
        data.set(issuedQuantityKey, PersistentDataType.INTEGER, issuedQuantity);
        if (segmentOffset != null) {
            data.set(segmentOffsetKey, PersistentDataType.INTEGER, segmentOffset);
            data.set(segmentQuantityKey, PersistentDataType.INTEGER, segmentQuantity);
        }
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
        if (hasRedemptionReceipt(item)) {
            return Optional.empty();
        }
        return readIdentity(item);
    }

    /** Reads the crystal identity while a durable physical redemption receipt is attached. */
    Optional<ResearchCrystalItemIdentity> readWithRedemptionReceipt(ItemStack item) {
        return readIdentity(item);
    }

    /** Adds the redemption operation id before the database apply phase. */
    void tagRedemption(ItemStack item, UUID operationId) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(operationId, "operationId");
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "research crystal metadata");
        meta.getPersistentDataContainer().set(
                redemptionOperationKey,
                PersistentDataType.STRING,
                operationId.toString());
        item.setItemMeta(meta);
    }

    boolean hasRedemptionReceipt(ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().getKeys().contains(redemptionOperationKey);
    }

    Optional<UUID> redemptionOperationId(ItemStack item) {
        if (item == null) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String value = meta.getPersistentDataContainer().get(
                redemptionOperationKey,
                PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException invalidOperation) {
            return Optional.empty();
        }
    }

    /** Removes a receipt after its operation was rolled back or physically consumed. */
    void clearRedemptionReceipt(ItemStack item) {
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().remove(redemptionOperationKey);
        item.setItemMeta(meta);
    }

    private Optional<ResearchCrystalItemIdentity> readIdentity(ItemStack item) {
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
        Integer segmentOffset = data.get(segmentOffsetKey, PersistentDataType.INTEGER);
        Integer segmentQuantity = data.get(segmentQuantityKey, PersistentDataType.INTEGER);
        if (marker == null || marker != 1 || version == null || version != ITEM_VERSION
                || batchId == null || teamId == null || issuedQuantity == null
                || issuedQuantity <= 0) {
            return Optional.empty();
        }
        if ((segmentOffset == null) != (segmentQuantity == null)
                || (segmentOffset != null
                        && (segmentOffset < 0
                                || segmentQuantity <= 0
                                || segmentQuantity > STACK_LIMIT
                                || (long) segmentOffset + segmentQuantity > issuedQuantity))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResearchCrystalItemIdentity(
                    UUID.fromString(batchId),
                    UUID.fromString(teamId),
                    issuedQuantity,
                    segmentOffset,
                    segmentQuantity));
        } catch (IllegalArgumentException invalidIdentity) {
            return Optional.empty();
        }
    }
}
