package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreStateTest {
    @Test
    void constructorEnforcesHealthAndPresenceInvariants() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CoreState(0L, 0L, false)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CoreState(100L, -1L, false)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CoreState(100L, 101L, true)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CoreState(100L, 0L, true)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CoreState(100L, 1L, false)));
    }

    @Test
    void damageSaturatesAtZeroAndMakesTheCoreAbsent() {
        CoreState damaged = CoreState.intact(100L).damage(40L);
        assertEquals(60L, damaged.currentHitPoints());
        assertTrue(damaged.present());

        CoreState destroyed = damaged.damage(Long.MAX_VALUE);
        assertEquals(CoreState.destroyed(100L), destroyed);
        assertTrue(destroyed.isDestroyed());
        assertSame(destroyed, destroyed.damage(1L));
    }

    @Test
    void repairSaturatesAtMaximumWithoutOverflow() {
        CoreState damaged = CoreState.intact(Long.MAX_VALUE).damage(5L);
        CoreState repaired = damaged.repair(Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, repaired.currentHitPoints());
        assertFalse(repaired.isDestroyed());
        assertSame(repaired, repaired.repair(1L));
    }

    @Test
    void rejectsNegativeAmountsAndRepairingDestroyedCore() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> CoreState.intact(10L).damage(-1L)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> CoreState.intact(10L).repair(-1L)),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> CoreState.destroyed(10L).repair(1L)));
    }
}
