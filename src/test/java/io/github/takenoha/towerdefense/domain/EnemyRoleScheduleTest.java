package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnemyRoleScheduleTest {
    @Test
    void keepsTheBossSlotAndAllocatesConfiguredRolesDeterministically() {
        EnemyRoleSchedule schedule = new EnemyRoleSchedule(0.25d, 0.25d);

        List<EnemyRole> roles = schedule.forWave(1L, 1, 8, false);
        List<EnemyRole> finalRoles = schedule.forWave(1L, 1, 5, true);

        assertEquals(8, roles.size());
        assertEquals(2L, roles.stream().filter(role -> role == EnemyRole.DESTROYER).count());
        assertEquals(2L, roles.stream().filter(role -> role == EnemyRole.BUILDER).count());
        assertEquals(4L, roles.stream().filter(role -> role == EnemyRole.NORMAL).count());
        assertEquals(List.of(
                EnemyRole.BOSS,
                EnemyRole.DESTROYER,
                EnemyRole.BUILDER,
                EnemyRole.NORMAL,
                EnemyRole.NORMAL), finalRoles);
    }

    @Test
    void raisesSpecialRoleAllocationOnlyWithinTheFixedWaveCount() {
        EnemyRoleSchedule schedule = new EnemyRoleSchedule(0.10d, 0.10d);

        List<EnemyRole> roles = schedule.forWave(10L, 10, 10, false);

        assertEquals(10, roles.size());
        assertEquals(3L, roles.stream().filter(role -> role == EnemyRole.DESTROYER).count());
        assertEquals(3L, roles.stream().filter(role -> role == EnemyRole.BUILDER).count());
        assertEquals(4L, roles.stream().filter(role -> role == EnemyRole.NORMAL).count());
    }

    @Test
    void rejectsInvalidRoleScheduleInputs() {
        assertThrows(IllegalArgumentException.class, () -> new EnemyRoleSchedule(-0.1d, 0.1d));
        assertThrows(IllegalArgumentException.class, () -> new EnemyRoleSchedule(0.6d, 0.5d));

        EnemyRoleSchedule schedule = new EnemyRoleSchedule(0.1d, 0.1d);
        assertThrows(IllegalArgumentException.class, () -> schedule.forWave(0L, 1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> schedule.forWave(1L, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> schedule.forWave(1L, 1, 0, false));
    }
}
