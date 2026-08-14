package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and invariant checks for the Kotlin particle definition record. */
class TowerEffectDefinitionKotlinBoundaryAbiTest {
    @Test
    void recordShapeAndCanonicalConstructorRemainCompatible() throws Exception {
        Class<?> type = TowerEffectDefinition.class;
        assertTrue(type.isRecord());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        String[] names = {
            "type", "trail", "hit", "buff", "trailCount", "hitCount", "buffCount"
        };
        RecordComponent[] components = type.getRecordComponents();
        assertEquals(names.length, components.length);
        for (int index = 0; index < names.length; index++) {
            assertEquals(names[index], components[index].getName());
            assertEquals(components[index].getType(), type.getMethod(names[index]).getReturnType());
        }
        Constructor<?> constructor = type.getDeclaredConstructor(
                TowerType.class,
                Particle.class,
                Particle.class,
                Particle.class,
                int.class,
                int.class,
                int.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
    }

    @Test
    void positiveParticleCountValidationRemainsCompatible() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TowerEffectDefinition(
                        TowerType.ARROW,
                        Particle.CRIT,
                        Particle.DAMAGE_INDICATOR,
                        Particle.ENCHANTED_HIT,
                        0,
                        1,
                        1));
        TowerEffectDefinition definition = new TowerEffectDefinition(
                TowerType.ARROW,
                Particle.CRIT,
                Particle.DAMAGE_INDICATOR,
                Particle.ENCHANTED_HIT,
                1,
                3,
                2);
        assertEquals(TowerType.ARROW, definition.type());
        assertEquals(3, definition.hitCount());
    }
}
