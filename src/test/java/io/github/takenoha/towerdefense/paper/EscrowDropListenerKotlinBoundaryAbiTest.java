package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and event-handler checks for the Kotlin escrow drop listener. */
class EscrowDropListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = EscrowDropListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(PaperEscrowDropManager.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertHandler(type, "onPickup", EntityPickupItemEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onChunkLoad", ChunkLoadEvent.class, EventPriority.MONITOR, true);
        assertHandler(type, "onInventoryPickup", InventoryPickupItemEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onInventoryMove", InventoryMoveItemEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onInventoryClick", InventoryClickEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onInventoryDrag", InventoryDragEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onPlayerDrop", PlayerDropItemEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onPlayerDeath", PlayerDeathEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onCraft", CraftItemEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onDispense", BlockDispenseEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onPlace", BlockPlaceEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onInteract", PlayerInteractEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onConsume", PlayerItemConsumeEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onInteractEntity", PlayerInteractEntityEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onItemMerge", ItemMergeEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onItemDespawn", ItemDespawnEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onItemDamage", EntityDamageEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onItemPortal", EntityPortalEvent.class, EventPriority.HIGHEST, true);
        assertHandler(type, "onItemTeleport", EntityTeleportEvent.class, EventPriority.HIGHEST, true);
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
