package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin tower particle-effect boundary. */
class TowerAttackEffectsKotlinBoundaryAbiTest {
    @Test
    void utilityAndNestedBudgetShapeRemainUsableFromJava() throws Exception {
        Class<?> utility = TowerAttackEffects.class;
        assertTrue(Modifier.isPublic(utility.getModifiers()));
        assertTrue(Modifier.isFinal(utility.getModifiers()));
        Constructor<?> utilityConstructor = utility.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(utilityConstructor.getModifiers()));

        Field maxEffects = utility.getField("MAX_EFFECTS_PER_ATTACK");
        assertTrue(Modifier.isPublic(maxEffects.getModifiers()));
        assertTrue(Modifier.isStatic(maxEffects.getModifiers()));
        assertTrue(Modifier.isFinal(maxEffects.getModifiers()));
        assertEquals(32, maxEffects.getInt(null));

        assertStaticMethod("definition", TowerEffectDefinition.class, TowerType.class);
        assertStaticMethod("newBudget", TowerAttackEffects.Budget.class);
        assertStaticMethod(
                "renderAttack",
                void.class,
                TowerType.class,
                Location.class,
                Location.class,
                TowerAttackEffects.Budget.class);
        assertStaticMethod(
                "renderHit",
                void.class,
                TowerType.class,
                Location.class,
                TowerAttackEffects.Budget.class);
        assertStaticMethod(
                "renderBuff",
                void.class,
                TowerType.class,
                Location.class,
                Location.class,
                TowerAttackEffects.Budget.class);
        assertStaticMethod("particleDataFor", Object.class, Particle.class);

        Class<?> budget = TowerAttackEffects.Budget.class;
        assertTrue(Modifier.isPublic(budget.getModifiers()));
        assertTrue(Modifier.isFinal(budget.getModifiers()));
        Constructor<?> budgetConstructor = budget.getDeclaredConstructor(int.class);
        assertTrue(Modifier.isPrivate(budgetConstructor.getModifiers()));
        assertEquals(int.class, budget.getMethod("remaining").getReturnType());
        assertEquals(boolean.class, budget.getMethod("claim").getReturnType());
    }

    private static void assertStaticMethod(
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = TowerAttackEffects.class.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }
}
