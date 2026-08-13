package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class TowerRecipeCatalogTest {
    @Test
    void hasOneStableRecipeKeyForEveryTowerType() {
        assertEquals(
                List.of(
                        "tower_arrow",
                        "tower_cannon",
                        "tower_frost",
                        "tower_lightning",
                        "tower_support",
                        "tower_sniper",
                        "tower_flame"),
                List.of(TowerType.values()).stream()
                        .map(TowerRecipeCatalog::recipeKeySuffix)
                        .toList());
    }

    @Test
    void discoversEveryTowerRecipeForAPlayer() {
        List<NamespacedKey> discovered = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("discoverRecipe")) {
                        discovered.add((NamespacedKey) arguments[0]);
                        return true;
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getName")
                            || method.getName().equals("namespace")) {
                        return "minecraft-tower-defense";
                    }
                    throw new UnsupportedOperationException(method.toString());
                });

        assertEquals(7, TowerRecipeCatalog.discoverAll(plugin, player));
        assertEquals(
                List.of(
                        "tower_arrow",
                        "tower_cannon",
                        "tower_frost",
                        "tower_lightning",
                        "tower_support",
                        "tower_sniper",
                        "tower_flame"),
                discovered.stream().map(NamespacedKey::getKey).toList());
    }
}
