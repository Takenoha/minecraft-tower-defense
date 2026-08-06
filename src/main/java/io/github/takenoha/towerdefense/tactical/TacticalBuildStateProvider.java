package io.github.takenoha.towerdefense.tactical;

import java.util.Optional;
import java.util.UUID;

/** Read-only persistence boundary used by the defense runtime. */
public interface TacticalBuildStateProvider {
    Optional<TacticalBuildSelectionView> findActiveByDefense(UUID defenseId);
}
