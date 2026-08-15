package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.EscrowDrop;
import io.github.takenoha.towerdefense.persistence.EscrowRepository;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.runtime.ActionBarBroker;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper escrow drop manager. */
class PaperEscrowDropManagerKotlinBoundaryAbiTest {
    @Test
    void managerBoundaryRemainsCompatible() throws Exception {
        Class<?> type = PaperEscrowDropManager.class;
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Constructor<?> fourArgument = type.getConstructor(
                Plugin.class,
                EscrowRepository.class,
                DatabaseExecutor.class,
                EscrowDropTagger.class);
        assertTrue(Modifier.isPublic(fourArgument.getModifiers()));

        Constructor<?> fiveArgument = type.getConstructor(
                Plugin.class,
                EscrowRepository.class,
                DatabaseExecutor.class,
                EscrowDropTagger.class,
                ResourceRepository.class);
        assertTrue(Modifier.isPublic(fiveArgument.getModifiers()));

        assertPublicMethod(
                type,
                "prepareBlockDrops",
                List.class,
                UUID.class,
                UUID.class,
                Block.class,
                Instant.class);
        assertPublicMethod(
                type,
                "spawnPreparedDrops",
                void.class,
                Block.class,
                List.class);
        assertPublicMethod(
                type,
                "issueEnemyDrop",
                void.class,
                UUID.class,
                UUID.class,
                Location.class,
                String.class,
                ItemStack.class,
                Instant.class);
        assertPublicMethod(
                type,
                "discardPreparedDrops",
                void.class,
                List.class,
                Instant.class);
        assertPublicMethod(type, "readyForTerminal", boolean.class, UUID.class);
        assertPublicMethod(type, "beginTerminal", boolean.class, UUID.class);
        assertPublicMethod(
                type,
                "handlePickup",
                void.class,
                EntityPickupItemEvent.class);
        assertPublicMethod(type, "removeEventDisplays", void.class, UUID.class);
        assertPublicMethod(type, "removeAllTaggedDisplays", void.class);
        assertPublicMethod(type, "removeStaleDisplays", void.class, Chunk.class);
        assertPublicMethod(type, "tagger", EscrowDropTagger.class);
        assertPublicMethod(type, "actionBarBroker", ActionBarBroker.class);

        Class<?> preparedDrop = PaperEscrowDropManager.PreparedDrop.class;
        assertTrue(preparedDrop.isRecord());
        assertTrue(Modifier.isPublic(preparedDrop.getModifiers()));
        assertTrue(Modifier.isFinal(preparedDrop.getModifiers()));
        Constructor<?> preparedDropConstructor = preparedDrop.getConstructor(
                EscrowDrop.class,
                ItemStack.class);
        assertTrue(Modifier.isPublic(preparedDropConstructor.getModifiers()));
        assertPublicMethod(preparedDrop, "drop", EscrowDrop.class);
        assertPublicMethod(preparedDrop, "itemStack", ItemStack.class);

        assertPrivateStatic(type, "deterministic", UUID.class, UUID.class, String.class, String.class);
        assertPrivateStatic(type, "requireMainThread", void.class);
        assertPrivate(type, "findDisplay", Optional.class, UUID.class);
        assertPrivate(type, "spawnPreparedDrop", void.class, Location.class, preparedDrop);
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
