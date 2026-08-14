package io.github.takenoha.towerdefense.paper

import java.io.IOException
import java.io.StringReader
import java.util.Objects
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack

/** Serializes an untagged Paper item stack for the database-owned escrow payload. */
class PaperItemStackCodec private constructor() {
    companion object {
        @JvmStatic
        fun encode(itemStack: ItemStack): String {
            Objects.requireNonNull(itemStack, "itemStack")
            val configuration = YamlConfiguration()
            configuration.set("item", itemStack.clone())
            return configuration.saveToString()
        }

        @JvmStatic
        fun decode(payload: String): ItemStack {
            Objects.requireNonNull(payload, "payload")
            val configuration = YamlConfiguration()
            try {
                configuration.load(StringReader(payload))
            } catch (exception: IOException) {
                throw IllegalArgumentException("The escrow item payload is invalid", exception)
            } catch (exception: InvalidConfigurationException) {
                throw IllegalArgumentException("The escrow item payload is invalid", exception)
            }
            val itemStack = configuration.getItemStack("item")
            if (itemStack == null || itemStack.type.isAir || itemStack.amount <= 0) {
                throw IllegalArgumentException("The escrow item payload has no usable item")
            }
            return itemStack
        }
    }
}
