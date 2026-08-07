package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Proxy;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class ResearchCrystalTaggerTest {
    @Test
    void normalizesPluginNamesBeforeCreatingKeys() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> "getName".equals(method.getName())
                        ? "MinecraftTowerDefense"
                        : null);

        assertDoesNotThrow(() -> new ResearchCrystalTagger(plugin));
    }
}
