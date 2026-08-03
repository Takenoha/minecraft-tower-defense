package io.github.takenoha.towerdefense.paper;

import java.io.IOException;
import java.io.StringReader;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Serializes an untagged Paper item stack for the database-owned escrow payload. */
public final class PaperItemStackCodec {
    private PaperItemStackCodec() {
    }

    public static String encode(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("item", itemStack.clone());
        return configuration.saveToString();
    }

    public static ItemStack decode(String payload) {
        Objects.requireNonNull(payload, "payload");
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(new StringReader(payload));
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalArgumentException("The escrow item payload is invalid", exception);
        }
        ItemStack itemStack = configuration.getItemStack("item");
        if (itemStack == null || itemStack.getType().isAir() || itemStack.getAmount() <= 0) {
            throw new IllegalArgumentException("The escrow item payload has no usable item");
        }
        return itemStack;
    }
}
