package io.github.takenoha.towerdefense.domain;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, bounded role composition for one wave. */
public final class EnemyRoleSchedule {
    private final double destroyerRatio;
    private final double builderRatio;
    private final double speedsterRatio;
    private final double rangedRatio;
    private final double heavyRatio;
    private final double supportRatio;

    public EnemyRoleSchedule(double destroyerRatio, double builderRatio) {
        this(
                destroyerRatio,
                builderRatio,
                0.0d,
                0.0d,
                0.0d,
                0.0d);
    }

    public EnemyRoleSchedule(
            double destroyerRatio,
            double builderRatio,
            double speedsterRatio,
            double rangedRatio,
            double heavyRatio) {
        this(
                destroyerRatio,
                builderRatio,
                speedsterRatio,
                rangedRatio,
                heavyRatio,
                0.0d);
    }

    public EnemyRoleSchedule(
            double destroyerRatio,
            double builderRatio,
            double speedsterRatio,
            double rangedRatio,
            double heavyRatio,
            double supportRatio) {
        requireRatio("destroyerRatio", destroyerRatio);
        requireRatio("builderRatio", builderRatio);
        requireRatio("speedsterRatio", speedsterRatio);
        requireRatio("rangedRatio", rangedRatio);
        requireRatio("heavyRatio", heavyRatio);
        requireRatio("supportRatio", supportRatio);
        if (destroyerRatio
                + builderRatio
                + speedsterRatio
                + rangedRatio
                + heavyRatio
                + supportRatio > 1.0d) {
            throw new IllegalArgumentException("enemy role ratios must sum to at most 1");
        }
        this.destroyerRatio = destroyerRatio;
        this.builderRatio = builderRatio;
        this.speedsterRatio = speedsterRatio;
        this.rangedRatio = rangedRatio;
        this.heavyRatio = heavyRatio;
        this.supportRatio = supportRatio;
    }

    /**
     * Allocates roles for a wave. Special-role ratios grow with stage and wave while their
     * combined allocation remains bounded by the regular enemy count. Every tenth non-final wave
     * has one intermediate boss; the final wave always has one final boss.
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

        int bossSlots = isBossWave(waveIndex, finalWave) ? 1 : 0;
        if (bossSlots > totalEnemies) {
            throw new IllegalArgumentException("a final wave must have at least one enemy");
        }
        int regularEnemies = totalEnemies - bossSlots;
        double progression = 1.0d
                + Math.min(1.0d, ((double) stageLevel - 1.0d) / 10.0d)
                + Math.min(1.0d, ((double) waveIndex - 1.0d) / 10.0d);
        double baseRoleRatioSum = destroyerRatio
                + builderRatio
                + speedsterRatio
                + rangedRatio
                + heavyRatio
                + supportRatio;
        if (baseRoleRatioSum > 0.0d) {
            progression = Math.min(progression, 1.0d / baseRoleRatioSum);
        }
        int remaining = regularEnemies;
        int destroyers = allocate(
                remaining,
                roundedCount(regularEnemies, destroyerRatio * progression));
        remaining -= destroyers;
        int builders = allocate(
                remaining,
                roundedCount(regularEnemies, builderRatio * progression));
        remaining -= builders;
        int speedsters = allocate(
                remaining,
                roundedCount(regularEnemies, speedsterRatio * progression));
        remaining -= speedsters;
        int ranged = allocate(
                remaining,
                roundedCount(regularEnemies, rangedRatio * progression));
        remaining -= ranged;
        int heavies = allocate(
                remaining,
                roundedCount(regularEnemies, heavyRatio * progression));
        remaining -= heavies;
        int supports = allocate(
                remaining,
                roundedCount(regularEnemies, supportRatio * progression));
        remaining -= supports;

        List<EnemyRole> result = new ArrayList<>(totalEnemies);
        if (bossSlots == 1) {
            result.add(EnemyRole.BOSS);
        }
        add(result, EnemyRole.DESTROYER, destroyers);
        add(result, EnemyRole.BUILDER, builders);
        add(result, EnemyRole.SPEEDSTER, speedsters);
        add(result, EnemyRole.RANGED, ranged);
        add(result, EnemyRole.HEAVY, heavies);
        add(result, EnemyRole.SUPPORT, supports);
        add(result, EnemyRole.NORMAL, remaining);
        return List.copyOf(result);
    }

    /** Returns whether this wave receives the intermediate or final boss slot. */
    public boolean isBossWave(int waveIndex, boolean finalWave) {
        if (waveIndex <= 0) {
            throw new IllegalArgumentException("waveIndex must be positive");
        }
        return finalWave || (waveIndex % 10 == 0);
    }

    private static int roundedCount(int total, double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0.0d) {
            throw new IllegalArgumentException("role ratio must be finite and non-negative");
        }
        long rounded = Math.round(total * Math.min(1.0d, ratio));
        return (int) Math.min(total, rounded);
    }

    private static int allocate(int remaining, int requested) {
        return Math.max(0, Math.min(remaining, requested));
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
