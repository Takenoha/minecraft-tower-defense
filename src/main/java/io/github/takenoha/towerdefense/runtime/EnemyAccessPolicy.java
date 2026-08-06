package io.github.takenoha.towerdefense.runtime;

import java.util.UUID;
import org.bukkit.Location;

/** Authorizes event-enemy identity and combat-area interaction boundaries. */
public interface EnemyAccessPolicy {
    boolean mayAffect(TaggedEnemy taggedEnemy, UUID playerId);

    /** Returns whether a tower owned by the team may damage this event enemy. */
    default boolean mayAffectFromTower(TaggedEnemy taggedEnemy, UUID teamId) {
        return false;
    }

    boolean mayRemain(TaggedEnemy taggedEnemy, UUID entityId);

    boolean mayModifyCombatArea(UUID playerId, Location location);

    boolean isCombatAreaProtected(Location location);
}
