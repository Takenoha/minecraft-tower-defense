package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import java.util.Objects;
import java.util.Optional;

/**
 * Paper-independent admission control for one temporary builder bridge block.
 *
 * <p>The planner intentionally produces at most one block per path decision. The durable ledger
 * owns the active-count check so a restart cannot lose the event-wide placement cap.</p>
 */
public final class EnemyBridgePlanner {
    /** Hard safety cap for unresolved temporary bridge blocks in one defense event. */
    public static final long MAX_ACTIVE_TEMPORARY_BLOCKS = 8L;

    private EnemyBridgePlanner() {
    }

    public static Optional<EnemyBridgePlan> plan(
            EnemyObstacleFacts facts,
            long activeTemporaryBlocks) {
        Objects.requireNonNull(facts, "facts");
        if (activeTemporaryBlocks < 0L) {
            throw new IllegalArgumentException("activeTemporaryBlocks must not be negative");
        }
        if (activeTemporaryBlocks >= MAX_ACTIVE_TEMPORARY_BLOCKS
                || facts.classification() != EnemyObstacleClassification.BUILDABLE_GAP
                || !facts.withinCombatArea()
                || !facts.supportAvailable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new EnemyBridgePlan(facts.targetMaterialKey()));
        } catch (IllegalArgumentException unsafeTarget) {
            // World facts are untrusted input to this boundary. An unsafe target is not an error
            // that should make a path tick mutate anything; it is simply not bridgeable.
            return Optional.empty();
        }
    }
}
