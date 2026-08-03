package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.persistence.CoreRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.Block;

/** Main-thread cache of durable cores used by protection listeners and commands. */
public final class CoreRegistry {
    private final Map<CoreBlockKey, CoreRecord> byBlock = new HashMap<>();
    private final Map<UUID, CoreRecord> byTeam = new HashMap<>();

    public void replaceAll(Collection<CoreRecord> cores) {
        Objects.requireNonNull(cores, "cores");
        byBlock.clear();
        byTeam.clear();
        for (CoreRecord core : cores) {
            register(core);
        }
    }

    public void register(CoreRecord core) {
        Objects.requireNonNull(core, "core");
        if (core.currentHitPoints() == 0L) {
            unregister(core.id());
            return;
        }
        CoreBlockKey key = new CoreBlockKey(
                core.worldId(), core.blockX(), core.blockY(), core.blockZ());
        CoreRecord blockCollision = byBlock.putIfAbsent(key, core);
        if (blockCollision != null && !blockCollision.id().equals(core.id())) {
            throw new IllegalStateException("two cores occupy " + key);
        }
        CoreRecord teamCollision = byTeam.putIfAbsent(core.teamId(), core);
        if (teamCollision != null && !teamCollision.id().equals(core.id())) {
            byBlock.remove(key, core);
            throw new IllegalStateException("team has more than one core: " + core.teamId());
        }
    }

    /** Replaces the cached version of a core, removing a destroyed core from protection. */
    public void replace(CoreRecord core) {
        Objects.requireNonNull(core, "core");
        unregister(core.id());
        register(core);
    }

    /** Removes a core identity from both main-thread indexes. */
    public void unregister(UUID coreId) {
        Objects.requireNonNull(coreId, "coreId");
        byBlock.entrySet().removeIf(entry -> entry.getValue().id().equals(coreId));
        byTeam.entrySet().removeIf(entry -> entry.getValue().id().equals(coreId));
    }

    public Optional<CoreRecord> at(Block block) {
        return Optional.ofNullable(byBlock.get(CoreBlockKey.from(block)));
    }

    public Optional<CoreRecord> forTeam(UUID teamId) {
        return Optional.ofNullable(byTeam.get(Objects.requireNonNull(teamId, "teamId")));
    }

    public boolean isCore(Block block) {
        return byBlock.containsKey(CoreBlockKey.from(block));
    }

    public Collection<CoreRecord> all() {
        return List.copyOf(byBlock.values());
    }
}
