package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.persistence.CorePlacement;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and event-boundary checks for the Kotlin core item listener. */
class CoreItemListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = CoreItemListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(
                JavaPlugin.class,
                PluginSettings.class,
                DefenseRepository.class,
                DatabaseExecutor.class,
                DefenseSessionManager.class,
                CoreRegistry.class,
                ThirdPartyRegionProtectionAdapter.class,
                CoreItemTagger.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertPublicVoid(type, "reconcileRegisteredCoreBlocks");
        assertPublicVoid(type, "registerRecipe");
        assertPublicVoid(type, "recoverPreparedPlacements");
        assertHandler(type, "onCraft", CraftItemEvent.class);
        assertHandler(type, "onJoin", PlayerJoinEvent.class);
        assertHandler(type, "onInteract", PlayerInteractEvent.class);

        Method relocation = type.getMethod(
                "beginGuiRelocation", Player.class, Block.class, CoreRecord.class);
        assertTrue(Modifier.isPublic(relocation.getModifiers()));
        assertEquals(void.class, relocation.getReturnType());

        Method configure = type.getMethod("configureRecipe", ShapedRecipe.class);
        assertTrue(Modifier.isPublic(configure.getModifiers()));
        assertTrue(Modifier.isStatic(configure.getModifiers()));
        assertEquals(ShapedRecipe.class, configure.getReturnType());

        assertPrivateStatic(type, "isStillOriginal", boolean.class, Block.class, String.class);
        assertPrivateStatic(type, "restore", void.class, Block.class, String.class);
        assertPrivateStatic(type, "soloTeamId", UUID.class, UUID.class);
        assertPrivateStatic(type, "rootMessage", String.class, Throwable.class);

        assertPrivate(type, "beginPlacement", Player.class, Block.class,
                CoreItemIdentity.class, boolean.class);
        assertPrivate(type, "preparePlan", UUID.class, CoreItemIdentity.class, UUID.class,
                int.class, int.class, int.class, String.class);
        assertPrivate(type, "applyPhysicalBlock", Player.class, Block.class, CorePlacement.class,
                UUID.class, boolean.class);
        Class<?> relocationState = Class.forName(
                "io.github.takenoha.towerdefense.paper.CoreItemListener$RelocationPhysicalState");
        assertPrivate(type, "rollbackPrepared", Player.class, Block.class, CorePlacement.class,
                relocationState, String.class);
    }

    private static void assertPublicVoid(Class<?> type, String name) throws Exception {
        Method method = type.getMethod(name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertEquals(void.class, method.getReturnType(), name);
    }

    private static void assertHandler(Class<?> type, String name, Class<?> parameterType)
            throws Exception {
        Method method = type.getMethod(name, parameterType);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertEquals(void.class, method.getReturnType(), name);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertTrue(handler != null, name);
        assertEquals(EventPriority.HIGHEST, handler.priority(), name);
        assertTrue(handler.ignoreCancelled(), name);
    }

    private static void assertPrivateStatic(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isPrivate(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
        assertEquals(returnType, method.getReturnType(), name);
    }

    private static void assertPrivate(Class<?> type, String name, Class<?>... parameterTypes)
            throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isPrivate(method.getModifiers()), name);
    }
}
