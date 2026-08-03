package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Adapts Paper's YAML configuration to the Paper-independent validated settings model. */
public final class PaperSettingsLoader {
    private PaperSettingsLoader() {
    }

    public static PluginSettings load(FileConfiguration configuration) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("combat", sectionValues(configuration, "combat"));
        values.put("core", sectionValues(configuration, "core"));
        values.put("enemies", sectionValues(configuration, "enemies"));
        values.put("protection", sectionValues(configuration, "protection"));
        return PluginSettings.from(values);
    }

    private static Map<String, Object> sectionValues(
            FileConfiguration configuration,
            String path) {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(section.getValues(false));
    }
}
