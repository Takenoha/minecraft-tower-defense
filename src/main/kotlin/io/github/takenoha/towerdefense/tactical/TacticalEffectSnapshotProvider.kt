package io.github.takenoha.towerdefense.tactical

import java.util.UUID

/** Runtime boundary for reading an already-compiled active effect snapshot. */
interface TacticalEffectSnapshotProvider {
    fun currentForDefense(defenseId: UUID): TacticalEffectSnapshot
}
