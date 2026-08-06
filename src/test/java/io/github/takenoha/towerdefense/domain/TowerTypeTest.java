package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TowerTypeTest {
    @Test
    void arrowHasStablePersistenceId() {
        assertEquals(TowerType.ARROW, TowerType.fromId("arrow"));
        assertEquals("arrow", TowerType.ARROW.id());
    }

    @Test
    void cannonHasStablePersistenceId() {
        assertEquals(TowerType.CANNON, TowerType.fromId("CANNON"));
        assertEquals("cannon", TowerType.CANNON.id());
    }

    @Test
    void unknownTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TowerType.fromId("unknown"));
    }
}
