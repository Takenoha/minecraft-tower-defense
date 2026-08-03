package io.github.takenoha.towerdefense.domain;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, bounded role composition for one wave. */
public final class EnemyRoleSchedule {
    private final double destroyerRatio;
    private final double builderRatio;

    public EnemyRoleSchedule(double destroyerRatio, double builderRatio) {
        requireRatio("destroyerRatio", destroyerRatio);
        requireRatio("builderRatio", builderRatio);
        if (destroyerRatio + builderRatio > 1.0d) {
            throw new IllegalArgumentException("enemy role ratios must sum to at most 1");
        }
        this.destroyerRatio = destroyerRatio;
        this.builderRatio = builderRatio;
    }

    /**
     * Allocates roles for a wave. Special-role ratios grow up to three times their base value as
     * stage and wave rise, while the total count and boss slot remain fixed.
     */
    public List<EnemyRole> forWave(
            long stageLevel,
            int waveIndex,
            int totalEnemies,
            boolean finalWave) {
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
        if (waveIndex <= 0) {
            throw new IllegalArgumentException("waveIndex must be positive");
        }
        if (totalEnemies <= 0) {
            throw new IllegalArgumentException("totalEnemies must be positive");
        }

        int bossSlots = finalWave ? 1 : 0;
        if (bossSlots > totalEnemies) {
            throw new IllegalArgumentException("a final wave must have at least one enemy");
        }
        int regularEnemies = totalEnemies - bossSlots;
        double progression = 1.0d
                + Math.min(1.0d, ((double) stageLevel - 1.0d) / 10.0d)
                + Math.min(1.0d, ((double) waveIndex - 1.0d) / 10.0d);
        int destroyers = roundedCount(regularEnemies, destroyerRatio * progression);
        int builders = roundedCount(regularEnemies, builderRatio * progression);
        if (destroyers + builders > regularEnemies) {
            builders = Math.max(0, regularEnemies - destroyers);
            if (destroyers + builders > regularEnemies) {
                destroyers = regularEnemies;
                builders = 0;
            }
        }

        List<EnemyRole> result = new ArrayList<>(totalEnemies);
        if (finalWave) {
            result.add(EnemyRole.BOSS);
        }
        add(result, EnemyRole.DESTROYER, destroyers);
        add(result, EnemyRole.BUILDER, builders);
        add(result, EnemyRole.NORMAL, regularEnemies - destroyers - builders);
        return List.copyOf(result);
    }

    private static int roundedCount(int total, double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0.0d) {
            throw new IllegalArgumentException("role ratio must be finite and non-negative");
        }
        long rounded = Math.round(total * Math.min(1.0d, ratio));
        return (int) Math.min(total, rounded);
    }

    private static void add(List<EnemyRole> roles, EnemyRole role, int count) {
        for (int index = 0; index < count; index++) {
            roles.add(role);
        }
    }

    private static void requireRatio(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
