package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and event-handler checks for the Kotlin protected-block listener. */
class ProtectedBlockListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = ProtectedBlockListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(CoreRegistry.class, EnemyAccessPolicy.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertHandler(type, "onBreak", BlockBreakEvent.class);
        assertHandler(type, "onPlace", BlockPlaceEvent.class);
        assertHandler(type, "onPistonExtend", BlockPistonExtendEvent.class);
        assertHandler(type, "onPistonRetract", BlockPistonRetractEvent.class);
        assertHandler(type, "onEntityExplosion", EntityExplodeEvent.class);
        assertHandler(type, "onBlockExplosion", BlockExplodeEvent.class);
        assertHandler(type, "onLiquid", BlockFromToEvent.class);
        assertHandler(type, "onGrow", BlockGrowEvent.class);
        assertHandler(type, "onFade", BlockFadeEvent.class);
        assertHandler(type, "onBurn", BlockBurnEvent.class);
        assertHandler(type, "onIgnite", BlockIgniteEvent.class);
        assertHandler(type, "onPhysics", BlockPhysicsEvent.class);
        assertHandler(type, "onEntityChangeBlock", EntityChangeBlockEvent.class);

        Method movesProtected = type.getDeclaredMethod(
                "movesProtected", Block.class, List.class, BlockFace.class);
        assertTrue(Modifier.isPrivate(movesProtected.getModifiers()));
        assertEquals(boolean.class, movesProtected.getReturnType());

        Method isProtectedTarget = type.getDeclaredMethod(
                "isProtectedTarget", Block.class, BlockState.class);
        assertTrue(Modifier.isPrivate(isProtectedTarget.getModifiers()));
        assertEquals(boolean.class, isProtectedTarget.getReturnType());
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
