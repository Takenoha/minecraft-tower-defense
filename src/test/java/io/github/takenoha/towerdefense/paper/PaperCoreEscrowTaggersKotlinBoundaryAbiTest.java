package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.EscrowDrop;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin core and escrow taggers. */
class PaperCoreEscrowTaggersKotlinBoundaryAbiTest {
    @Test
    void coreItemTaggerKeepsPublicBoundary() throws Exception {
        assertPublicConstructor(CoreItemTagger.class, Plugin.class);
        assertMethod(CoreItemTagger.class, "recipeTemplate", ItemStack.class);
        assertMethod(CoreItemTagger.class, "createUnbound", ItemStack.class, UUID.class);
        assertMethod(CoreItemTagger.class, "createBound", ItemStack.class, UUID.class, UUID.class);
        assertMethod(CoreItemTagger.class, "createBound", ItemStack.class, UUID.class, UUID.class, UUID.class);
        assertMethod(CoreItemTagger.class, "read", Optional.class, ItemStack.class);
        assertMethod(CoreItemTagger.class, "isRecipeTemplate", boolean.class, ItemStack.class);
        assertMethod(CoreItemTagger.class, "hasItemId", boolean.class, ItemStack.class, UUID.class);
        assertEquals(1, CoreItemTagger.class.getField("ITEM_VERSION").getInt(null));
        assertTrue(Modifier.isFinal(CoreItemTagger.class.getModifiers()));
    }

    @Test
    void escrowDropTaggerKeepsEntityAndStackOverloads() throws Exception {
        assertPublicConstructor(EscrowDropTagger.class, Plugin.class);
        assertMethod(EscrowDropTagger.class, "tag", void.class, Item.class, EscrowDrop.class);
        assertMethod(EscrowDropTagger.class, "tag", void.class, Entity.class, TaggedEscrowDrop.class);
        assertMethod(EscrowDropTagger.class, "tag", ItemStack.class, ItemStack.class, EscrowDrop.class);
        assertMethod(EscrowDropTagger.class, "read", Optional.class, Entity.class);
        assertMethod(EscrowDropTagger.class, "read", Optional.class, ItemStack.class);
        assertMethod(EscrowDropTagger.class, "read", Optional.class, Item.class);
        assertMethod(EscrowDropTagger.class, "isTagged", boolean.class, ItemStack.class);
        assertTrue(Modifier.isFinal(EscrowDropTagger.class.getModifiers()));
    }

    private static void assertPublicConstructor(Class<?> type, Class<?>... parameterTypes) throws Exception {
        var constructor = type.getDeclaredConstructor(parameterTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()), type.getName());
    }

    private static void assertMethod(
            Class<?> type, String name, Class<?> returnType, Class<?>... parameterTypes) throws Exception {
        var method = type.getMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
    }
}
