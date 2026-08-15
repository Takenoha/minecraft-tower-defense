package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and event-handler checks for the Kotlin core protection listener. */
class CoreProtectionListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = CoreProtectionListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(CoreRegistry.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertHandler(type, "onBreak", BlockBreakEvent.class);
        assertHandler(type, "onPistonExtend", BlockPistonExtendEvent.class);
        assertHandler(type, "onPistonRetract", BlockPistonRetractEvent.class);
        assertHandler(type, "onEntityExplosion", EntityExplodeEvent.class);
        assertHandler(type, "onBlockExplosion", BlockExplodeEvent.class);
        assertHandler(type, "onLiquid", BlockFromToEvent.class);
        assertHandler(type, "onEntityBlockChange", EntityChangeBlockEvent.class);
        assertHandler(type, "onBurn", BlockBurnEvent.class);
        assertHandler(type, "onFade", BlockFadeEvent.class);

        Method movesCore = type.getDeclaredMethod("movesCore", Iterable.class, BlockFace.class);
        assertTrue(Modifier.isPrivate(movesCore.getModifiers()));
        assertEquals(boolean.class, movesCore.getReturnType());
    }

    private static void assertHandler(Class<?> type, String name, Class<?> parameterType)
            throws Exception {
        Method method = type.getMethod(name, parameterType);
        assertEquals(void.class, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertTrue(handler != null, name);
        assertEquals(EventPriority.HIGHEST, handler.priority(), name);
        assertTrue(handler.ignoreCancelled(), name);
    }
}
