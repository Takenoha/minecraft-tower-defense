package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.ResourceType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI and invariant checks for the Kotlin Paper identity records. */
class PaperIdentitiesKotlinBoundaryAbiTest {
    @Test
    void recordComponentsAndConstructorsRemainJavaCompatible() throws Exception {
        assertRecord(CoreItemIdentity.class, "itemId", "teamId", "coreId");
        assertPublicConstructor(CoreItemIdentity.class, UUID.class, Optional.class, Optional.class);
        assertPublicConstructor(CoreItemIdentity.class, UUID.class, Optional.class);
        assertRecord(RaidSealItemIdentity.class, "sealId", "stageLevel");
        assertPublicConstructor(RaidSealItemIdentity.class, UUID.class, long.class);
        assertRecord(TowerItemIdentity.class, "towerId", "type", "individualLevel", "targetPriority");
        assertPublicConstructor(
                TowerItemIdentity.class, UUID.class, TowerType.class, int.class, TowerTargetPriority.class);
        assertPublicConstructor(TowerItemIdentity.class, UUID.class, TowerType.class, int.class);
        assertRecord(TowerEntityIdentity.class, "towerId", "teamId", "type", "individualLevel");
        assertPublicConstructor(TowerEntityIdentity.class, UUID.class, UUID.class, TowerType.class, int.class);
        assertRecord(
                ResearchCrystalItemIdentity.class,
                "batchId",
                "teamId",
                "issuedQuantity",
                "segmentOffset",
                "segmentQuantity");
        assertPublicConstructor(
                ResearchCrystalItemIdentity.class,
                UUID.class,
                UUID.class,
                int.class,
                Integer.class,
                Integer.class);
        assertPublicConstructor(
                ResearchCrystalItemIdentity.class, UUID.class, UUID.class, int.class);
        assertRecord(
                ResourceVoucherItemData.class,
                "voucherId",
                "teamId",
                "resourceType",
                "quantity",
                "deliveryOperationId",
                "redeemOperationId");
        assertPublicConstructor(
                ResourceVoucherItemData.class,
                UUID.class,
                UUID.class,
                ResourceType.class,
                long.class,
                Optional.class,
                Optional.class);
        assertEquals(boolean.class, CoreItemIdentity.class.getMethod("isBound").getReturnType());
        assertEquals(boolean.class, ResearchCrystalItemIdentity.class.getMethod("hasSegmentIdentity").getReturnType());
        assertEquals(boolean.class, ResourceVoucherItemData.class.getMethod("hasReceipt").getReturnType());
    }

    @Test
    void constructorsRetainValidationAndConvenienceSemantics() {
        UUID itemId = UUID.randomUUID();
        CoreItemIdentity unbound = new CoreItemIdentity(itemId, Optional.empty());
        assertFalse(unbound.isBound());
        assertEquals(Optional.empty(), unbound.coreId());
        CoreItemIdentity bound = new CoreItemIdentity(itemId, Optional.of(UUID.randomUUID()));
        assertTrue(bound.isBound());

        TowerItemIdentity tower = new TowerItemIdentity(itemId, TowerType.ARROW, 2);
        assertEquals(TowerTargetPriority.CORE_NEAREST, tower.targetPriority());
        assertThrows(IllegalArgumentException.class, () -> new RaidSealItemIdentity(itemId, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResearchCrystalItemIdentity(itemId, itemId, 3, 2, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceVoucherItemData(
                        itemId,
                        itemId,
                        ResourceType.DEFENSE_POINTS,
                        2L,
                        Optional.of(UUID.randomUUID()),
                        Optional.of(UUID.randomUUID())));
        ResourceVoucherItemData voucher = new ResourceVoucherItemData(
                itemId,
                itemId,
                ResourceType.DEFENSE_POINTS,
                2L,
                Optional.empty(),
                Optional.empty());
        assertFalse(voucher.hasReceipt());
    }

    private static void assertRecord(Class<?> type, String... names) throws Exception {
        assertTrue(type.isRecord(), type.getName());
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
        RecordComponent[] components = type.getRecordComponents();
        assertNotNull(components, type.getName());
        assertEquals(names.length, components.length, type.getName());
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], components[i].getName(), type.getName());
            assertEquals(components[i].getType(), type.getMethod(names[i]).getReturnType(), names[i]);
        }
    }

    private static void assertPublicConstructor(Class<?> type, Class<?>... parameterTypes) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()), type.getName());
    }
}
