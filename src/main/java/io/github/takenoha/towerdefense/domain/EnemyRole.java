package io.github.takenoha.towerdefense.domain;

import java.util.Locale;
import java.util.Objects;

/** Role assigned to one logical event enemy for movement and terrain decisions. */
public enum EnemyRole {
    NORMAL("FOUNDATION_NORMAL", 1.0d),
    DESTROYER("FOUNDATION_DESTROYER", 1.15d),
    BUILDER("FOUNDATION_BUILDER", 0.9d),
    BOSS("FOUNDATION_BOSS", 0.85d);

    private final String ledgerType;
    private final double speedMultiplier;

    EnemyRole(String ledgerType, double speedMultiplier) {
        this.ledgerType = ledgerType;
        this.speedMultiplier = speedMultiplier;
    }

    public String ledgerType() {
        return ledgerType;
    }

    /** Returns the PDC-safe role identifier. */
    public String id() {
        return name();
    }

    /** Converts a persisted role identifier and rejects unknown values. */
    public static EnemyRole fromId(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidRole) {
            throw new IllegalArgumentException("unknown enemy role: " + value, invalidRole);
        }
    }

    /** Applies the bounded role speed multiplier to a validated base speed. */
    public double navigationSpeed(double baseSpeed) {
        if (!Double.isFinite(baseSpeed) || baseSpeed <= 0.0d) {
            throw new IllegalArgumentException("baseSpeed must be finite and positive");
        }
        double result = baseSpeed * speedMultiplier;
        if (!Double.isFinite(result) || result <= 0.0d) {
            throw new IllegalArgumentException("role navigation speed is not finite");
        }
        return result;
    }

    /** Whether the role may perform the supplied terrain action when explicitly selected. */
    public boolean allowsTerrainAction(EnemyTerrainActionKind action, boolean fallbackEligible) {
        Objects.requireNonNull(action, "action");
        return switch (this) {
            case NORMAL -> action == EnemyTerrainActionKind.BREAK && fallbackEligible;
            case DESTROYER -> action == EnemyTerrainActionKind.BREAK;
            case BUILDER -> action == EnemyTerrainActionKind.BUILD;
            case BOSS -> false;
        };
    }
}
