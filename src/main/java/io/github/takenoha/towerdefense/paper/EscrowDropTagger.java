package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.EscrowDrop;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Reads and writes escrow identity on both the display entity and its ItemStack. */
public final class EscrowDropTagger {
    private final NamespacedKey eventIdKey;
    private final NamespacedKey dropIdKey;

    public EscrowDropTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        eventIdKey = new NamespacedKey(plugin, "escrow_event_id");
        dropIdKey = new NamespacedKey(plugin, "escrow_drop_id");
    }

    public void tag(Item item, EscrowDrop drop) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(drop, "drop");
        tag(item, new TaggedEscrowDrop(drop.eventId(), drop.dropId()));
        item.setItemStack(tag(item.getItemStack(), drop));
    }

    public void tag(Entity entity, TaggedEscrowDrop drop) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(drop, "drop");
        write(entity.getPersistentDataContainer(), drop);
    }

    public ItemStack tag(ItemStack itemStack, EscrowDrop drop) {
        Objects.requireNonNull(itemStack, "itemStack");
        Objects.requireNonNull(drop, "drop");
        ItemStack tagged = itemStack.clone();
        ItemMeta meta = tagged.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("The escrow display item has no metadata holder");
        }
        write(meta.getPersistentDataContainer(), new TaggedEscrowDrop(
                drop.eventId(), drop.dropId()));
        tagged.setItemMeta(meta);
        return tagged;
    }

    public Optional<TaggedEscrowDrop> read(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return read(entity.getPersistentDataContainer());
    }

    public Optional<TaggedEscrowDrop> read(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        ItemMeta meta = itemStack.getItemMeta();
        return meta == null ? Optional.empty() : read(meta.getPersistentDataContainer());
    }

    public Optional<TaggedEscrowDrop> read(Item item) {
        Objects.requireNonNull(item, "item");
        Optional<TaggedEscrowDrop> entityTag = read((Entity) item);
        return entityTag.isPresent() ? entityTag : read(item.getItemStack());
    }

    public boolean isTagged(ItemStack itemStack) {
        return itemStack != null && read(itemStack).isPresent();
    }

    private Optional<TaggedEscrowDrop> read(PersistentDataContainer data) {
        String eventId = data.get(eventIdKey, PersistentDataType.STRING);
        String dropId = data.get(dropIdKey, PersistentDataType.STRING);
        if (eventId == null || dropId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TaggedEscrowDrop(UUID.fromString(eventId), UUID.fromString(dropId)));
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
    }

    private void write(PersistentDataContainer data, TaggedEscrowDrop drop) {
        data.set(eventIdKey, PersistentDataType.STRING, drop.eventId().toString());
        data.set(dropIdKey, PersistentDataType.STRING, drop.dropId().toString());
    }
}
