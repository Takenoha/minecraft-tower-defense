package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerRecoveryGuardTest {
    @Test
    void noOpenRecoveryReleasesTheGuardImmediately() {
        PlayerRecoveryGuard guard = new PlayerRecoveryGuard();
        UUID playerId = UUID.randomUUID();

        guard.begin(playerId);
        assertTrue(guard.isGuarded(playerId));
        guard.complete(playerId);
        assertFalse(guard.isGuarded(playerId));
    }

    @Test
    void quitOrRestartLifecycleDoesNotLeakACompletedPlayerGuard() {
        PlayerRecoveryGuard guard = new PlayerRecoveryGuard();
        UUID playerId = UUID.randomUUID();

        guard.begin(playerId);
        guard.complete(playerId);
        assertFalse(guard.isGuarded(playerId));
        assertFalse(new PlayerRecoveryGuard().isGuarded(playerId));
    }
}
