package io.github.takenoha.towerdefense.config;

import java.util.Map;

/** Complete, Paper-independent settings used by the defense foundation. */
public record PluginSettings(
        CombatSettings combat,
        CoreSettings core,
        EnemySettings enemies) {

    /**
     * Reads and validates the nested map shape used by {@code config.yml}.
     *
     * @param values top-level configuration values
     * @return validated plugin settings
     * @throws InvalidPluginSettingsException if any value is missing, malformed, or invalid
     */
    public static PluginSettings from(Map<String, ?> values) {
        return PluginSettingsMapReader.read(values);
    }

    /**
     * Validates settings assembled directly from record values.
     *
     * @return this settings instance
     * @throws InvalidPluginSettingsException if one or more invariants are violated
     */
    public PluginSettings validated() {
        return PluginSettingsValidator.validate(this);
    }
}
