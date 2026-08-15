package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.ResourceType;
import io.github.takenoha.towerdefense.persistence.TeamResourceSnapshot;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamResourceSnapshotKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCompatibilityConstructor() throws Exception {
        assertTrue(TeamResourceSnapshot.class.isRecord());
        assertTrue(Modifier.isPublic(TeamResourceSnapshot.class.getModifiers()));
        assertTrue(Modifier.isFinal(TeamResourceSnapshot.class.getModifiers()));
        assertEquals(List.of(
                "teamId", "defensePoints", "enhancementPoints",
                "teamProvisionalDefensePoints", "teamProvisionalEnhancementPoints",
                "viewerProvisionalDefensePoints", "viewerProvisionalEnhancementPoints"
        ), Arrays.stream(TeamResourceSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertNotNull(TeamResourceSnapshot.class.getConstructor(
                UUID.class, long.class, long.class, long.class, long.class,
                long.class, long.class));
        assertNotNull(TeamResourceSnapshot.class.getConstructor(
                UUID.class, long.class, long.class, long.class, long.class));
    }

    @Test
    void preservesResourceViews() throws Exception {
        assertPublicInstance("balance", ResourceType.class);
        assertPublicInstance("provisional", ResourceType.class);
        assertPublicInstance("teamProvisional", ResourceType.class);

        TeamResourceSnapshot snapshot = new TeamResourceSnapshot(
                UUID.randomUUID(), 20L, 30L, 4L, 5L, 2L, 3L);
        assertEquals(20L, snapshot.balance(ResourceType.DEFENSE_POINTS));
        assertEquals(30L, snapshot.balance(ResourceType.ENHANCEMENT_POINTS));
        assertEquals(2L, snapshot.provisional(ResourceType.DEFENSE_POINTS));
        assertEquals(3L, snapshot.provisional(ResourceType.ENHANCEMENT_POINTS));
        assertEquals(4L, snapshot.teamProvisional(ResourceType.DEFENSE_POINTS));
        assertEquals(5L, snapshot.teamProvisional(ResourceType.ENHANCEMENT_POINTS));
    }

    private static void assertPublicInstance(String name, Class<?>... parameters)
            throws Exception {
        var method = TeamResourceSnapshot.class.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()), name);
        assertTrue(!Modifier.isStatic(method.getModifiers()), name);
    }
}
