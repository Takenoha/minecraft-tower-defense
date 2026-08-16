package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.domain.EnemyTerrainActionKind;
import io.github.takenoha.towerdefense.domain.TowerTargetPriority;
import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.BattleBoostKind;
import io.github.takenoha.towerdefense.persistence.ResourceType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MethodEnumsKotlinBoundaryAbiTest {
    @Test
    void preservesEnumOrderAndStaticFactories() throws Exception {
        assertEnum(DefensePhase.class,
                "COUNTDOWN", "PREPARATION", "WAVE_ACTIVE", "INTERMISSION",
                "VICTORY", "DEFEAT", "ABORTED", "RECOVERY");
        assertEnum(EnemyRole.class,
                "NORMAL", "DESTROYER", "BUILDER", "BOSS", "SPEEDSTER", "RANGED", "HEAVY");
        assertEnum(TowerTargetPriority.class,
                "CORE_NEAREST", "NEAREST", "HEALTH_HIGH", "HEALTH_LOW", "BOSS");
        assertEnum(TowerType.class,
                "ARROW", "CANNON", "FROST", "LIGHTNING", "SUPPORT", "SNIPER", "FLAME");
        assertEnum(BattleBoostKind.class, "POWER", "SPEED", "RANGE");
        assertEnum(ResourceType.class, "DEFENSE_POINTS", "ENHANCEMENT_POINTS");

        assertTrue(Modifier.isStatic(EnemyRole.class.getMethod("fromId", String.class).getModifiers()));
        assertTrue(Modifier.isStatic(TowerTargetPriority.class
                .getMethod("fromId", String.class).getModifiers()));
        assertTrue(Modifier.isStatic(TowerType.class.getMethod("fromId", String.class).getModifiers()));
        assertTrue(Modifier.isStatic(ResourceType.class
                .getMethod("fromItemId", String.class).getModifiers()));
        assertTrue(Modifier.isStatic(ResourceType.class
                .getMethod("require", ResourceType.class).getModifiers()));
    }

    @Test
    void preservesPhaseTransitions() {
        assertTrue(DefensePhase.COUNTDOWN.canTransitionTo(DefensePhase.PREPARATION));
        assertTrue(DefensePhase.WAVE_ACTIVE.canTransitionTo(DefensePhase.VICTORY));
        assertTrue(DefensePhase.VICTORY.canTransitionTo(DefensePhase.VICTORY));
        assertFalse(DefensePhase.COUNTDOWN.canTransitionTo(DefensePhase.VICTORY));
        assertFalse(DefensePhase.RECOVERY.canTransitionTo(DefensePhase.PREPARATION));
        assertTrue(DefensePhase.VICTORY.isTerminal());
        assertFalse(DefensePhase.INTERMISSION.isTerminal());
    }

    @Test
    void preservesMappedValuesAndRoleRules() {
        assertEquals("FOUNDATION_DESTROYER", EnemyRole.DESTROYER.ledgerType());
        assertEquals("DESTROYER", EnemyRole.DESTROYER.id());
        assertEquals(11.5, EnemyRole.DESTROYER.navigationSpeed(10.0));
        assertEquals(15.0, EnemyRole.SPEEDSTER.navigationSpeed(10.0));
        assertEquals(0.75, EnemyRole.SPEEDSTER.healthMultiplier());
        assertEquals(1.25, EnemyRole.HEAVY.healthMultiplier());
        assertTrue(EnemyRole.NORMAL.allowsTerrainAction(EnemyTerrainActionKind.BREAK, true));
        assertFalse(EnemyRole.NORMAL.allowsTerrainAction(EnemyTerrainActionKind.BREAK, false));
        assertTrue(EnemyRole.BUILDER.allowsTerrainAction(EnemyTerrainActionKind.BUILD, false));
        assertFalse(EnemyRole.BOSS.allowsTerrainAction(EnemyTerrainActionKind.BREAK, true));
        assertFalse(EnemyRole.SPEEDSTER.allowsTerrainAction(EnemyTerrainActionKind.BREAK, true));
        assertFalse(EnemyRole.RANGED.allowsTerrainAction(EnemyTerrainActionKind.BUILD, false));
        assertFalse(EnemyRole.HEAVY.allowsTerrainAction(EnemyTerrainActionKind.BREAK, false));

        assertEquals("arrow", TowerType.ARROW.id());
        assertEquals("アロー", TowerType.ARROW.displayName());
        assertEquals("core_nearest", TowerTargetPriority.CORE_NEAREST.id());
        assertEquals("コアに近い", TowerTargetPriority.CORE_NEAREST.displayName());
        assertEquals("power", BattleBoostKind.POWER.id());
        assertEquals("defense_shard", ResourceType.DEFENSE_POINTS.itemId());
        assertEquals("防衛ポイント", ResourceType.DEFENSE_POINTS.displayName());
    }

    @Test
    void preservesParsingAndOptionalBoundaries() {
        assertEquals(EnemyRole.DESTROYER, EnemyRole.fromId("destroyer"));
        assertEquals(EnemyRole.SPEEDSTER, EnemyRole.fromId("speedster"));
        assertEquals(TowerType.ARROW, TowerType.fromId("ARROW"));
        assertEquals(TowerTargetPriority.BOSS, TowerTargetPriority.fromId("BOSS"));
        assertThrows(IllegalArgumentException.class, () -> EnemyRole.fromId("unknown"));
        assertThrows(IllegalArgumentException.class, () -> TowerType.fromId("unknown"));
        assertThrows(IllegalArgumentException.class, () -> TowerTargetPriority.fromId("unknown"));
        assertEquals(Optional.of(ResourceType.DEFENSE_POINTS),
                ResourceType.fromItemId("defense_shard"));
        assertEquals(Optional.empty(), ResourceType.fromItemId(null));
        assertEquals(Optional.empty(), ResourceType.fromItemId("unknown"));
        assertEquals(ResourceType.DEFENSE_POINTS,
                ResourceType.require(ResourceType.DEFENSE_POINTS));
    }

    private static <E extends Enum<E>> void assertEnum(Class<E> type, String... names) {
        assertTrue(type.isEnum());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(List.of(names), Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
        for (String name : names) {
            assertEquals(name, Enum.valueOf(type, name).name());
        }
    }
}
