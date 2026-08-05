package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import org.junit.jupiter.api.Test;

class EventEnemyVisualPolicyTest {
    @Test
    void everyDefenseEventRoleIsGlowing() {
        for (EnemyRole role : EnemyRole.values()) {
            assertTrue(EventEnemyVisualPolicy.shouldGlow(role), role.name());
        }
    }
}
