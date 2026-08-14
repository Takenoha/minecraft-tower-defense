package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.TowerSettings;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.domain.TowerResearch;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin tower research GUI builder. */
class TowerResearchGuiKotlinBoundaryAbiTest {
    @Test
    void guiBoundaryRemainsCompatible() throws Exception {
        Class<?> type = TowerResearchGui.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));

        assertConstant(type, "SIZE", 27);
        assertConstant(type, "RESEARCH_START_SLOT", 10);
        assertConstant(type, "CLOSE_SLOT", 22);

        Method create = type.getMethod(
                "create", UUID.class, TeamProgress.class, List.class, TowerSettings.class);
        assertEquals(Inventory.class, create.getReturnType());
        assertTrue(Modifier.isPublic(create.getModifiers()));
        assertTrue(Modifier.isStatic(create.getModifiers()));

        Method towerTypeAt = type.getMethod("towerTypeAt", int.class);
        assertEquals(Optional.class, towerTypeAt.getReturnType());
        assertTrue(Modifier.isPublic(towerTypeAt.getModifiers()));
        assertTrue(Modifier.isStatic(towerTypeAt.getModifiers()));
    }

    private static void assertConstant(Class<?> type, String name, int expected) throws Exception {
        Field field = type.getField(name);
        assertEquals(int.class, field.getType(), name);
        assertTrue(Modifier.isPublic(field.getModifiers()), name);
        assertTrue(Modifier.isStatic(field.getModifiers()), name);
        assertTrue(Modifier.isFinal(field.getModifiers()), name);
        assertEquals(expected, field.getInt(null), name);
    }
}
