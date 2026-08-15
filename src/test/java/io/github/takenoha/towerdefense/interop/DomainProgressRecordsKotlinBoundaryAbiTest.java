package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.EnemyObstacleClassification;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyPathContext;
import io.github.takenoha.towerdefense.domain.EnemyTerrainActionKind;
import io.github.takenoha.towerdefense.domain.TeamProgress;
import io.github.takenoha.towerdefense.domain.TowerResearch;
import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainProgressRecordsKotlinBoundaryAbiTest {
    @Test
    void preservesRecordComponentsAndCanonicalConstructors() throws Exception {
        assertRecord(TeamProgress.class,
                new Class<?>[]{UUID.class, long.class, long.class, long.class},
                "teamId", "highestClearedLevel", "unlockedLevel", "researchPoints");
        assertRecord(TowerResearch.class,
                new Class<?>[]{UUID.class, TowerType.class, int.class, Instant.class},
                "teamId", "towerType", "researchLevel", "updatedAt");
        assertRecord(EnemyObstacleFacts.class,
                new Class<?>[]{EnemyObstacleClassification.class, String.class, String.class,
                        boolean.class, boolean.class},
                "classification", "currentMaterialKey", "targetMaterialKey",
                "withinCombatArea", "supportAvailable");
    }

    @Test
    void preservesFactoriesAndDomainMethods() throws Exception {
        assertPublicStatic(TeamProgress.class, "initial", UUID.class);
        assertPublicInstance(TeamProgress.class, "afterVictory", long.class);
        assertPublicStatic(TowerResearch.class, "initial",
                UUID.class, TowerType.class, Instant.class);
        assertPublicStatic(EnemyObstacleFacts.class, "unavailable");
        assertPublicInstance(EnemyObstacleFacts.class, "permits", EnemyTerrainActionKind.class);
        assertPublicInstance(EnemyObstacleFacts.class, "toPathContext", int.class);

        UUID teamId = UUID.randomUUID();
        TeamProgress initial = TeamProgress.initial(teamId);
        assertEquals(teamId, initial.teamId());
        assertEquals(0L, initial.highestClearedLevel());
        assertEquals(1L, initial.unlockedLevel());
        assertEquals(0L, initial.researchPoints());
        assertEquals(2L, initial.afterVictory(1L).unlockedLevel());

        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        TowerResearch research = TowerResearch.initial(teamId, TowerType.ARROW, createdAt);
        assertEquals(teamId, research.teamId());
        assertEquals(TowerType.ARROW, research.towerType());
        assertEquals(1, research.researchLevel());
        assertEquals(createdAt, research.updatedAt());

        EnemyObstacleFacts unavailable = EnemyObstacleFacts.unavailable();
        assertEquals(EnemyObstacleClassification.UNAVAILABLE, unavailable.classification());
        assertEquals("minecraft:air", unavailable.currentMaterialKey());
        assertEquals("minecraft:air", unavailable.targetMaterialKey());
        assertTrue(!unavailable.permits(EnemyTerrainActionKind.BREAK));
        EnemyPathContext context = unavailable.toPathContext(3);
        assertTrue(!context.directPathAvailable());
        assertTrue(!context.protectedObstacle());
        assertTrue(!context.breakableObstacle());
        assertTrue(!context.buildableGap());
        assertEquals(3, context.consecutivePathFailures());
    }

    private static void assertPublicStatic(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }

    private static void assertPublicInstance(Class<?> type, String name, Class<?>... parameters)
            throws Exception {
        var method = type.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(!Modifier.isStatic(method.getModifiers()));
    }

    private static void assertRecord(Class<?> type, Class<?>[] componentTypes, String... names)
            throws Exception {
        assertTrue(type.isRecord(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(List.of(names), Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(names.length, componentTypes.length);
        assertNotNull(type.getConstructor(componentTypes));
    }
}
