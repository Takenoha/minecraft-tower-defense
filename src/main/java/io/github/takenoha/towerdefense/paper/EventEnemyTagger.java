package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.runtime.TaggedEnemy;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Reads and writes event/logical-enemy IDs in an entity's persistent data. */
public final class EventEnemyTagger {
    private final NamespacedKey eventIdKey;
    private final NamespacedKey logicalEnemyIdKey;
    private final NamespacedKey roleKey;

    public EventEnemyTagger(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        eventIdKey = new NamespacedKey(plugin, "event_id");
        logicalEnemyIdKey = new NamespacedKey(plugin, "logical_enemy_id");
        roleKey = new NamespacedKey(plugin, "enemy_role");
    }

    public void tag(Entity entity, TaggedEnemy taggedEnemy) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(taggedEnemy, "taggedEnemy");
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(eventIdKey, PersistentDataType.STRING, taggedEnemy.eventId().toString());
        data.set(
                logicalEnemyIdKey,
                PersistentDataType.STRING,
                taggedEnemy.logicalEnemyId().toString());
        data.set(roleKey, PersistentDataType.STRING, taggedEnemy.role().id());
    }

    public Optional<TaggedEnemy> read(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String eventId = data.get(eventIdKey, PersistentDataType.STRING);
        String enemyId = data.get(logicalEnemyIdKey, PersistentDataType.STRING);
        if (eventId == null || enemyId == null) {
            return Optional.empty();
        }
        String role = data.get(roleKey, PersistentDataType.STRING);
        try {
            return Optional.of(new TaggedEnemy(
                    UUID.fromString(eventId),
                    UUID.fromString(enemyId),
                    role == null ? EnemyRole.NORMAL : EnemyRole.fromId(role)));
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
    }
}
