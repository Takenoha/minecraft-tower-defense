package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.EnemyLifecycleSink;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and event-handler checks for the Kotlin event-enemy listener. */
class EventEnemyListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = EventEnemyListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(
                EventEnemyTagger.class,
                EnemyLifecycleSink.class,
                EnemyAccessPolicy.class,
                PaperEnemyTerrainAction.class,
                TowerEntityTagger.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertHandler(type, "onDamage", EntityDamageEvent.class, true);
        assertHandler(type, "onTarget", EntityTargetLivingEntityEvent.class, true);
        assertHandler(type, "onEntitiesLoad", EntitiesLoadEvent.class, false);
        assertHandler(type, "onBlockBreak", BlockBreakEvent.class, true);
        assertHandler(type, "onBlockPlace", BlockPlaceEvent.class, true);
        assertHandler(type, "onBucketEmpty", PlayerBucketEmptyEvent.class, true);
        assertHandler(type, "onBucketFill", PlayerBucketFillEvent.class, true);
        assertHandler(type, "onIgnite", BlockIgniteEvent.class, true);
        assertHandler(type, "onPistonExtend", BlockPistonExtendEvent.class, true);
        assertHandler(type, "onPistonRetract", BlockPistonRetractEvent.class, true);
        assertHandler(type, "onDeath", EntityDeathEvent.class, false);
        assertHandler(type, "onDropItem", EntityDropItemEvent.class, true);
        assertHandler(type, "onChangeBlock", EntityChangeBlockEvent.class, true);
        assertHandler(type, "onExplosion", EntityExplodeEvent.class, true);
        assertHandler(type, "onBlockExplosion", BlockExplodeEvent.class, true);
        assertHandler(type, "onPickupItem", EntityPickupItemEvent.class, true);
        assertHandler(type, "onPortal", EntityPortalEvent.class, true);
        assertHandler(type, "onTransform", EntityTransformEvent.class, true);

        Method piston = type.getDeclaredMethod(
                "pistonTouchesCombatArea", Block.class, List.class, BlockFace.class);
        assertTrue(Modifier.isPrivate(piston.getModifiers()));
        assertEquals(boolean.class, piston.getReturnType());

        Method responsible = type.getDeclaredMethod(
                "responsiblePlayer", EntityDamageByEntityEvent.class);
        assertTrue(Modifier.isPrivate(responsible.getModifiers()));
        assertTrue(Modifier.isStatic(responsible.getModifiers()));
        assertEquals(Player.class, responsible.getReturnType());
    }

    private static void assertHandler(
            Class<?> type,
            String name,
            Class<?> parameterType,
            boolean ignoreCancelled) throws Exception {
        Method method = type.getMethod(name, parameterType);
        assertEquals(void.class, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertTrue(handler != null, name);
        assertEquals(EventPriority.HIGHEST, handler.priority(), name);
        assertEquals(ignoreCancelled, handler.ignoreCancelled(), name);
    }
}
