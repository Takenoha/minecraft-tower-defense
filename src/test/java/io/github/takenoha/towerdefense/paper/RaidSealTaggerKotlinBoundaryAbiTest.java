package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.StageWaveSchedule;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin raid-seal tagger. */
class RaidSealTaggerKotlinBoundaryAbiTest {
    @Test
    void constantsConstructorsAndMethodsRemainJavaCompatible() throws Exception {
        assertEquals(1, RaidSealTagger.class.getField("ITEM_VERSION").getInt(null));
        assertEquals(1L, RaidSealTagger.class.getField("FOUNDATION_STAGE").getLong(null));
        assertEquals(
                Material.valueOf(RaidSealMaterialPolicy.CURRENT_MATERIAL),
                RaidSealTagger.class.getField("ITEM_MATERIAL").get(null));
        assertEquals(
                Material.valueOf(RaidSealMaterialPolicy.LEGACY_MATERIAL),
                RaidSealTagger.class.getField("LEGACY_ITEM_MATERIAL").get(null));
        assertPublicConstructor(RaidSealTagger.class, Plugin.class);
        assertMethod(RaidSealTagger.class, "recipeTemplate", ItemStack.class);
        assertMethod(RaidSealTagger.class, "recipeTemplate", ItemStack.class, long.class);
        assertMethod(RaidSealTagger.class, "create", ItemStack.class, UUID.class, long.class);
        assertMethod(RaidSealTagger.class, "read", Optional.class, ItemStack.class);
        assertMethod(RaidSealTagger.class, "isLegacyMaterial", boolean.class, ItemStack.class);
        assertMethod(RaidSealTagger.class, "isRecipeTemplate", boolean.class, ItemStack.class);
        assertMethod(RaidSealTagger.class, "templateStage", OptionalLong.class, ItemStack.class);
        assertMethod(RaidSealTagger.class, "hasSealId", boolean.class, ItemStack.class, UUID.class);
        assertTrue(Modifier.isFinal(RaidSealTagger.class.getModifiers()));
    }

    @Test
    void stageValidationBoundaryRemainsAvailable() {
        assertEquals(1L, StageWaveSchedule.requireValidStageLevel(1L));
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
