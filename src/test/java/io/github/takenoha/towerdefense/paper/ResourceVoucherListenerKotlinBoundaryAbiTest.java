package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.persistence.ResourceVoucherRepository;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and EventHandler checks for the Kotlin resource voucher listener. */
class ResourceVoucherListenerKotlinBoundaryAbiTest {
    @Test
    void listenerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = ResourceVoucherListener.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Listener.class.isAssignableFrom(type));

        Constructor<?> constructor = type.getConstructor(
                JavaPlugin.class,
                DefenseRepository.class,
                DatabaseExecutor.class,
                DefenseSessionManager.class,
                CoreRegistry.class,
                ResourceRepository.class,
                ResourceVoucherRepository.class,
                ResourceVoucherTagger.class);
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        assertHandler(type, "onVoucherCoreInteract", EventPriority.LOWEST, true, PlayerInteractEvent.class);
        assertHandler(type, "onVaultClick", EventPriority.NORMAL, false, InventoryClickEvent.class);
        assertHandler(type, "onVoucherJoin", EventPriority.NORMAL, false, PlayerJoinEvent.class);
        assertHandler(type, "onVoucherRespawn", EventPriority.NORMAL, false, PlayerRespawnEvent.class);
        assertHandler(type, "onVoucherQuit", EventPriority.NORMAL, false, PlayerQuitEvent.class);
        assertHandler(type, "onVoucherHeldChange", EventPriority.HIGHEST, true, PlayerItemHeldEvent.class);
        assertHandler(type, "onVoucherInventoryClick", EventPriority.HIGHEST, true, InventoryClickEvent.class);
        assertHandler(type, "onVoucherInventoryDrag", EventPriority.HIGHEST, true, InventoryDragEvent.class);
        assertHandler(type, "onVoucherInventoryMove", EventPriority.HIGHEST, true, InventoryMoveItemEvent.class);
        assertHandler(type, "onVoucherInventoryPickup", EventPriority.HIGHEST, true, InventoryPickupItemEvent.class);
        assertHandler(type, "onVoucherEntityPickup", EventPriority.HIGHEST, true, EntityPickupItemEvent.class);
        assertHandler(type, "onVoucherDrop", EventPriority.HIGHEST, true, PlayerDropItemEvent.class);
        assertHandler(type, "onVoucherCraft", EventPriority.HIGHEST, true, CraftItemEvent.class);
        assertHandler(type, "onVoucherCrafter", EventPriority.HIGHEST, true, CrafterCraftEvent.class);
        assertHandler(type, "onVoucherSmith", EventPriority.HIGHEST, true, SmithItemEvent.class);
        assertHandler(type, "onVoucherPrepareSmithing", EventPriority.HIGHEST, true, PrepareSmithingEvent.class);
        assertHandler(type, "onVoucherPrepareAnvil", EventPriority.HIGHEST, true, PrepareAnvilEvent.class);
        assertHandler(type, "onVoucherPrepareGrindstone", EventPriority.HIGHEST, true, PrepareGrindstoneEvent.class);
        assertHandler(type, "onVoucherPlace", EventPriority.HIGHEST, true, BlockPlaceEvent.class);
        assertHandler(type, "onVoucherDispense", EventPriority.HIGHEST, true, BlockDispenseEvent.class);
        assertHandler(type, "onVoucherConsume", EventPriority.HIGHEST, true, PlayerItemConsumeEvent.class);
        assertHandler(type, "onVoucherInteract", EventPriority.HIGH, true, PlayerInteractEvent.class);
        assertHandler(type, "onVoucherInteractEntity", EventPriority.HIGHEST, true, PlayerInteractEntityEvent.class);
        assertHandler(type, "onVoucherInteractAtEntity", EventPriority.HIGHEST, true, PlayerInteractAtEntityEvent.class);
        assertHandler(type, "onVoucherArmorStandManipulate", EventPriority.HIGHEST, true,
                PlayerArmorStandManipulateEvent.class);
        assertHandler(type, "onVoucherHangingBreak", EventPriority.HIGHEST, true, HangingBreakEvent.class);
        assertHandler(type, "onVoucherSwapHands", EventPriority.HIGHEST, true, PlayerSwapHandItemsEvent.class);
        assertHandler(type, "onVoucherDeath", EventPriority.HIGHEST, true, PlayerDeathEvent.class);
        assertHandler(type, "onVoucherMerge", EventPriority.HIGHEST, true, ItemMergeEvent.class);
        assertHandler(type, "onVoucherDespawn", EventPriority.HIGHEST, true, ItemDespawnEvent.class);
        assertHandler(type, "onVoucherDamage", EventPriority.HIGHEST, true, EntityDamageEvent.class);
        assertHandler(type, "onVoucherPortal", EventPriority.HIGHEST, true, EntityPortalEvent.class);
        assertHandler(type, "onVoucherTeleport", EventPriority.HIGHEST, true, EntityTeleportEvent.class);

        assertPrivateStatic(type, "onlinePlayer", org.bukkit.entity.Player.class, UUID.class);
        assertPrivateStatic(type, "deterministic", UUID.class, UUID.class, String.class);
        assertPrivateStatic(type, "isForbiddenVoucherInventory", boolean.class, InventoryType.class);
        assertPrivateStatic(type, "rootMessage", String.class, Throwable.class);
        assertPrivate(type, "runOnMainThread", void.class, Runnable.class);
        assertPrivate(type, "openVault", void.class, org.bukkit.entity.Player.class, UUID.class);
    }

    private static void assertHandler(
            Class<?> type,
            String name,
            EventPriority priority,
            boolean ignoreCancelled,
            Class<?> parameterType) throws Exception {
        Method method = type.getMethod(name, parameterType);
        assertEquals(void.class, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        EventHandler annotation = method.getAnnotation(EventHandler.class);
        assertNotNull(annotation, name);
        assertEquals(priority, annotation.priority(), name);
        assertEquals(ignoreCancelled, annotation.ignoreCancelled(), name);
    }

    private static void assertPrivateStatic(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPrivate(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }

    private static void assertPrivate(
            Class<?> type,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPrivate(method.getModifiers()), name);
        assertTrue(!Modifier.isStatic(method.getModifiers()), name);
    }
}
