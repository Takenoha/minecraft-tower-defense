package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TowerTargetPriorityTest {
    @Test
    void exposesStablePersistenceIds() {
        assertEquals(TowerTargetPriority.CORE_NEAREST,
                TowerTargetPriority.fromId("core_nearest"));
        assertEquals(TowerTargetPriority.BOSS, TowerTargetPriority.fromId("BOSS"));
        assertEquals("core_nearest", TowerTargetPriority.CORE_NEAREST.id());
    }

    @Test
    void unknownPriorityIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TowerTargetPriority.fromId("unknown"));
    }
}
