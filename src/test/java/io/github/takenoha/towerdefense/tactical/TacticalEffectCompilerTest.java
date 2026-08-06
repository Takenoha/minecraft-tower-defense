package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalEffectCompilerTest {
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();

    @Test
    void compilesAllHotPathValuesAndConditions() {
        TacticalEffectSnapshot snapshot = new TacticalEffectCompiler().compile(selection(
                6,
                List.of(
                        node(1, effect(TacticalEffectType.DAMAGE_MULTIPLIER,
                                Set.of(TowerType.ARROW), 1.10d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.ATTACK_INTERVAL_MULTIPLIER,
                                Set.of(TowerType.SNIPER), 0.92d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.RANGE_ADD,
                                Set.of(TowerType.ARROW), 1.0d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.AREA_RADIUS_MULTIPLIER,
                                Set.of(TowerType.CANNON), 1.10d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.CHAIN_COUNT_ADD,
                                Set.of(TowerType.LIGHTNING), 1.0d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.SLOW_STRENGTH_MULTIPLIER,
                                Set.of(TowerType.FROST), 1.10d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.BURN_DURATION_MULTIPLIER,
                                Set.of(TowerType.FLAME), 1.20d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.SUPPORT_BUFF_MULTIPLIER,
                                Set.of(TowerType.SUPPORT), 1.10d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.REPAIR_COST_MULTIPLIER,
                                Set.of(TowerType.values()), 0.90d, TacticalTargetCondition.NONE)),
                        node(1, effect(TacticalEffectType.TOWER_DAMAGE_TAKEN_MULTIPLIER,
                                Set.of(TowerType.values()), 0.90d, TacticalTargetCondition.NONE)),
                        node(2, effect(TacticalEffectType.DAMAGE_TO_BOSS_MULTIPLIER,
                                Set.of(TowerType.SNIPER), 1.15d, TacticalTargetCondition.NONE)),
                        node(2, effect(TacticalEffectType.DAMAGE_TO_LOW_HP_MULTIPLIER,
                                Set.of(TowerType.ARROW), 1.20d, TacticalTargetCondition.NONE)),
                        node(2, effect(TacticalEffectType.CORE_LOW_HP_DAMAGE_MULTIPLIER,
                                Set.of(TowerType.values()), 1.10d, TacticalTargetCondition.NONE)),
                        node(2, effect(TacticalEffectType.CORE_LOW_HP_ATTACK_INTERVAL_MULTIPLIER,
                                Set.of(TowerType.values()), 0.85d, TacticalTargetCondition.NONE))
                )));

        TacticalTargetContext normal = new TacticalTargetContext(0.80d, 0.70d, false, false, false);
        TacticalTargetContext bossLowCore = new TacticalTargetContext(
                0.20d, 0.20d, true, false, false);

        assertEquals(1.10d, snapshot.damageMultiplier(TowerType.ARROW, normal), 0.000001d);
        assertEquals(1.10d * 1.20d * 1.10d,
                snapshot.damageMultiplier(TowerType.ARROW, bossLowCore), 0.000001d);
        assertEquals(0.92d, snapshot.attackIntervalMultiplier(TowerType.SNIPER, normal), 0.000001d);
        assertEquals(0.92d * 0.85d,
                snapshot.attackIntervalMultiplier(TowerType.SNIPER, bossLowCore), 0.000001d);
        assertEquals(1.0d, snapshot.damageMultiplier(TowerType.CANNON, normal), 0.000001d);
        assertEquals(1.0d, snapshot.damageMultiplier(TowerType.SNIPER, normal), 0.000001d);
        assertEquals(0.0d, snapshot.rangeAdd(TowerType.CANNON), 0.000001d);
        assertEquals(1.10d, snapshot.areaRadiusMultiplier(TowerType.CANNON), 0.000001d);
        assertEquals(1, snapshot.chainCountAdd(TowerType.LIGHTNING));
        assertEquals(1.10d, snapshot.slowStrengthMultiplier(TowerType.FROST), 0.000001d);
        assertEquals(1.20d, snapshot.burnDurationMultiplier(TowerType.FLAME), 0.000001d);
        assertEquals(1.10d, snapshot.supportBuffMultiplier(), 0.000001d);
        assertEquals(0.90d, snapshot.repairCostMultiplier(), 0.000001d);
        assertEquals(0.90d, snapshot.towerDamageTakenMultiplier(), 0.000001d);
    }

    @Test
    void ignoresNodesAboveTheUnlockedTierAndClampsConfiguredValues() {
        TacticalEffectSnapshot snapshot = new TacticalEffectCompiler().compile(selection(
                1,
                List.of(
                        node(1, new TacticalEffectEntry(
                                TacticalEffectType.DAMAGE_MULTIPLIER,
                                Set.of(TowerType.ARROW),
                                2.0d,
                                TacticalTargetCondition.NONE,
                                1.0d,
                                1.5d)),
                        node(2, effect(TacticalEffectType.DAMAGE_MULTIPLIER,
                                Set.of(TowerType.ARROW), 4.0d, TacticalTargetCondition.NONE))
                )));

        assertEquals(1.5d, snapshot.damageMultiplier(
                TowerType.ARROW, TacticalTargetContext.neutral()), 0.000001d);
    }

    private static TacticalBuildSelectionView selection(
            int highestTier,
            List<TacticalSkillNodeSnapshot> nodes) {
        return new TacticalBuildSelectionView(
                SESSION_ID,
                TEAM_ID,
                1,
                "test-build",
                1,
                highestTier,
                nodes);
    }

    private static TacticalSkillNodeSnapshot node(int tier, TacticalEffectEntry effect) {
        return new TacticalSkillNodeSnapshot(
                "node-" + tier + "-" + UUID.randomUUID(),
                1,
                tier,
                "Node " + tier,
                "test",
                List.of(effect));
    }

    private static TacticalEffectEntry effect(
            TacticalEffectType type,
            Set<TowerType> towers,
            double value,
            TacticalTargetCondition condition) {
        return new TacticalEffectEntry(type, towers, value, condition, null, null);
    }
}
