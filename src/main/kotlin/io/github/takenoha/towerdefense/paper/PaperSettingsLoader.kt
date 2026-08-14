package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.config.PluginSettings
import java.util.LinkedHashMap
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

/** Adapts Paper's YAML configuration to the Paper-independent validated settings model. */
class PaperSettingsLoader private constructor() {
    companion object {
        @JvmStatic
        fun load(configuration: FileConfiguration): PluginSettings {
            val values = linkedMapOf<String, Any?>(
                "combat" to sectionValues(configuration, "combat"),
                "core" to sectionValues(configuration, "core"),
                "enemies" to sectionValues(configuration, "enemies"),
                "protection" to sectionValues(configuration, "protection"),
                "rewards" to sectionValues(configuration, "rewards"),
                "terrain-mutation" to sectionValues(configuration, "terrain-mutation"),
            )
            return PluginSettings.from(values)
        }

        private fun sectionValues(
            configuration: FileConfiguration,
            path: String,
        ): Map<String, Any?> {
            val section: ConfigurationSection = configuration.getConfigurationSection(path)
                ?: return emptyMap()
            return LinkedHashMap(section.getValues(false))
        }
    }
}
