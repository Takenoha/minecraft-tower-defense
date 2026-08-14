package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.CorePlacement;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin Paper core/reward taggers. */
class PaperTaggerCoreKotlinBoundaryAbiTest {
    @Test
    void constructorsAndMethodsKeepJavaDescriptors() throws Exception {
        assertPublicConstructor(CoreBlockTagger.class, Plugin.class);
        assertMethod(CoreBlockTagger.class, "tag", boolean.class, Block.class, CorePlacement.class);
        assertMethod(CoreBlockTagger.class, "matches", boolean.class, Block.class, CorePlacement.class);

        assertPublicConstructor(DefenseShardTagger.class, Plugin.class);
        assertMethod(DefenseShardTagger.class, "create", ItemStack.class, UUID.class, int.class);
        assertMethod(DefenseShardTagger.class, "isShard", boolean.class, ItemStack.class);
        assertEquals(1, DefenseShardTagger.class.getField("ITEM_VERSION").getInt(null));

        assertPublicConstructor(EnhancementCoreTagger.class, Plugin.class);
        assertMethod(EnhancementCoreTagger.class, "create", ItemStack.class, UUID.class, int.class);
        assertMethod(EnhancementCoreTagger.class, "isEnhancementCore", boolean.class, ItemStack.class);
        assertEquals(1, EnhancementCoreTagger.class.getField("ITEM_VERSION").getInt(null));

        assertPublicConstructor(CoreRepairReceiptTagger.class, Plugin.class);
        assertMethod(CoreRepairReceiptTagger.class, "tag", ItemStack.class, ItemStack.class, UUID.class);
        assertMethod(CoreRepairReceiptTagger.class, "strip", ItemStack.class, ItemStack.class);
        assertMethod(CoreRepairReceiptTagger.class, "read", Optional.class, ItemStack.class);
        assertMethod(CoreRepairReceiptTagger.class, "isTagged", boolean.class, ItemStack.class);
        assertMethod(CoreRepairReceiptTagger.class, "isFor", boolean.class, ItemStack.class, UUID.class);

        assertPublicConstructor(RewardQueueReceiptTagger.class, Plugin.class);
        assertMethod(RewardQueueReceiptTagger.class, "tag", ItemStack.class, ItemStack.class, RewardQueueReceipt.class);
        assertMethod(RewardQueueReceiptTagger.class, "strip", ItemStack.class, ItemStack.class);
        assertMethod(RewardQueueReceiptTagger.class, "read", Optional.class, ItemStack.class);
        assertMethod(RewardQueueReceiptTagger.class, "isTagged", boolean.class, ItemStack.class);
    }

    @Test
    void publicClassesRemainFinal() {
        assertTrue(Modifier.isFinal(CoreBlockTagger.class.getModifiers()));
        assertTrue(Modifier.isFinal(DefenseShardTagger.class.getModifiers()));
        assertTrue(Modifier.isFinal(EnhancementCoreTagger.class.getModifiers()));
        assertTrue(Modifier.isFinal(CoreRepairReceiptTagger.class.getModifiers()));
        assertTrue(Modifier.isFinal(RewardQueueReceiptTagger.class.getModifiers()));
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
