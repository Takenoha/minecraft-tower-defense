package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.EnemyRole
import io.github.takenoha.towerdefense.runtime.TaggedEnemy
import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Reads and writes event/logical-enemy IDs in an entity's persistent data. */
class EventEnemyTagger(plugin: Plugin) {
    private val eventIdKey: NamespacedKey
    private val logicalEnemyIdKey: NamespacedKey
    private val roleKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        eventIdKey = NamespacedKey(plugin, "event_id")
        logicalEnemyIdKey = NamespacedKey(plugin, "logical_enemy_id")
        roleKey = NamespacedKey(plugin, "enemy_role")
    }

    fun tag(entity: Entity, taggedEnemy: TaggedEnemy) {
        Objects.requireNonNull(entity, "entity")
        Objects.requireNonNull(taggedEnemy, "taggedEnemy")
        val data = entity.persistentDataContainer
        data.set(eventIdKey, PersistentDataType.STRING, taggedEnemy.eventId.toString())
        data.set(logicalEnemyIdKey, PersistentDataType.STRING, taggedEnemy.logicalEnemyId.toString())
        data.set(roleKey, PersistentDataType.STRING, taggedEnemy.role.id())
    }

    fun read(entity: Entity): Optional<TaggedEnemy> {
        Objects.requireNonNull(entity, "entity")
        val data = entity.persistentDataContainer
        val eventId = data.get(eventIdKey, PersistentDataType.STRING)
        val enemyId = data.get(logicalEnemyIdKey, PersistentDataType.STRING)
        if (eventId == null || enemyId == null) {
            return Optional.empty()
        }
        val role = data.get(roleKey, PersistentDataType.STRING)
        return try {
            Optional.of(
                TaggedEnemy(
                    UUID.fromString(eventId),
                    UUID.fromString(enemyId),
                    role?.let { EnemyRole.fromId(it) } ?: EnemyRole.NORMAL,
                )
            )
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }
}
