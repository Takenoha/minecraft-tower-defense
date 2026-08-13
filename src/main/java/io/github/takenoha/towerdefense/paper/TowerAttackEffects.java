package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Bounded vanilla-particle rendering for tower attacks and support pulses.
 *
 * <p>This class deliberately has no gameplay side effects.  Callers invoke it only after the
 * corresponding damage, debuff, or support multiplier has been accepted.</p>
 */
public final class TowerAttackEffects {
    public static final int MAX_EFFECTS_PER_ATTACK = 32;
    private static final int MAX_TRACE_POINTS = 12;
    private static final double TRACE_STEP = 1.5d;
    private static final Map<TowerType, TowerEffectDefinition> DEFINITIONS = definitions();

    private TowerAttackEffects() {
    }

    public static TowerEffectDefinition definition(TowerType type) {
        return DEFINITIONS.get(Objects.requireNonNull(type, "type"));
    }

    public static Budget newBudget() {
        return new Budget(MAX_EFFECTS_PER_ATTACK);
    }

    public static void renderAttack(
            TowerType type,
            Location origin,
            Location target,
            Budget budget) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(budget, "budget");
        if (!sameWorld(origin, target)) {
            return;
        }
        TowerEffectDefinition effect = definition(type);
        World world = origin.getWorld();
        double distance = origin.distance(target);
        int points = Math.max(1, Math.min(
                MAX_TRACE_POINTS,
                (int) Math.ceil(distance / TRACE_STEP)));
        for (int index = 1; index <= points; index++) {
            double fraction = (double) index / points;
            Location point = origin.clone().add(
                    (target.getX() - origin.getX()) * fraction,
                    (target.getY() - origin.getY()) * fraction,
                    (target.getZ() - origin.getZ()) * fraction);
            if (!budget.claim()) {
                return;
            }
            spawnParticle(
                    world,
                    effect.trail(),
                    point,
                    effect.trailCount(),
                    0.0d,
                    0.0d,
                    0.0d,
                    0.0d);
        }
    }

    public static void renderHit(TowerType type, Location target, Budget budget) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(budget, "budget");
        if (target.getWorld() == null || !budget.claim()) {
            return;
        }
        TowerEffectDefinition effect = definition(type);
        spawnParticle(
                target.getWorld(),
                effect.hit(),
                target,
                effect.hitCount(),
                0.25d,
                0.35d,
                0.25d,
                0.0d);
    }

    public static void renderBuff(
            TowerType type,
            Location source,
            Location target,
            Budget budget) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(budget, "budget");
        if (!sameWorld(source, target)) {
            return;
        }
        TowerEffectDefinition effect = definition(type);
        World world = source.getWorld();
        if (!budget.claim()) {
            return;
        }
        spawnParticle(
                world,
                effect.buff(),
                target,
                effect.buffCount(),
                0.35d,
                0.5d,
                0.35d,
                0.0d);
        if (!budget.claim()) {
            return;
        }
        spawnParticle(
                world,
                effect.buff(),
                source,
                Math.max(1, effect.buffCount() / 2),
                0.2d,
                0.35d,
                0.2d,
                0.0d);
    }

    /** Returns the payload required by Paper for particles that are not data-free. */
    static Object particleDataFor(Particle particle) {
        Objects.requireNonNull(particle, "particle");
        return particle == Particle.FLASH ? Color.WHITE : null;
    }

    private static void spawnParticle(
            World world,
            Particle particle,
            Location location,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ,
            double extra) {
        Object data = particleDataFor(particle);
        if (data == null) {
            world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
        } else {
            world.spawnParticle(
                    particle,
                    location,
                    count,
                    offsetX,
                    offsetY,
                    offsetZ,
                    extra,
                    data);
        }
    }

    private static boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null
                && second.getWorld() != null
                && first.getWorld().equals(second.getWorld());
    }

    private static Map<TowerType, TowerEffectDefinition> definitions() {
        EnumMap<TowerType, TowerEffectDefinition> definitions = new EnumMap<>(TowerType.class);
        definitions.put(TowerType.ARROW, new TowerEffectDefinition(
                TowerType.ARROW, Particle.CRIT, Particle.DAMAGE_INDICATOR, Particle.ENCHANTED_HIT,
                1, 3, 2));
        definitions.put(TowerType.CANNON, new TowerEffectDefinition(
                TowerType.CANNON, Particle.SMOKE, Particle.EXPLOSION, Particle.CLOUD,
                2, 1, 2));
        definitions.put(TowerType.FROST, new TowerEffectDefinition(
                TowerType.FROST, Particle.SNOWFLAKE, Particle.CLOUD, Particle.SNOWFLAKE,
                3, 4, 3));
        definitions.put(TowerType.LIGHTNING, new TowerEffectDefinition(
                TowerType.LIGHTNING, Particle.ELECTRIC_SPARK, Particle.FLASH, Particle.ELECTRIC_SPARK,
                2, 1, 3));
        definitions.put(TowerType.SUPPORT, new TowerEffectDefinition(
                TowerType.SUPPORT, Particle.ENCHANT, Particle.HEART, Particle.ENCHANT,
                2, 1, 5));
        definitions.put(TowerType.SNIPER, new TowerEffectDefinition(
                TowerType.SNIPER, Particle.END_ROD, Particle.SONIC_BOOM, Particle.END_ROD,
                1, 1, 2));
        definitions.put(TowerType.FLAME, new TowerEffectDefinition(
                TowerType.FLAME, Particle.FLAME, Particle.LAVA, Particle.FLAME,
                3, 3, 3));
        return Map.copyOf(definitions);
    }

    /** Per-attack cap shared by a tower's trail, hit effects, and support pulse. */
    public static final class Budget {
        private int remaining;

        private Budget(int maximum) {
            remaining = maximum;
        }

        boolean claim() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        public int remaining() {
            return remaining;
        }
    }
}
