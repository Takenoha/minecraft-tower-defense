package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalContractTest {
    @Test
    void emptySnapshotIsNeutral() {
        TacticalEffectSnapshot snapshot = EmptyTacticalEffectSnapshot.INSTANCE;

        assertEquals(1.0d, snapshot.damageMultiplier(TowerType.ARROW, TacticalTargetContext.neutral()));
        assertEquals(1.0d, snapshot.attackIntervalMultiplier(
                TowerType.CANNON, TacticalTargetContext.neutral()));
        assertEquals(0.0d, snapshot.rangeAdd(TowerType.SNIPER));
        assertEquals(0, snapshot.chainCountAdd(TowerType.LIGHTNING));
        assertEquals(1.0d, snapshot.repairCostMultiplier());
        assertEquals(1.0d, snapshot.towerDamageTakenMultiplier());
    }

    @Test
    void selectionViewCopiesNodeListAndKeepsSharedShape() {
        TacticalEffectEntry effect = new TacticalEffectEntry(
                TacticalEffectType.DAMAGE_MULTIPLIER,
                Set.of(TowerType.ARROW),
                1.1d,
                TacticalTargetCondition.NONE,
                null,
                null);
        TacticalSkillNodeSnapshot node = new TacticalSkillNodeSnapshot(
                "rapid-fire-1", 1, 1, "Rapid Fire", "Faster arrows", List.of(effect));
        List<TacticalSkillNodeSnapshot> nodes = List.of(node);
        TacticalBuildSelectionView view = new TacticalBuildSelectionView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "rapid-fire",
                1,
                1,
                nodes);

        assertEquals(nodes, view.nodes());
        assertSame(node, view.nodes().getFirst());
    }
}
