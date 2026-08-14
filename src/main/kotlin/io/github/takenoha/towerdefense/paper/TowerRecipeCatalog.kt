package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.Objects
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Keeps the registered tower recipe keys in sync with recipe-book discovery. */
class TowerRecipeCatalog private constructor() {
    companion object {
        /** Returns the stable plugin-local recipe suffix for a tower type. */
        @JvmStatic
        fun recipeKeySuffix(type: TowerType): String =
            "tower_" + Objects.requireNonNull(type, "type").id()

        @JvmStatic
        fun key(plugin: Plugin, type: TowerType): NamespacedKey =
            NamespacedKey(Objects.requireNonNull(plugin, "plugin"), recipeKeySuffix(type))

        /** Returns every tower recipe key in the same order as [TowerType.values]. */
        @JvmStatic
        fun keys(plugin: Plugin): List<NamespacedKey> =
            TowerType.values().map { type -> key(plugin, type) }

        /** Discovers all tower recipes and returns the number newly discovered for this player. */
        @JvmStatic
        fun discoverAll(plugin: Plugin, player: Player): Int {
            Objects.requireNonNull(player, "player")
            var discovered = 0
            for (key in keys(plugin)) {
                if (player.discoverRecipe(key)) {
                    discovered++
                }
            }
            return discovered
        }
    }
}
