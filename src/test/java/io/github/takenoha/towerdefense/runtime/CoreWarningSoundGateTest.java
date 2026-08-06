package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CoreWarningSoundGateTest {
    @Test
    void debouncesWarningsUntilTheConfiguredTickBoundary() {
        CoreWarningSoundGate gate = new CoreWarningSoundGate(10L);

        assertTrue(gate.tryClaim(100L));
        assertFalse(gate.tryClaim(100L));
        assertFalse(gate.tryClaim(109L));
        assertTrue(gate.tryClaim(110L));
    }

    @Test
    void rejectsAZeroOrNegativeInterval() {
        assertThrows(IllegalArgumentException.class, () -> new CoreWarningSoundGate(0L));
        assertThrows(IllegalArgumentException.class, () -> new CoreWarningSoundGate(-1L));
    }
}
