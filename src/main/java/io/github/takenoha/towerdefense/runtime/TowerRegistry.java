package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.persistence.TowerRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.Block;

/** Main-thread cache of durable towers used by placement, protection, and attack logic. */
public final class TowerRegistry {
    private final Map<UUID, TowerRecord> byId = new HashMap<>();
    private final Map<TowerBlockKey, TowerRecord> byBlock = new HashMap<>();
    private final Map<UUID, TowerRecord> byEntity = new HashMap<>();

    public void replaceAll(Collection<TowerRecord> towers) {
        Objects.requireNonNull(towers, "towers");
        byId.clear();
        byBlock.clear();
        byEntity.clear();
        for (TowerRecord tower : towers) {
            register(tower);
        }
    }

    public void register(TowerRecord tower) {
        Objects.requireNonNull(tower, "tower");
        TowerBlockKey blockKey = key(tower);
        TowerRecord blockCollision = byBlock.putIfAbsent(blockKey, tower);
        if (blockCollision != null && !blockCollision.id().equals(tower.id())) {
            throw new IllegalStateException("two towers occupy " + blockKey);
        }
        TowerRecord idCollision = byId.putIfAbsent(tower.id(), tower);
        if (idCollision != null && !idCollision.equals(tower)) {
            byBlock.remove(blockKey, tower);
            throw new IllegalStateException("tower identity is duplicated: " + tower.id());
        }
        TowerRecord entityCollision = byEntity.putIfAbsent(tower.entityId(), tower);
        if (entityCollision != null && !entityCollision.id().equals(tower.id())) {
            byBlock.remove(blockKey, tower);
            byId.remove(tower.id(), tower);
            throw new IllegalStateException("tower entity identity is duplicated: " + tower.entityId());
        }
    }

    public void replace(TowerRecord tower) {
        Objects.requireNonNull(tower, "tower");
        unregister(tower.id());
        register(tower);
    }

    public void unregister(UUID towerId) {
        Objects.requireNonNull(towerId, "towerId");
        TowerRecord removed = byId.remove(towerId);
        if (removed != null) {
            byBlock.remove(key(removed), removed);
            byEntity.remove(removed.entityId(), removed);
        }
    }

    public Optional<TowerRecord> find(UUID towerId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(towerId, "towerId")));
    }

    public Optional<TowerRecord> at(Block block) {
        Objects.requireNonNull(block, "block");
        return Optional.ofNullable(byBlock.get(new TowerBlockKey(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ())));
    }

    public Optional<TowerRecord> forEntity(UUID entityId) {
        return Optional.ofNullable(byEntity.get(Objects.requireNonNull(entityId, "entityId")));
    }

    public Optional<TowerRecord> forTeam(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return byId.values().stream().filter(tower -> tower.teamId().equals(teamId)).findFirst();
    }

    public Collection<TowerRecord> all() {
        return List.copyOf(byId.values());
    }

    public long countForTeam(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        return byId.values().stream().filter(tower -> tower.teamId().equals(teamId)).count();
    }

    private static TowerBlockKey key(TowerRecord tower) {
        return new TowerBlockKey(
                tower.worldId(), tower.blockX(), tower.blockY(), tower.blockZ());
    }
}
