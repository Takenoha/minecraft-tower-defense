package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.domain.TowerType
import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Stores and validates the stable identity of the physical tower entity. */
class TowerEntityTagger(plugin: Plugin) {
    private val markerKey: NamespacedKey
    private val versionKey: NamespacedKey
    private val towerIdKey: NamespacedKey
    private val teamIdKey: NamespacedKey
    private val typeKey: NamespacedKey
    private val levelKey: NamespacedKey

    init {
        Objects.requireNonNull(plugin, "plugin")
        markerKey = NamespacedKey(plugin, "tower_entity")
        versionKey = NamespacedKey(plugin, "tower_entity_version")
        towerIdKey = NamespacedKey(plugin, "tower_id")
        teamIdKey = NamespacedKey(plugin, "tower_team_id")
        typeKey = NamespacedKey(plugin, "tower_type")
        levelKey = NamespacedKey(plugin, "tower_level")
    }

    fun tag(entity: Entity, identity: TowerEntityIdentity) {
        Objects.requireNonNull(entity, "entity")
        Objects.requireNonNull(identity, "identity")
        val data = entity.persistentDataContainer
        data.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        data.set(versionKey, PersistentDataType.INTEGER, ENTITY_VERSION)
        data.set(towerIdKey, PersistentDataType.STRING, identity.towerId.toString())
        data.set(teamIdKey, PersistentDataType.STRING, identity.teamId.toString())
        data.set(typeKey, PersistentDataType.STRING, identity.type.id())
        data.set(levelKey, PersistentDataType.INTEGER, identity.individualLevel)
    }

    fun read(entity: Entity): Optional<TowerEntityIdentity> {
        Objects.requireNonNull(entity, "entity")
        return read(entity.persistentDataContainer)
    }

    private fun read(data: org.bukkit.persistence.PersistentDataContainer): Optional<TowerEntityIdentity> {
        val marker = data.get(markerKey, PersistentDataType.BYTE)
        val version = data.get(versionKey, PersistentDataType.INTEGER)
        val towerId = data.get(towerIdKey, PersistentDataType.STRING)
        val teamId = data.get(teamIdKey, PersistentDataType.STRING)
        val type = data.get(typeKey, PersistentDataType.STRING)
        val level = data.get(levelKey, PersistentDataType.INTEGER)
        if (marker == null || marker != 1.toByte() || version == null ||
            version != ENTITY_VERSION || towerId == null || teamId == null ||
            type == null || level == null || level <= 0
        ) {
            return Optional.empty()
        }
        return try {
            Optional.of(
                TowerEntityIdentity(
                    UUID.fromString(towerId),
                    UUID.fromString(teamId),
                    TowerType.fromId(type),
                    level,
                )
            )
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }
    }

    companion object {
        const val ENTITY_VERSION: Int = 1
    }
}
