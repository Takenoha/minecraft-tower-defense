package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.OptionalLong;
import java.lang.reflect.Proxy;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper recipe catalogs. */
class PaperRecipeCatalogsKotlinBoundaryAbiTest {
    @Test
    void catalogUtilityShapesAndMethodsRemainJavaCompatible() throws Exception {
        assertUtilityClass(TowerRecipeCatalog.class);
        assertUtilityClass(RaidSealCatalog.class);

        assertStaticMethod("recipeKeySuffix", String.class, TowerType.class);
        assertStaticMethod("key", NamespacedKey.class, Plugin.class, TowerType.class);
        assertStaticMethod("keys", List.class, Plugin.class);
        assertStaticMethod("discoverAll", int.class, Plugin.class, Player.class);

        assertStaticMethod("recipeStages", List.class);
        assertStaticMethod("ingredientNameFor", String.class, long.class);
        assertStaticMethod("stageAtSlot", OptionalLong.class, int.class);
        assertStaticMethod("slotForStage", int.class, long.class);

        Field maxStage = RaidSealCatalog.class.getField("MAX_RECIPE_STAGE_LEVEL");
        assertTrue(Modifier.isPublic(maxStage.getModifiers()));
        assertTrue(Modifier.isStatic(maxStage.getModifiers()));
        assertTrue(Modifier.isFinal(maxStage.getModifiers()));
        assertEquals(10L, maxStage.getLong(null));
    }

    @Test
    void catalogMappingsRemainStable() {
        assertEquals("tower_arrow", TowerRecipeCatalog.recipeKeySuffix(TowerType.ARROW));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                RaidSealCatalog.recipeStages());
        assertEquals(OptionalLong.of(9L), RaidSealCatalog.stageAtSlot(16));
        assertEquals(OptionalLong.of(10L), RaidSealCatalog.stageAtSlot(17));
        assertEquals(OptionalLong.empty(), RaidSealCatalog.stageAtSlot(9));
        assertEquals(16, RaidSealCatalog.slotForStage(9L));
        assertEquals(17, RaidSealCatalog.slotForStage(10L));
    }

    @Test
    void returnedCatalogListsRemainUnmodifiable() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getName")
                            || method.getName().equals("namespace")) {
                        return "minecraft-tower-defense";
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        assertThrows(UnsupportedOperationException.class, () -> TowerRecipeCatalog.keys(plugin).clear());
        assertThrows(UnsupportedOperationException.class, () -> RaidSealCatalog.recipeStages().clear());
    }

    private static void assertUtilityClass(Class<?> type) throws Exception {
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        Constructor<?> constructor = type.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }

    private static void assertStaticMethod(
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        Method method = findMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType(), name);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(Modifier.isStatic(method.getModifiers()), name);
    }

    private static Method findMethod(String name, Class<?>... parameterTypes) throws Exception {
        try {
            return TowerRecipeCatalog.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return RaidSealCatalog.class.getMethod(name, parameterTypes);
        }
    }
}
