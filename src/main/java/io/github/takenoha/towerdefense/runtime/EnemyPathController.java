package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyPathAction;
import io.github.takenoha.towerdefense.domain.EnemyPathContext;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.domain.EnemyRolePlanner;
import java.util.Objects;

/** Connects a main-thread obstacle snapshot to the Paper-independent role planner. */
public final class EnemyPathController {
    private EnemyPathController() {
    }

    /**
     * Converts the latest pathfinder result and obstacle facts into one bounded planner action.
     *
     * <p>A direct path remains authoritative. When the pathfinder cannot provide one, the
     * world-aware facts supply the role-specific fallback flags. This class does not mutate
     * Paper state or invoke a terrain action.</p>
     */
    public static EnemyPathAction decide(
            EnemyRole role,
            boolean directPathAvailable,
            EnemyObstacleFacts obstacleFacts,
            int consecutivePathFailures) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(obstacleFacts, "obstacleFacts");
        EnemyPathContext context = directPathAvailable
                ? new EnemyPathContext(true, false, false, false, consecutivePathFailures)
                : obstacleFacts.toPathContext(consecutivePathFailures);
        return EnemyRolePlanner.decide(role, context);
    }
}
