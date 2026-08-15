package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.RaidSealRepository;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and event-handler checks for the Kotlin raid-seal listener. */
class RaidSealListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = RaidSealListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Class<?>[] common = {
                JavaPlugin.class,
                RaidSealRepository.class,
                DatabaseExecutor.class,
                CoreRegistry.class,
                TowerDefenseCommand.class,
                RaidSealTagger.class,
        };
        Constructor<?> sixArgument = type.getConstructor(common);
        assertTrue(Modifier.isPublic(sixArgument.getModifiers()));

        Class<?>[] sevenArgument = {
                JavaPlugin.class,
                RaidSealRepository.class,
                DatabaseExecutor.class,
                CoreRegistry.class,
                TowerDefenseCommand.class,
                RaidSealTagger.class,
                TacticalBuildSelectionListener.class,
        };
        Constructor<?> sevenArgumentConstructor = type.getConstructor(sevenArgument);
        assertTrue(Modifier.isPublic(sevenArgumentConstructor.getModifiers()));

        Constructor<?> optionalConstructor = type.getDeclaredConstructor(
                JavaPlugin.class,
                RaidSealRepository.class,
                DatabaseExecutor.class,
                CoreRegistry.class,
                TowerDefenseCommand.class,
                RaidSealTagger.class,
                Optional.class);
        assertTrue(Modifier.isPrivate(optionalConstructor.getModifiers()));

        assertPublicMethod(type, "registerRecipe", void.class);
        assertHandler(type, "onCraft", CraftItemEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onCrafterCraft", CrafterCraftEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onJoin", PlayerJoinEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onCoreInteract", PlayerInteractEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onCoreGuiStart", InventoryClickEvent.class, EventPriority.LOWEST, false);

        assertPublicMethod(type, "hasPhysicalItem", boolean.class, UUID.class);
        assertPublicMethod(type, "removeMatchingItems", void.class, UUID.class);
        assertStaticMethod(
                type,
                "configureRecipe",
                ShapedRecipe.class,
                ShapedRecipe.class,
                Material.class);

        Method start = type.getDeclaredMethod(
                "startWithSelectedTacticalBuild",
                org.bukkit.entity.Player.class,
                UUID.class,
                long.class,
                UUID.class);
        assertTrue(Modifier.isPrivate(start.getModifiers()));
        assertEquals(void.class, start.getReturnType());

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

    private static void assertStaticMethod(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
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
