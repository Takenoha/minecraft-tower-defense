package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.EnemyRole;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Builds the compact health-bar name shown above an event enemy. */
public final class EnemyHealthBar {
    static final int SEGMENT_COUNT = 10;
    private static final String FILLED_SEGMENT = "█";
    private static final String EMPTY_SEGMENT = "░";

    private EnemyHealthBar() {
    }

    /** Returns a role label followed by a colored, number-free health bar. */
    public static Component displayName(
            EnemyRole role,
            boolean finalWave,
            double currentHealth,
            double maximumHealth) {
        Objects.requireNonNull(role, "role");
        double ratio = normalizedRatio(currentHealth, maximumHealth);
        int filledSegments = filledSegments(currentHealth, maximumHealth);
        return Component.text(label(role, finalWave), labelColor(role))
                .append(Component.text(" "))
                .append(Component.text(
                        FILLED_SEGMENT.repeat(filledSegments),
                        barColor(ratio)))
                .append(Component.text(
                        EMPTY_SEGMENT.repeat(SEGMENT_COUNT - filledSegments),
                        NamedTextColor.DARK_GRAY));
    }

    static int filledSegments(double currentHealth, double maximumHealth) {
        return (int) Math.round(normalizedRatio(currentHealth, maximumHealth) * SEGMENT_COUNT);
    }

    static String barText(double currentHealth, double maximumHealth) {
        int filledSegments = filledSegments(currentHealth, maximumHealth);
        return FILLED_SEGMENT.repeat(filledSegments)
                + EMPTY_SEGMENT.repeat(SEGMENT_COUNT - filledSegments);
    }

    private static double normalizedRatio(double currentHealth, double maximumHealth) {
        if (!Double.isFinite(currentHealth)
                || !Double.isFinite(maximumHealth)
                || maximumHealth <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, currentHealth / maximumHealth));
    }

    private static String label(EnemyRole role, boolean finalWave) {
        return switch (role) {
            case NORMAL -> "防衛戦敵";
            case DESTROYER -> "防衛戦破壊兵";
            case BUILDER -> "防衛戦建築兵";
            case BOSS -> finalWave ? "防衛戦最終ボス" : "防衛戦中ボス";
        };
    }

    private static NamedTextColor labelColor(EnemyRole role) {
        return switch (role) {
            case NORMAL -> NamedTextColor.WHITE;
            case DESTROYER, BOSS -> NamedTextColor.DARK_RED;
            case BUILDER -> NamedTextColor.BLUE;
        };
    }

    private static NamedTextColor barColor(double ratio) {
        if (ratio > 0.5) {
            return NamedTextColor.GREEN;
        }
        if (ratio > 0.25) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }
}
