package io.github.takenoha.towerdefense.paper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Main-thread lifecycle gate used while a player's voucher state is being reconciled. */
final class PlayerRecoveryGuard {
    private final Set<UUID> playerIds = new HashSet<>();

    void begin(UUID playerId) {
        playerIds.add(playerId);
    }

    void complete(UUID playerId) {
        playerIds.remove(playerId);
    }

    boolean isGuarded(UUID playerId) {
        return playerIds.contains(playerId);
    }
}
