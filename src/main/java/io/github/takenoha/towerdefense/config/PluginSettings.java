package io.github.takenoha.towerdefense.config;

import java.util.Map;

/** Complete, Paper-independent settings used by the defense foundation. */
public record PluginSettings(
        CombatSettings combat,
        CoreSettings core,
        EnemySettings enemies,
        ProtectionSettings protection,
        RewardSettings rewards,
        TerrainMutationSettings terrainMutation,
        TowerSettings towers) {

    /** Keeps five-field construction source-compatible while the activation gate is opt-in. */
    public PluginSettings(
            CombatSettings combat,
            CoreSettings core,
            EnemySettings enemies,
            ProtectionSettings protection,
            RewardSettings rewards) {
        this(
                combat,
                core,
                enemies,
                protection,
                rewards,
                TerrainMutationSettings.disabled(),
                TowerSettings.defaults());
    }

    /** Keeps direct settings construction source-compatible with the terrain gate boundary. */
    public PluginSettings(
            CombatSettings combat,
            CoreSettings core,
            EnemySettings enemies,
            ProtectionSettings protection,
            RewardSettings rewards,
            TerrainMutationSettings terrainMutation) {
        this(
                combat,
                core,
                enemies,
                protection,
                rewards,
                terrainMutation,
                TowerSettings.defaults());
    }

    /** Keeps direct settings construction source-compatible with the pre-boundary model. */
    public PluginSettings(
            CombatSettings combat,
            CoreSettings core,
            EnemySettings enemies) {
        this(combat, core, enemies, ProtectionSettings.empty(), RewardSettings.defaults());
    }

    /** Keeps four-field construction source-compatible with the protection-boundary model. */
    public PluginSettings(
            CombatSettings combat,
            CoreSettings core,
            EnemySettings enemies,
            ProtectionSettings protection) {
        this(combat, core, enemies, protection, RewardSettings.defaults());
    }

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
