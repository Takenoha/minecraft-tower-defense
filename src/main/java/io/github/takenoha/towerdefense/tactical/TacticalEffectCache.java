package io.github.takenoha.towerdefense.tactical;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory cache of compiled effects; it never reads persistence on a combat hot path. */
public final class TacticalEffectCache implements TacticalEffectSnapshotProvider {
    private final TacticalBuildStateProvider stateProvider;
    private final TacticalEffectCompiler compiler;
    private final Map<UUID, TacticalEffectSnapshot> snapshots = new HashMap<>();

    public TacticalEffectCache(TacticalBuildStateProvider stateProvider) {
        this(stateProvider, new TacticalEffectCompiler());
    }

    public TacticalEffectCache(
            TacticalBuildStateProvider stateProvider,
            TacticalEffectCompiler compiler) {
        this.stateProvider = Objects.requireNonNull(stateProvider, "stateProvider");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /** Rebuilds one cache entry from the selected, versioned definition snapshot. */
    public synchronized void rebuild(UUID defenseId) {
        Objects.requireNonNull(defenseId, "defenseId");
        // Remove first so a provider/compiler failure cannot leave stale effects active.
        snapshots.remove(defenseId);
        Optional<TacticalBuildSelectionView> selected = stateProvider.findActiveByDefense(defenseId);
        if (selected == null || selected.isEmpty()) {
            return;
        }
        snapshots.put(defenseId, compiler.compile(selected.orElseThrow()));
    }

    /** Installs a supplied snapshot for recovery/tests without adding a persistence dependency. */
    public synchronized void rebuild(UUID defenseId, TacticalBuildSelectionView selected) {
        Objects.requireNonNull(defenseId, "defenseId");
        snapshots.remove(defenseId);
        snapshots.put(defenseId, compiler.compile(Objects.requireNonNull(selected, "selected")));
    }

    public synchronized void invalidate(UUID defenseId) {
        Objects.requireNonNull(defenseId, "defenseId");
        snapshots.remove(defenseId);
    }

    public synchronized void clear() {
        snapshots.clear();
    }

    @Override
    public synchronized TacticalEffectSnapshot currentForDefense(UUID defenseId) {
        Objects.requireNonNull(defenseId, "defenseId");
        return snapshots.getOrDefault(defenseId, EmptyTacticalEffectSnapshot.INSTANCE);
    }

    public synchronized int size() {
        return snapshots.size();
    }
}
