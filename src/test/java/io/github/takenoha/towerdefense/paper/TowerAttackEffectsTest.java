package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

final class TowerAttackEffectsTest {
    @Test
    void everyTowerHasAStableDistinctTrailDefinition() {
        Set<Particle> trails = new HashSet<>();
        for (TowerType type : TowerType.values()) {
            TowerEffectDefinition definition = TowerAttackEffects.definition(type);
            assertEquals(type, definition.type());
            assertTrue(trails.add(definition.trail()), "duplicate trail for " + type);
            assertTrue(definition.trailCount() > 0);
            assertTrue(definition.hitCount() > 0);
            assertTrue(definition.buffCount() > 0);
        }
        assertEquals(EnumSet.allOf(TowerType.class).size(), trails.size());
    }

    @Test
    void supportUsesBuffParticleDefinitionAndAllTypesAreMapped() {
        assertNotEquals(
                TowerAttackEffects.definition(TowerType.SUPPORT).hit(),
                TowerAttackEffects.definition(TowerType.SUPPORT).trail());
        for (TowerType type : TowerType.values()) {
            assertTrue(TowerAttackEffects.definition(type).buff() != null);
        }
    }

    @Test
    void effectBudgetStopsAtThePerAttackBound() {
        TowerAttackEffects.Budget budget = TowerAttackEffects.newBudget();
        for (int index = 0; index < TowerAttackEffects.MAX_EFFECTS_PER_ATTACK; index++) {
            assertTrue(budget.remaining() > 0);
            assertTrue(budget.claim());
        }
        assertEquals(0, budget.remaining());
        assertTrue(!budget.claim());
    }
}
