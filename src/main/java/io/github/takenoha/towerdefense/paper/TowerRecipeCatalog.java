package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Keeps the registered tower recipe keys in sync with recipe-book discovery. */
public final class TowerRecipeCatalog {
    private TowerRecipeCatalog() {}

    /** Returns the stable plugin-local recipe suffix for a tower type. */
    static String recipeKeySuffix(TowerType type) {
        return "tower_" + Objects.requireNonNull(type, "type").id();
    }

    public static NamespacedKey key(Plugin plugin, TowerType type) {
        return new NamespacedKey(
                Objects.requireNonNull(plugin, "plugin"), recipeKeySuffix(type));
    }

    /** Returns every tower recipe key in the same order as {@link TowerType#values()}. */
    public static List<NamespacedKey> keys(Plugin plugin) {
        List<NamespacedKey> keys = new ArrayList<>();
        for (TowerType type : TowerType.values()) {
            keys.add(key(plugin, type));
        }
        return List.copyOf(keys);
    }

    /** Discovers all tower recipes and returns the number newly discovered for this player. */
    public static int discoverAll(Plugin plugin, Player player) {
        Objects.requireNonNull(player, "player");
        int discovered = 0;
        for (NamespacedKey key : keys(plugin)) {
            if (player.discoverRecipe(key)) {
                discovered++;
            }
        }
        return discovered;
    }
}
