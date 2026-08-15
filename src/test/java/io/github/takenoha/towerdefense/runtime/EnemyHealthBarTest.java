package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EnemyHealthBarTest {
    @Test
    void rendersTenSegmentsWithoutNumericHealth() {
        assertEquals("██████████", EnemyHealthBar.barText(20.0, 20.0));
        assertEquals("█████░░░░░", EnemyHealthBar.barText(10.0, 20.0));
        assertEquals("░░░░░░░░░░", EnemyHealthBar.barText(0.0, 20.0));
    }

    @Test
    void clampsHealthOutsideTheValidRange() {
        assertEquals(10, EnemyHealthBar.filledSegments(30.0, 20.0));
        assertEquals(0, EnemyHealthBar.filledSegments(-1.0, 20.0));
        assertEquals(0, EnemyHealthBar.filledSegments(10.0, 0.0));
    }
}
