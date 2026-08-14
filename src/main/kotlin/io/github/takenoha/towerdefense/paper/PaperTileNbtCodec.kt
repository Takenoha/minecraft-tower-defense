package io.github.takenoha.towerdefense.paper

import io.papermc.paper.block.TileStateInventoryHolder
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Objects
import java.util.regex.Pattern
import org.bukkit.Nameable
import org.bukkit.block.BlockState
import org.bukkit.block.Lockable
import org.bukkit.block.TileState
import org.bukkit.inventory.ItemStack

/**
 * Captures the mutable tile data exposed by the Paper API in a deterministic payload.
 *
 * Paper deliberately does not expose the server's internal raw NBT compound. The codec uses
 * the stable API projection instead: the tile's persistent-data bytes, snapshot inventory bytes,
 * lock, and custom name. The payload is versioned so a future Paper adapter can add fields without
 * making old WAL rows ambiguous.
 */
@Suppress("DEPRECATION")
class PaperTileNbtCodec private constructor() {
    companion object {
        private const val VERSION = "v1"
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 5

        /** Returns an empty payload for an ordinary block and a versioned payload for a tile state. */
        @JvmStatic
        fun capture(state: BlockState): String {
            Objects.requireNonNull(state, "state")
            if (state !is TileState) {
                return ""
            }
            return VERSION +
                SEPARATOR + encodeBytes(serializePersistentData(state)) +
                SEPARATOR + encodeInventory(state) +
                SEPARATOR + encodeNullableString(if (state is Lockable) state.lock else null) +
                SEPARATOR + encodeNullableString(if (state is Nameable) state.customName else null)
        }

        /** Applies a previously captured payload to a mutable tile snapshot. */
        @JvmStatic
        fun apply(state: BlockState, tileNbt: String) {
            Objects.requireNonNull(state, "state")
            Objects.requireNonNull(tileNbt, "tileNbt")
            if (tileNbt.isBlank()) {
                return
            }
            if (state !is TileState) {
                throw IllegalStateException(
                    "A tile payload cannot be applied to " + state.type.key,
                )
            }
            val fields = Pattern.compile("\\|").split(tileNbt, -1)
            if (fields.size != FIELD_COUNT || VERSION != fields[0]) {
                throw IllegalArgumentException("Unsupported tile payload version or shape")
            }
            try {
                state.persistentDataContainer.readFromBytes(
                    decodeBytes(fields[1]),
                    true,
                )
            } catch (exception: IOException) {
                throw IllegalArgumentException("Tile persistent data is not readable", exception)
            }

            if (fields[2].isNotEmpty()) {
                if (state !is TileStateInventoryHolder) {
                    throw IllegalArgumentException(
                        "Tile payload contains inventory data for a non-inventory state",
                    )
                }
                val contents: Array<ItemStack> = try {
                    ItemStack.deserializeItemsFromBytes(decodeBytes(fields[2]))
                } catch (exception: RuntimeException) {
                    throw IllegalArgumentException("Tile inventory data is not readable", exception)
                }
                state.snapshotInventory.contents = contents
            }

            if (state is Lockable) {
                state.setLock(decodeNullableString(fields[3]))
            } else if (fields[3].isNotEmpty()) {
                throw IllegalArgumentException(
                    "Tile payload contains a lock for a non-lockable state",
                )
            }

            if (state is Nameable) {
                state.customName = decodeNullableString(fields[4])
            } else if (fields[4].isNotEmpty()) {
                throw IllegalArgumentException(
                    "Tile payload contains a name for a non-nameable state",
                )
            }
        }

        private fun serializePersistentData(tile: TileState): ByteArray {
            return try {
                tile.persistentDataContainer.serializeToBytes()
            } catch (exception: IOException) {
                throw IllegalStateException("Could not serialize tile persistent data", exception)
            }
        }

        private fun encodeInventory(state: BlockState): String {
            if (state !is TileStateInventoryHolder) {
                return ""
            }
            return try {
                encodeBytes(ItemStack.serializeItemsAsBytes(state.snapshotInventory.contents))
            } catch (exception: RuntimeException) {
                throw IllegalStateException("Could not serialize tile inventory", exception)
            }
        }

        private fun encodeBytes(bytes: ByteArray): String {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Objects.requireNonNull(bytes, "bytes"),
            )
        }

        private fun decodeBytes(value: String): ByteArray {
            return try {
                Base64.getUrlDecoder().decode(value)
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("Tile payload contains invalid binary data", exception)
            }
        }

        private fun encodeNullableString(value: String?): String {
            return if (value == null) {
                ""
            } else {
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    value.toByteArray(StandardCharsets.UTF_8),
                )
            }
        }

        private fun decodeNullableString(value: String): String? {
            if (value.isEmpty()) {
                return null
            }
            return String(decodeBytes(value), StandardCharsets.UTF_8)
        }

    }
}
