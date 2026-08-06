package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Stores and validates the stable identity of the physical tower entity. */
public final class TowerEntityTagger {
    public static final int ENTITY_VERSION = 1;

    private final NamespacedKey markerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey towerIdKey;
    private final NamespacedKey teamIdKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey levelKey;

    public TowerEntityTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        markerKey = new NamespacedKey(plugin, "tower_entity");
        versionKey = new NamespacedKey(plugin, "tower_entity_version");
        towerIdKey = new NamespacedKey(plugin, "tower_id");
        teamIdKey = new NamespacedKey(plugin, "tower_team_id");
        typeKey = new NamespacedKey(plugin, "tower_type");
        levelKey = new NamespacedKey(plugin, "tower_level");
    }

    public void tag(Entity entity, TowerEntityIdentity identity) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(identity, "identity");
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(versionKey, PersistentDataType.INTEGER, ENTITY_VERSION);
        data.set(towerIdKey, PersistentDataType.STRING, identity.towerId().toString());
        data.set(teamIdKey, PersistentDataType.STRING, identity.teamId().toString());
        data.set(typeKey, PersistentDataType.STRING, identity.type().id());
        data.set(levelKey, PersistentDataType.INTEGER, identity.individualLevel());
    }

    public Optional<TowerEntityIdentity> read(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return read(entity.getPersistentDataContainer());
    }

    private Optional<TowerEntityIdentity> read(PersistentDataContainer data) {
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String towerId = data.get(towerIdKey, PersistentDataType.STRING);
        String teamId = data.get(teamIdKey, PersistentDataType.STRING);
        String type = data.get(typeKey, PersistentDataType.STRING);
        Integer level = data.get(levelKey, PersistentDataType.INTEGER);
        if (marker == null || marker != 1 || version == null || version != ENTITY_VERSION
                || towerId == null || teamId == null || type == null || level == null
                || level <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TowerEntityIdentity(
                    UUID.fromString(towerId),
                    UUID.fromString(teamId),
                    TowerType.fromId(type),
                    level));
        } catch (IllegalArgumentException invalidIdentity) {
            return Optional.empty();
        }
    }
}
