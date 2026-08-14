package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin tower-item tagger. */
class TowerItemTaggerKotlinBoundaryAbiTest {
    @Test
    void constantsConstructorsMethodsAndStaticMaterialMappingRemainCompatible() throws Exception {
        assertEquals(1, TowerItemTagger.class.getField("ITEM_VERSION").getInt(null));
        assertPublicConstructor(TowerItemTagger.class, Plugin.class);
        assertMethod(TowerItemTagger.class, "recipeTemplate", ItemStack.class);
        assertMethod(TowerItemTagger.class, "recipeTemplate", ItemStack.class, TowerType.class);
        assertMethod(TowerItemTagger.class, "create", ItemStack.class, UUID.class, TowerType.class, int.class);
        assertMethod(
                TowerItemTagger.class,
                "create",
                ItemStack.class,
                UUID.class,
                TowerType.class,
                int.class,
                TowerTargetPriority.class);
        assertMethod(TowerItemTagger.class, "read", Optional.class, ItemStack.class);
        assertMethod(TowerItemTagger.class, "isRecipeTemplate", boolean.class, ItemStack.class);
        assertMethod(TowerItemTagger.class, "recipeType", Optional.class, ItemStack.class);
        assertMethod(TowerItemTagger.class, "hasTowerId", boolean.class, ItemStack.class, UUID.class);
        var materialFor = TowerItemTagger.class.getMethod("materialFor", TowerType.class);
        assertEquals(Material.class, materialFor.getReturnType());
        assertTrue(Modifier.isStatic(materialFor.getModifiers()));
        assertTrue(Modifier.isFinal(TowerItemTagger.class.getModifiers()));
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
