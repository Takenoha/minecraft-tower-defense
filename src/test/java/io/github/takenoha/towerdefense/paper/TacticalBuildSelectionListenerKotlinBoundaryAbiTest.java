package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.TacticalBuildRepository;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.tactical.TacticalBuildCatalog;
import io.github.takenoha.towerdefense.tactical.TacticalCandidateGenerator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and event-handler checks for the Kotlin tactical selection listener. */
class TacticalBuildSelectionListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = TacticalBuildSelectionListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(
                JavaPlugin.class,
                DefenseRepository.class,
                TacticalBuildRepository.class,
                DatabaseExecutor.class,
                TacticalBuildCatalog.class,
                TacticalCandidateGenerator.class,
                TowerDefenseCommand.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertPublicMethod(type, "beginSelection", void.class,
                Player.class, UUID.class, long.class, UUID.class);
        assertHandler(type, "onClick", InventoryClickEvent.class, EventPriority.HIGHEST, false);
        assertHandler(type, "onDrag", InventoryDragEvent.class, EventPriority.HIGHEST, false);
        assertHandler(type, "onClose", InventoryCloseEvent.class, EventPriority.MONITOR, false);

        Method cancel = type.getDeclaredMethod(
                "cancelAfterSelectionFailure",
                TacticalBuildSelectionInventoryHolder.class,
                Player.class,
                Throwable.class);
        assertTrue(Modifier.isPrivate(cancel.getModifiers()));
        assertEquals(void.class, cancel.getReturnType());

        Method mainThread = type.getDeclaredMethod("runOnMainThread", Runnable.class);
        assertTrue(Modifier.isPrivate(mainThread.getModifiers()));
        assertEquals(void.class, mainThread.getReturnType());

        Method rootMessage = type.getDeclaredMethod("rootMessage", Throwable.class);
        assertTrue(Modifier.isPrivate(rootMessage.getModifiers()));
        assertTrue(Modifier.isStatic(rootMessage.getModifiers()));
        assertEquals(String.class, rootMessage.getReturnType());
    }

    private static void assertPublicMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
    }

    private static void assertHandler(
            Class<?> type,
            String name,
            Class<?> parameterType,
            EventPriority priority,
            boolean ignoreCancelled) throws Exception {
        Method method = type.getMethod(name, parameterType);
        assertEquals(void.class, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertTrue(handler != null, name);
        assertEquals(priority, handler.priority(), name);
        assertEquals(ignoreCancelled, handler.ignoreCancelled(), name);
    }
}
