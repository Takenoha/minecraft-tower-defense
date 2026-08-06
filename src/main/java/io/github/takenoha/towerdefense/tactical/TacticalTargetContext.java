package io.github.takenoha.towerdefense.tactical;

/** Read-only combat facts used when evaluating conditional tactical effects. */
public record TacticalTargetContext(
        double targetHealthFraction,
        double coreHealthFraction,
        boolean boss,
        boolean slowed,
        boolean burning) {
    public TacticalTargetContext {
        requireFraction(targetHealthFraction, "targetHealthFraction");
        requireFraction(coreHealthFraction, "coreHealthFraction");
    }

    public static TacticalTargetContext neutral() {
        return new TacticalTargetContext(1.0d, 1.0d, false, false, false);
    }

    public boolean targetHasHighHealth() {
        return targetHealthFraction >= 0.75d;
    }

    public boolean targetHasLowHealth() {
        return targetHealthFraction <= 0.30d;
    }

    public boolean coreBelowHalf() {
        return coreHealthFraction < 0.50d;
    }

    public boolean coreBelowThirtyPercent() {
        return coreHealthFraction < 0.30d;
    }

    private static void requireFraction(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
