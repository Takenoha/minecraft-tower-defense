package io.github.takenoha.towerdefense.runtime;

import java.util.UUID;
import org.bukkit.Location;

/** Authorizes event-enemy identity and combat-area interaction boundaries. */
public interface EnemyAccessPolicy {
    boolean mayAffect(TaggedEnemy taggedEnemy, UUID playerId);

    boolean mayRemain(TaggedEnemy taggedEnemy, UUID entityId);

    boolean mayModifyCombatArea(UUID playerId, Location location);

    boolean isCombatAreaProtected(Location location);
}
