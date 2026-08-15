package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.BattleBoost;
import io.github.takenoha.towerdefense.persistence.BattleBoostKind;
import io.github.takenoha.towerdefense.persistence.BattleFunds;
import io.github.takenoha.towerdefense.persistence.BattleFundsState;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BattleRecordsKotlinBoundaryAbiTest {
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TOWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant UPDATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void preservesRecordComponentsAndCanonicalConstructors() throws Exception {
        assertRecord(BattleBoost.class,
                new Class<?>[]{UUID.class, UUID.class, UUID.class, BattleBoostKind.class,
                        int.class, double.class, Instant.class},
                "eventId", "teamId", "towerId", "kind", "level", "multiplier", "updatedAt");
        assertRecord(BattleFunds.class,
                new Class<?>[]{UUID.class, UUID.class, long.class, long.class, long.class,
                        BattleFundsState.class, Instant.class},
                "eventId", "teamId", "balance", "totalEarned", "totalSpent", "state",
                "updatedAt");
    }

    @Test
    void preservesValidationAndAccessorValues() {
        BattleBoost boost = new BattleBoost(
                EVENT_ID, TEAM_ID, TOWER_ID, BattleBoostKind.POWER, 2, 1.25d, UPDATED_AT);
        assertEquals(EVENT_ID, boost.eventId());
        assertEquals(TEAM_ID, boost.teamId());
        assertEquals(TOWER_ID, boost.towerId());
        assertEquals(BattleBoostKind.POWER, boost.kind());
        assertEquals(2, boost.level());
        assertEquals(1.25d, boost.multiplier());
        assertEquals(UPDATED_AT, boost.updatedAt());

        IllegalArgumentException invalidBoost = assertThrows(IllegalArgumentException.class,
                () -> new BattleBoost(EVENT_ID, TEAM_ID, TOWER_ID, BattleBoostKind.POWER,
                        0, 1.25d, UPDATED_AT));
        assertEquals("battle boost values are invalid", invalidBoost.getMessage());
        IllegalArgumentException invalidMultiplier = assertThrows(IllegalArgumentException.class,
                () -> new BattleBoost(EVENT_ID, TEAM_ID, TOWER_ID, BattleBoostKind.POWER,
                        1, Double.NaN, UPDATED_AT));
        assertEquals("battle boost values are invalid", invalidMultiplier.getMessage());

        BattleFunds funds = new BattleFunds(
                EVENT_ID, TEAM_ID, 10L, 25L, 15L, BattleFundsState.ACTIVE, UPDATED_AT);
        assertEquals(10L, funds.balance());
        assertEquals(25L, funds.totalEarned());
        assertEquals(15L, funds.totalSpent());
        assertEquals(BattleFundsState.ACTIVE, funds.state());

        IllegalArgumentException negativeFunds = assertThrows(IllegalArgumentException.class,
                () -> new BattleFunds(EVENT_ID, TEAM_ID, -1L, 0L, 0L,
                        BattleFundsState.ACTIVE, UPDATED_AT));
        assertEquals("battle funds totals must not be negative", negativeFunds.getMessage());
        IllegalArgumentException overspent = assertThrows(IllegalArgumentException.class,
                () -> new BattleFunds(EVENT_ID, TEAM_ID, 0L, 1L, 2L,
                        BattleFundsState.ACTIVE, UPDATED_AT));
        assertEquals("totalSpent cannot exceed totalEarned", overspent.getMessage());
        IllegalArgumentException settledBalance = assertThrows(IllegalArgumentException.class,
                () -> new BattleFunds(EVENT_ID, TEAM_ID, 1L, 1L, 0L,
                        BattleFundsState.SETTLED, UPDATED_AT));
        assertEquals("settled battle funds must have zero balance", settledBalance.getMessage());
    }

    private static void assertRecord(Class<?> type, Class<?>[] componentTypes, String... names)
            throws Exception {
        assertTrue(type.isRecord(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
        assertEquals(List.of(names), Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(names.length, componentTypes.length);
        assertNotNull(type.getConstructor(componentTypes));
        for (int i = 0; i < names.length; i++) {
            var accessor = type.getMethod(names[i]);
            assertEquals(componentTypes[i], accessor.getReturnType(), names[i]);
            assertTrue(Modifier.isPublic(accessor.getModifiers()), names[i]);
        }
    }
}
