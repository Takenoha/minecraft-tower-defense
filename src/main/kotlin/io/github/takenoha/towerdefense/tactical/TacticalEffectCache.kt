package io.github.takenoha.towerdefense.tactical

import java.util.HashMap
import java.util.Objects
import java.util.Optional
import java.util.UUID

/** In-memory cache of compiled effects; it never reads persistence on a combat hot path. */
class TacticalEffectCache(
    private val stateProvider: TacticalBuildStateProvider,
    private val compiler: TacticalEffectCompiler,
) : TacticalEffectSnapshotProvider {
    constructor(stateProvider: TacticalBuildStateProvider) : this(
        stateProvider,
        TacticalEffectCompiler(),
    )

    private val snapshots: MutableMap<UUID, TacticalEffectSnapshot> = HashMap()

    /** Rebuilds one cache entry from the selected, versioned definition snapshot. */
    @Synchronized
    fun rebuild(defenseId: UUID) {
        Objects.requireNonNull(defenseId, "defenseId")
        // Remove first so a provider/compiler failure cannot leave stale effects active.
        snapshots.remove(defenseId)
        val selected: Optional<TacticalBuildSelectionView>? =
            stateProvider.findActiveByDefense(defenseId)
        if (selected == null || selected.isEmpty()) {
            return
        }
        snapshots[defenseId] = compiler.compile(selected.orElseThrow())
    }

    /** Installs a supplied snapshot for recovery/tests without adding a persistence dependency. */
    @Synchronized
    fun rebuild(defenseId: UUID, selected: TacticalBuildSelectionView) {
        Objects.requireNonNull(defenseId, "defenseId")
        snapshots.remove(defenseId)
        snapshots[defenseId] = compiler.compile(
            Objects.requireNonNull(selected, "selected"),
        )
    }

    @Synchronized
    fun invalidate(defenseId: UUID) {
        Objects.requireNonNull(defenseId, "defenseId")
        snapshots.remove(defenseId)
    }

    /** Returns whether a selected tactical build is currently cached for the defense. */
    @Synchronized
    fun hasSelectedBuild(defenseId: UUID): Boolean {
        Objects.requireNonNull(defenseId, "defenseId")
        return snapshots.containsKey(defenseId)
    }

    @Synchronized
    fun clear() {
        snapshots.clear()
    }

    @Synchronized
    override fun currentForDefense(defenseId: UUID): TacticalEffectSnapshot {
        Objects.requireNonNull(defenseId, "defenseId")
        return snapshots.getOrDefault(defenseId, EmptyTacticalEffectSnapshot.INSTANCE)
    }

    @Synchronized
    fun size(): Int = snapshots.size
}
