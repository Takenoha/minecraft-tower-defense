package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class TacticalModelsKotlinBoundaryAbiTest {
    @Test
    void preservesEnumNamesAndOrder() {
        assertEquals(
                List.of("OFFENSE", "RANGE", "SIEGE", "CONTROL", "SUPPORT", "DEFENSE"),
                java.util.Arrays.stream(TacticalBuildCategory.values())
                        .map(Enum::name)
                        .toList());
        assertEquals(
                List.of("COMMON", "RARE", "EPIC"),
                java.util.Arrays.stream(TacticalBuildRarity.values())
                        .map(Enum::name)
                        .toList());
        assertEquals(17, TacticalEffectType.values().length);
        assertEquals("DAMAGE_TO_BURNING_TARGET_MULTIPLIER",
                TacticalEffectType.values()[16].name());
        assertEquals(
                List.of("NONE", "CORE_BELOW_50_PERCENT", "CORE_BELOW_30_PERCENT", "BOSS",
                        "HIGH_HP", "LOW_HP", "SLOWED", "BURNING"),
                java.util.Arrays.stream(TacticalTargetCondition.values())
                        .map(Enum::name)
                        .toList());
    }

    @Test
    void preservesJvmRecordsCandidateValidationAndAccessors() throws Exception {
        assertRecord(TacticalCandidate.class, "slot", int.class);
        assertRecord(TacticalCandidate.class, "definition", TacticalBuildDefinition.class);

        var constructor = TacticalCandidate.class.getConstructor(
                int.class, TacticalBuildDefinition.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
        assertEquals(int.class, TacticalCandidate.class.getMethod("slot").getReturnType());
        assertEquals(TacticalBuildDefinition.class,
                TacticalCandidate.class.getMethod("definition").getReturnType());
        assertThrowsIllegalArgument(() -> constructor.newInstance(-1,
                TacticalBuildCatalog.defaults().require("rapid-fire")));
        assertThrowsIllegalArgument(() -> constructor.newInstance(3,
                TacticalBuildCatalog.defaults().require("rapid-fire")));
    }

    @Test
    void preservesTargetContextRecordHelpersAndValidation() throws Exception {
        assertRecord(TacticalTargetContext.class, "targetHealthFraction", double.class);
        assertRecord(TacticalTargetContext.class, "coreHealthFraction", double.class);
        assertRecord(TacticalTargetContext.class, "boss", boolean.class);
        assertRecord(TacticalTargetContext.class, "slowed", boolean.class);
        assertRecord(TacticalTargetContext.class, "burning", boolean.class);

        var neutral = TacticalTargetContext.neutral();
        assertEquals(1.0, neutral.targetHealthFraction());
        assertEquals(1.0, neutral.coreHealthFraction());
        assertFalse(neutral.boss());
        assertTrue(new TacticalTargetContext(0.8, 0.2, true, true, false)
                .targetHasHighHealth());
        assertTrue(new TacticalTargetContext(0.3, 0.2, true, true, false)
                .targetHasLowHealth());
        assertTrue(new TacticalTargetContext(0.3, 0.2, true, true, false)
                .coreBelowHalf());
        assertTrue(new TacticalTargetContext(0.3, 0.2, true, true, false)
                .coreBelowThirtyPercent());

        var constructor = TacticalTargetContext.class.getConstructor(
                double.class, double.class, boolean.class, boolean.class, boolean.class);
        assertThrowsIllegalArgument(() -> constructor.newInstance(Double.NaN, 1.0,
                false, false, false));
        assertThrowsIllegalArgument(() -> constructor.newInstance(1.0, 1.1,
                false, false, false));
    }

    private static void assertRecord(Class<?> type, String name, Class<?> componentType) {
        assertTrue(type.isRecord());
        assertEquals(Record.class, type.getSuperclass());
        var component = java.util.Arrays.stream(type.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
        assertEquals(componentType, component.getType());
        assertTrue(Modifier.isPublic(component.getAccessor().getModifiers()));
    }

    private static void assertThrowsIllegalArgument(ThrowingAction action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (ReflectiveOperationException reflection) {
            if (reflection.getCause() instanceof IllegalArgumentException) {
                return;
            }
            throw new AssertionError(reflection);
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws ReflectiveOperationException;
    }
}
