package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
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
    void flashParticleReceivesItsRequiredColorPayload() {
        assertEquals(Color.WHITE, TowerAttackEffects.particleDataFor(Particle.FLASH));
        assertNull(TowerAttackEffects.particleDataFor(Particle.ELECTRIC_SPARK));
    }

    @Test
    void lightningHitUsesTheDataBearingSpawnParticleOverload() {
        AtomicReference<Object[]> invocation = new AtomicReference<>();
        World world = recordingWorld(invocation);

        TowerAttackEffects.renderHit(
                TowerType.LIGHTNING,
                new Location(world, 1.0d, 2.0d, 3.0d),
                TowerAttackEffects.newBudget());

        Object[] arguments = invocation.get();
        assertTrue(arguments != null, "expected a particle emission");
        assertEquals(8, arguments.length, "expected the data-bearing overload");
        assertEquals(Particle.FLASH, arguments[0]);
        assertEquals(Color.WHITE, arguments[7]);
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

    private static World recordingWorld(AtomicReference<Object[]> invocation) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("spawnParticle")) {
                        invocation.set(arguments);
                    }
                    return null;
                });
    }
}
