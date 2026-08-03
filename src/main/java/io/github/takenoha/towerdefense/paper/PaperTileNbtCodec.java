package io.github.takenoha.towerdefense.paper;

import io.papermc.paper.block.TileStateInventoryHolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.bukkit.Nameable;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lockable;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;

/**
 * Captures the mutable tile data exposed by the Paper API in a deterministic payload.
 *
 * <p>Paper deliberately does not expose the server's internal raw NBT compound. The codec uses
 * the stable API projection instead: the tile's persistent-data bytes, snapshot inventory bytes,
 * lock, and custom name. The payload is versioned so a future Paper adapter can add fields without
 * making old WAL rows ambiguous.</p>
 */
@SuppressWarnings("deprecation")
public final class PaperTileNbtCodec {
    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";
    private static final int FIELD_COUNT = 5;

    private PaperTileNbtCodec() {
    }

    /** Returns an empty payload for an ordinary block and a versioned payload for a tile state. */
    public static String capture(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (!(state instanceof TileState tile)) {
            return "";
        }
        return VERSION
                + SEPARATOR + encodeBytes(serializePersistentData(tile))
                + SEPARATOR + encodeInventory(state)
                + SEPARATOR + encodeNullableString(state instanceof Lockable lockable
                        ? lockable.getLock()
                        : null)
                + SEPARATOR + encodeNullableString(state instanceof Nameable nameable
                        ? nameable.getCustomName()
                        : null);
    }

    /** Applies a previously captured payload to a mutable tile snapshot. */
    public static void apply(BlockState state, String tileNbt) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tileNbt, "tileNbt");
        if (tileNbt.isBlank()) {
            return;
        }
        if (!(state instanceof TileState tile)) {
            throw new IllegalStateException(
                    "A tile payload cannot be applied to " + state.getType().getKey());
        }
        String[] fields = tileNbt.split("\\|", -1);
        if (fields.length != FIELD_COUNT || !VERSION.equals(fields[0])) {
            throw new IllegalArgumentException("Unsupported tile payload version or shape");
        }
        try {
            tile.getPersistentDataContainer().readFromBytes(
                    decodeBytes(fields[1]),
                    true);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Tile persistent data is not readable", exception);
        }

        if (!fields[2].isEmpty()) {
            if (!(state instanceof TileStateInventoryHolder inventoryHolder)) {
                throw new IllegalArgumentException(
                        "Tile payload contains inventory data for a non-inventory state");
            }
            ItemStack[] contents;
            try {
                contents = ItemStack.deserializeItemsFromBytes(decodeBytes(fields[2]));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Tile inventory data is not readable", exception);
            }
            inventoryHolder.getSnapshotInventory().setContents(contents);
        }

        if (state instanceof Lockable lockable) {
            lockable.setLock(decodeNullableString(fields[3]));
        } else if (!fields[3].isEmpty()) {
            throw new IllegalArgumentException(
                    "Tile payload contains a lock for a non-lockable state");
        }

        if (state instanceof Nameable nameable) {
            nameable.setCustomName(decodeNullableString(fields[4]));
        } else if (!fields[4].isEmpty()) {
            throw new IllegalArgumentException(
                    "Tile payload contains a name for a non-nameable state");
        }
    }

    private static byte[] serializePersistentData(TileState tile) {
        try {
            return tile.getPersistentDataContainer().serializeToBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize tile persistent data", exception);
        }
    }

    private static String encodeInventory(BlockState state) {
        if (!(state instanceof TileStateInventoryHolder inventoryHolder)) {
            return "";
        }
        try {
            return encodeBytes(ItemStack.serializeItemsAsBytes(
                    inventoryHolder.getSnapshotInventory().getContents()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize tile inventory", exception);
        }
    }

    private static String encodeBytes(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Objects.requireNonNull(bytes, "bytes"));
    }

    private static byte[] decodeBytes(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Tile payload contains invalid binary data", exception);
        }
    }

    private static String encodeNullableString(String value) {
        return value == null
                ? ""
                : Base64.getUrlEncoder().withoutPadding().encodeToString(
                        value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeNullableString(String value) {
        if (value.isEmpty()) {
            return null;
        }
        return new String(decodeBytes(value), StandardCharsets.UTF_8);
    }
}
