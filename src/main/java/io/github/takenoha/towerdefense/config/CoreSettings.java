package io.github.takenoha.towerdefense.config;

/** Core health, attack cadence, and idle repair settings for a defense encounter. */
public record CoreSettings(
        int maxHealth,
        int damagePerEnemy,
        int attackIntervalTicks,
        String repairMaterial,
        int repairHealthPerUnit,
        int repairMaterialBaseCost,
        int repairShardBaseCost,
        int repairCostPerClearLevel,
        String warningSound,
        double warningVolume,
        double warningPitch,
        int warningMinIntervalTicks) {
    public static final int DEFAULT_ATTACK_INTERVAL_TICKS = 20;
    public static final String DEFAULT_REPAIR_MATERIAL = "IRON_INGOT";
    public static final int DEFAULT_REPAIR_HEALTH_PER_UNIT = 100;
    public static final int DEFAULT_REPAIR_MATERIAL_BASE_COST = 1;
    public static final int DEFAULT_REPAIR_SHARD_BASE_COST = 1;
    public static final int DEFAULT_REPAIR_COST_PER_CLEAR_LEVEL = 1;
    public static final String DEFAULT_WARNING_SOUND = "ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR";
    public static final double DEFAULT_WARNING_VOLUME = 1.0d;
    public static final double DEFAULT_WARNING_PITCH = 1.0d;
    public static final int DEFAULT_WARNING_MIN_INTERVAL_TICKS = 10;

    public CoreSettings(
            int maxHealth,
            int damagePerEnemy,
            int attackIntervalTicks) {
        this(
                maxHealth,
                damagePerEnemy,
                attackIntervalTicks,
                DEFAULT_REPAIR_MATERIAL,
                DEFAULT_REPAIR_HEALTH_PER_UNIT,
                DEFAULT_REPAIR_MATERIAL_BASE_COST,
                DEFAULT_REPAIR_SHARD_BASE_COST,
                DEFAULT_REPAIR_COST_PER_CLEAR_LEVEL,
                DEFAULT_WARNING_SOUND,
                DEFAULT_WARNING_VOLUME,
                DEFAULT_WARNING_PITCH,
                DEFAULT_WARNING_MIN_INTERVAL_TICKS);
    }

    /** Keeps direct settings construction source-compatible with the original two fields. */
    public CoreSettings(int maxHealth, int damagePerEnemy) {
        this(maxHealth, damagePerEnemy, DEFAULT_ATTACK_INTERVAL_TICKS);
    }

    public CoreSettings(
            int maxHealth,
            int damagePerEnemy,
            int attackIntervalTicks,
            String repairMaterial,
            int repairHealthPerUnit,
            int repairMaterialBaseCost,
            int repairShardBaseCost,
            int repairCostPerClearLevel) {
        this(
                maxHealth,
                damagePerEnemy,
                attackIntervalTicks,
                repairMaterial,
                repairHealthPerUnit,
                repairMaterialBaseCost,
                repairShardBaseCost,
                repairCostPerClearLevel,
                DEFAULT_WARNING_SOUND,
                DEFAULT_WARNING_VOLUME,
                DEFAULT_WARNING_PITCH,
                DEFAULT_WARNING_MIN_INTERVAL_TICKS);
    }

    public CoreSettings(
            int maxHealth,
            int damagePerEnemy,
            int attackIntervalTicks,
            String repairMaterial,
            int repairHealthPerUnit,
            int repairMaterialBaseCost,
            int repairShardBaseCost,
            int repairCostPerClearLevel,
            String warningSound,
            double warningVolume,
            double warningPitch,
            int warningMinIntervalTicks) {
        this.maxHealth = maxHealth;
        this.damagePerEnemy = damagePerEnemy;
        this.attackIntervalTicks = attackIntervalTicks;
        this.repairMaterial = java.util.Objects.requireNonNull(repairMaterial, "repairMaterial");
        this.repairHealthPerUnit = repairHealthPerUnit;
        this.repairMaterialBaseCost = repairMaterialBaseCost;
        this.repairShardBaseCost = repairShardBaseCost;
        this.repairCostPerClearLevel = repairCostPerClearLevel;
        this.warningSound = java.util.Objects.requireNonNull(warningSound, "warningSound");
        this.warningVolume = warningVolume;
        this.warningPitch = warningPitch;
        this.warningMinIntervalTicks = warningMinIntervalTicks;
    }
}
