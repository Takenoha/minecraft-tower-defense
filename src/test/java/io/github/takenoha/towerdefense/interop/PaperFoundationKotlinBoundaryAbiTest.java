package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.paper.CoreRecipeDefinition;
import io.github.takenoha.towerdefense.paper.RaidSealRecipeDefinition;
import io.github.takenoha.towerdefense.paper.RewardQueueReceipt;
import io.github.takenoha.towerdefense.paper.TaggedEscrowDrop;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the first Kotlin Paper foundation slice. */
class PaperFoundationKotlinBoundaryAbiTest {
    @Test
    void recordBoundariesKeepCanonicalConstructorsAndAccessors() throws Exception {
        assertTrue(TaggedEscrowDrop.class.isRecord());
        assertEquals(
                List.of("eventId", "dropId"),
                List.of(TaggedEscrowDrop.class.getRecordComponents()).stream()
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                UUID.class,
                TaggedEscrowDrop.class.getConstructor(UUID.class, UUID.class)
                        .getParameterTypes()[0]);
        assertEquals(UUID.class, TaggedEscrowDrop.class.getMethod("eventId").getReturnType());
        assertEquals(UUID.class, TaggedEscrowDrop.class.getMethod("dropId").getReturnType());

        assertTrue(RewardQueueReceipt.class.isRecord());
        assertArrayEquals(
                new Class<?>[] {UUID.class, UUID.class},
                RewardQueueReceipt.class.getConstructor(UUID.class, UUID.class).getParameterTypes());
        assertEquals(UUID.class, RewardQueueReceipt.class.getMethod("queueId").getReturnType());
        assertEquals(UUID.class, RewardQueueReceipt.class.getMethod("operationId").getReturnType());
    }

    @Test
    void recipeUtilitiesKeepConstantsPrivateConstructorsAndShapes() throws Exception {
        assertEquals(
                "PAPER",
                RaidSealRecipeDefinition.class.getField("PAPER_MATERIAL").get(null));
        assertEquals(
                List.of(" P ", "PSP", " P "),
                RaidSealRecipeDefinition.class.getMethod("shape").invoke(null));
        assertEquals(
                List.of(" I ", "IDI", " I "),
                CoreRecipeDefinition.class.getMethod("shape").invoke(null));
        assertEquals(
                "DIAMOND_BLOCK",
                CoreRecipeDefinition.class.getField("DIAMOND_BLOCK_MATERIAL").get(null));
        assertEquals(
                "IRON_INGOT",
                CoreRecipeDefinition.class.getField("IRON_INGOT_MATERIAL").get(null));
        assertTrue(Modifier.isPrivate(RaidSealRecipeDefinition.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(CoreRecipeDefinition.class.getDeclaredConstructor().getModifiers()));
    }
}
