package io.github.takenoha.towerdefense.config;

import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.domain.WaveMutation;
import io.github.takenoha.towerdefense.domain.WaveMutationSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps generic YAML-like values to settings records without importing Paper configuration types. */
final class PluginSettingsMapReader {
    private PluginSettingsMapReader() {
    }

    static PluginSettings read(Map<String, ?> values) {
        Reader reader = new Reader(values);
        PluginSettings settings = new PluginSettings(
                reader.combat(),
                reader.core(),
                reader.enemies(),
                reader.protection(),
                reader.rewards(),
                reader.terrainMutation(),
                reader.towers(),
                reader.waveMutations());

        List<String> violations = new ArrayList<>(reader.problems());
        violations.addAll(PluginSettingsValidator.violations(settings, reader.unreadablePaths()));
        if (!violations.isEmpty()) {
            throw new InvalidPluginSettingsException(violations);
        }
        return settings;
    }

    private static final class Reader {
        private final Map<?, ?> root;
        private final List<String> problems = new ArrayList<>();
        private final Set<String> unreadablePaths = new LinkedHashSet<>();

        private Reader(Map<String, ?> values) {
            if (values == null) {
                root = Map.of();
                addProblem("settings", "must not be null");
            } else {
                root = values;
            }
        }

        private CombatSettings combat() {
            Map<?, ?> values = section("combat");
            if (values == null) {
                return null;
            }
            return new CombatSettings(
                    decimal(values, "combat", "radius"),
                    decimal(values, "combat", "spawn-inner"),
                    decimal(values, "combat", "spawn-outer"),
                    decimal(values, "combat", "minimum-core-distance"),
                    decimal(values, "combat", "core-gap"),
                    integer(values, "combat", "max-participants"),
                    integer(values, "combat", "countdown-seconds"),
                    integer(values, "combat", "preparation-seconds"),
                    integer(values, "combat", "intermission-seconds"),
                    integer(values, "combat", "absence-grace-seconds"));
        }

        private CoreSettings core() {
            Map<?, ?> values = section("core");
            if (values == null) {
                return null;
            }
            return new CoreSettings(
                    integer(values, "core", "max-health"),
                    integer(values, "core", "damage-per-enemy"),
                    integerOrDefault(
                            values,
                            "core",
                            "attack-interval-ticks",
                            CoreSettings.DEFAULT_ATTACK_INTERVAL_TICKS),
                    textOrDefault(
                            values,
                            "core",
                            "repair-material",
                            CoreSettings.DEFAULT_REPAIR_MATERIAL),
                    integerOrDefault(
                            values,
                            "core",
                            "repair-health-per-unit",
                            CoreSettings.DEFAULT_REPAIR_HEALTH_PER_UNIT),
                    integerOrDefault(
                            values,
                            "core",
                            "repair-material-base-cost",
                            CoreSettings.DEFAULT_REPAIR_MATERIAL_BASE_COST),
                    integerOrDefault(
                            values,
                            "core",
                            "repair-shard-base-cost",
                            CoreSettings.DEFAULT_REPAIR_SHARD_BASE_COST),
                    integerOrDefault(
                            values,
                            "core",
                            "repair-cost-per-clear-level",
                            CoreSettings.DEFAULT_REPAIR_COST_PER_CLEAR_LEVEL),
                    textOrDefault(
                            values,
                            "core",
                            "warning-sound",
                            CoreSettings.DEFAULT_WARNING_SOUND),
                    decimalOrDefault(
                            values,
                            "core",
                            "warning-volume",
                            CoreSettings.DEFAULT_WARNING_VOLUME),
                    decimalOrDefault(
                            values,
                            "core",
                            "warning-pitch",
                            CoreSettings.DEFAULT_WARNING_PITCH),
                    integerOrDefault(
                            values,
                            "core",
                            "warning-min-interval-ticks",
                            CoreSettings.DEFAULT_WARNING_MIN_INTERVAL_TICKS));
        }

        private EnemySettings enemies() {
            Map<?, ?> values = section("enemies");
            if (values == null) {
                return null;
            }
            return new EnemySettings(
                    integer(values, "enemies", "max-alive"),
                    integer(values, "enemies", "spawn-per-tick"),
                    integer(values, "enemies", "base-per-wave"),
                    integer(values, "enemies", "added-per-wave"),
                    decimal(values, "enemies", "boss-health-multiplier"),
                    decimal(values, "enemies", "move-speed"),
                    decimalOrDefault(
                            values,
                            "enemies",
                            "destroyer-ratio",
                            EnemySettings.DEFAULT_DESTROYER_RATIO),
                    decimalOrDefault(
                            values,
                            "enemies",
                            "builder-ratio",
                            EnemySettings.DEFAULT_BUILDER_RATIO),
                    decimalOrDefault(
                            values,
                            "enemies",
                            "speedster-ratio",
                            EnemySettings.DEFAULT_SPEEDSTER_RATIO),
                    decimalOrDefault(
                            values,
                            "enemies",
                            "ranged-ratio",
                            EnemySettings.DEFAULT_RANGED_RATIO),
                    decimalOrDefault(
                            values,
                            "enemies",
                            "heavy-ratio",
                            EnemySettings.DEFAULT_HEAVY_RATIO),
                    integerOrDefault(
                            values,
                            "enemies",
                            "tower-attack-damage",
                            EnemySettings.DEFAULT_TOWER_ATTACK_DAMAGE),
                    integerOrDefault(
                            values,
                            "enemies",
                            "tower-attack-interval-ticks",
                            EnemySettings.DEFAULT_TOWER_ATTACK_INTERVAL_TICKS),
                    decimalOrDefault(
                            values,
                            "enemies",
                            "tower-attack-range",
                            EnemySettings.DEFAULT_TOWER_ATTACK_RANGE));
        }

        private ProtectionSettings protection() {
            Object raw = root.get("protection");
            if (raw == null) {
                return ProtectionSettings.empty();
            }
            if (!(raw instanceof Map<?, ?> values)) {
                addProblem("protection", "must be a map");
                return ProtectionSettings.empty();
            }
            return new ProtectionSettings(
                    forbiddenWorlds(values),
                    forbiddenRegions(values));
        }

        private RewardSettings rewards() {
            Object raw = root.get("rewards");
            if (raw == null) {
                return RewardSettings.defaults();
            }
            if (!(raw instanceof Map<?, ?> values)) {
                addProblem("rewards", "must be a map");
                return RewardSettings.defaults();
            }
            return new RewardSettings(
                    integer(values, "rewards", "team-queue-retention-seconds"),
                    integerOrDefault(
                            values,
                            "rewards",
                            "research-crystal-base-per-stage",
                            RewardSettings.DEFAULT_RESEARCH_CRYSTAL_BASE_PER_STAGE),
                    integerOrDefault(
                            values,
                            "rewards",
                            "research-crystal-replay-percent",
                            RewardSettings.DEFAULT_RESEARCH_CRYSTAL_REPLAY_PERCENT),
                    integerOrDefault(
                            values,
                            "rewards",
                            "research-crystal-minimum-quantity",
                            RewardSettings.DEFAULT_RESEARCH_CRYSTAL_MINIMUM_QUANTITY),
                    integerOrDefault(
                            values,
                            "rewards",
                            "battle-funds-normal-enemy",
                            RewardSettings.DEFAULT_BATTLE_FUNDS_NORMAL_ENEMY),
                    integerOrDefault(
                            values,
                            "rewards",
                            "battle-funds-special-enemy",
                            RewardSettings.DEFAULT_BATTLE_FUNDS_SPECIAL_ENEMY),
                    integerOrDefault(
                            values,
                            "rewards",
                            "battle-funds-boss-enemy",
                            RewardSettings.DEFAULT_BATTLE_FUNDS_BOSS_ENEMY),
                    integerOrDefault(
                            values,
                            "rewards",
                            "battle-funds-per-wave",
                            RewardSettings.DEFAULT_BATTLE_FUNDS_PER_WAVE),
                    integerOrDefault(
                            values,
                            "rewards",
                            "defense-shards-normal-enemy",
                            RewardSettings.DEFAULT_DEFENSE_SHARDS_NORMAL_ENEMY),
                    integerOrDefault(
                            values,
                            "rewards",
                            "defense-shards-special-enemy",
                            RewardSettings.DEFAULT_DEFENSE_SHARDS_SPECIAL_ENEMY),
                    integerOrDefault(
                            values,
                            "rewards",
                            "enhancement-core-drop-percent",
                            RewardSettings.DEFAULT_ENHANCEMENT_CORE_DROP_PERCENT),
                    booleanOrDefault(
                            values,
                            "rewards",
                            "legacy-resource-payments-enabled",
                            true));
        }

        private TerrainMutationSettings terrainMutation() {
            Object raw = root.get("terrain-mutation");
            if (raw == null) {
                return TerrainMutationSettings.disabled();
            }
            if (!(raw instanceof Map<?, ?> values)) {
                addProblem("terrain-mutation", "must be a map");
                return TerrainMutationSettings.disabled();
            }
            return new TerrainMutationSettings(
                    booleanOrDefault(values, "terrain-mutation", "requested", false),
                    booleanOrDefault(
                            values,
                            "terrain-mutation",
                            "paper-integration-verified",
                            false),
                    booleanOrDefault(
                            values,
                            "terrain-mutation",
                            "recovery-verified",
                            false));
        }

        private WaveMutationSettings waveMutations() {
            Object raw = root.get("wave-mutations");
            if (raw == null) {
                return WaveMutationSettings.defaults();
            }
            if (!(raw instanceof Map<?, ?> values)) {
                addProblem("wave-mutations", "must be a map");
                return WaveMutationSettings.defaults();
            }
            WaveMutationSettings defaults = WaveMutationSettings.defaults();
            Map<?, ?> swift = nestedSection(values, "wave-mutations", "swift");
            Map<?, ?> fortified = nestedSection(values, "wave-mutations", "fortified");
            Map<?, ?> reinforcements = nestedSection(
                    values, "wave-mutations", "reinforcements");
            return new WaveMutationSettings(
                    booleanOrDefault(values, "wave-mutations", "enabled", true),
                    mutationProfile(
                            swift, "wave-mutations.swift", defaults.swift(), WaveMutation.SWIFT),
                    mutationProfile(
                            fortified,
                            "wave-mutations.fortified",
                            defaults.fortified(),
                            WaveMutation.FORTIFIED),
                    mutationProfile(
                            reinforcements,
                            "wave-mutations.reinforcements",
                            defaults.reinforcements(),
                            WaveMutation.REINFORCEMENTS));
        }

        private WaveMutationSnapshot mutationProfile(
                Map<?, ?> values,
                String path,
                WaveMutationSnapshot defaults,
                WaveMutation mutation) {
            if (values == null) {
                return defaults;
            }
            return new WaveMutationSnapshot(
                    mutation,
                    positiveMultiplier(
                            values,
                            path,
                            "enemy-speed-multiplier",
                            defaults.enemySpeedMultiplier()),
                    positiveMultiplier(
                            values,
                            path,
                            "enemy-health-multiplier",
                            defaults.enemyHealthMultiplier()),
                    positiveMultiplier(
                            values,
                            path,
                            "enemy-count-multiplier",
                            defaults.enemyCountMultiplier()),
                    positiveMultiplier(
                            values,
                            path,
                            "reward-multiplier",
                            defaults.rewardMultiplier()));
        }

        private double positiveMultiplier(
                Map<?, ?> values,
                String path,
                String key,
                double defaultValue) {
            double value = decimalOrDefault(values, path, key, defaultValue);
            if (!Double.isFinite(value) || value <= 0.0d) {
                addProblem(
                        path + "." + key,
                        "must be finite and > 0 (was " + value + ")");
                return defaultValue;
            }
            return value;
        }

        private TowerSettings towers() {
            Object raw = root.get("towers");
            if (raw == null) {
                return TowerSettings.defaults();
            }
            if (!(raw instanceof Map<?, ?> values)) {
                addProblem("towers", "must be a map");
                return TowerSettings.defaults();
            }
            Map<?, ?> arrow = nestedSection(values, "towers", "arrow");
            if (arrow == null) {
                arrow = Map.of();
            }
            Map<?, ?> cannon = nestedSection(values, "towers", "cannon");
            if (cannon == null) {
                cannon = Map.of();
            }
            Map<?, ?> upgrade = nestedSection(values, "towers", "upgrade");
            if (upgrade == null) {
                upgrade = Map.of();
            }
            Map<?, ?> battleBoost = nestedSection(values, "towers", "battle-boost");
            if (battleBoost == null) {
                battleBoost = Map.of();
            }
            Map<?, ?> frost = nestedSection(values, "towers", "frost");
            if (frost == null) {
                frost = Map.of();
            }
            Map<?, ?> lightning = nestedSection(values, "towers", "lightning");
            if (lightning == null) {
                lightning = Map.of();
            }
            Map<?, ?> support = nestedSection(values, "towers", "support");
            if (support == null) {
                support = Map.of();
            }
            Map<?, ?> sniper = nestedSection(values, "towers", "sniper");
            if (sniper == null) {
                sniper = Map.of();
            }
            Map<?, ?> flame = nestedSection(values, "towers", "flame");
            if (flame == null) {
                flame = Map.of();
            }
            return new TowerSettings(
                    integerOrDefault(
                            values,
                            "towers",
                            "base-limit",
                            TowerSettings.DEFAULT_BASE_LIMIT),
                    integerOrDefault(
                            values,
                            "towers",
                            "limit-increment",
                            TowerSettings.DEFAULT_LIMIT_INCREMENT),
                    integerOrDefault(
                            values,
                            "towers",
                            "hard-cap",
                            TowerSettings.DEFAULT_HARD_CAP),
                    integerOrDefault(
                            values,
                            "towers",
                            "max-health",
                            TowerSettings.DEFAULT_TOWER_MAXIMUM_HIT_POINTS),
                    integerOrDefault(
                            arrow,
                            "towers.arrow",
                            "damage",
                            TowerSettings.DEFAULT_ARROW_DAMAGE),
                    decimalOrDefault(
                            arrow,
                            "towers.arrow",
                            "range",
                            TowerSettings.DEFAULT_ARROW_RANGE),
                    integerOrDefault(
                            arrow,
                            "towers.arrow",
                            "attack-interval-ticks",
                            TowerSettings.DEFAULT_ARROW_ATTACK_INTERVAL_TICKS),
                    integerOrDefault(
                            cannon,
                            "towers.cannon",
                            "damage",
                            TowerSettings.DEFAULT_CANNON_DAMAGE),
                    decimalOrDefault(
                            cannon,
                            "towers.cannon",
                            "range",
                            TowerSettings.DEFAULT_CANNON_RANGE),
                    integerOrDefault(
                            cannon,
                            "towers.cannon",
                            "attack-interval-ticks",
                            TowerSettings.DEFAULT_CANNON_ATTACK_INTERVAL_TICKS),
                    decimalOrDefault(
                            cannon,
                            "towers.cannon",
                            "splash-radius",
                            TowerSettings.DEFAULT_CANNON_SPLASH_RADIUS),
                    integerOrDefault(
                            upgrade,
                            "towers.upgrade",
                            "base-shard-cost",
                            TowerSettings.DEFAULT_INDIVIDUAL_UPGRADE_BASE_SHARD_COST),
                    integerOrDefault(
                            upgrade,
                            "towers.upgrade",
                            "base-core-cost",
                            TowerSettings.DEFAULT_INDIVIDUAL_UPGRADE_BASE_CORE_COST),
                    integerOrDefault(
                            upgrade,
                            "towers.upgrade",
                            "shard-cost-per-level",
                            TowerSettings.DEFAULT_INDIVIDUAL_UPGRADE_SHARD_COST_PER_LEVEL),
                    integerOrDefault(
                            upgrade,
                            "towers.upgrade",
                            "core-cost-per-level",
                            TowerSettings.DEFAULT_INDIVIDUAL_UPGRADE_CORE_COST_PER_LEVEL),
                    integerOrDefault(
                            upgrade,
                            "towers.upgrade",
                            "research-base-cost",
                            TowerSettings.DEFAULT_RESEARCH_BASE_COST),
                    integerOrDefault(
                            upgrade,
                            "towers.upgrade",
                            "research-cost-per-level",
                            TowerSettings.DEFAULT_RESEARCH_COST_PER_LEVEL),
                    integerOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "base-cost",
                            TowerSettings.DEFAULT_BATTLE_BOOST_BASE_COST),
                    integerOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "cost-per-level",
                            TowerSettings.DEFAULT_BATTLE_BOOST_COST_PER_LEVEL),
                    decimalOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "power-multiplier",
                            TowerSettings.DEFAULT_BATTLE_BOOST_POWER_MULTIPLIER),
                    decimalOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "speed-multiplier",
                            TowerSettings.DEFAULT_BATTLE_BOOST_SPEED_MULTIPLIER),
                    decimalOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "range-multiplier",
                            TowerSettings.DEFAULT_BATTLE_BOOST_RANGE_MULTIPLIER),
                    integerOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "stack-limit",
                            TowerSettings.DEFAULT_BATTLE_BOOST_STACK_LIMIT),
                    integerOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "funds-per-health",
                            TowerSettings.DEFAULT_BATTLE_REPAIR_FUNDS_PER_HEALTH),
                    integerOrDefault(
                            battleBoost,
                            "towers.battle-boost",
                            "health-per-purchase",
                            TowerSettings.DEFAULT_BATTLE_REPAIR_HEALTH_PER_PURCHASE),
                    Map.of(
                            TowerType.FROST,
                            specialistProfile(
                                    frost,
                                    "towers.frost",
                                    TowerProfile.frostDefaults()),
                            TowerType.LIGHTNING,
                            specialistProfile(
                                    lightning,
                                    "towers.lightning",
                                    TowerProfile.lightningDefaults()),
                            TowerType.SUPPORT,
                            specialistProfile(
                                    support,
                                    "towers.support",
                                    TowerProfile.supportDefaults()),
                            TowerType.SNIPER,
                            specialistProfile(
                                    sniper,
                                    "towers.sniper",
                                    TowerProfile.sniperDefaults()),
                            TowerType.FLAME,
                            specialistProfile(
                                    flame,
                                    "towers.flame",
                                    TowerProfile.flameDefaults())));
        }

        private TowerProfile specialistProfile(
                Map<?, ?> values,
                String path,
                TowerProfile defaults) {
            return new TowerProfile(
                    integerOrDefault(values, path, "damage", defaults.damage()),
                    decimalOrDefault(values, path, "range", defaults.range()),
                    integerOrDefault(
                            values,
                            path,
                            "attack-interval-ticks",
                            defaults.attackIntervalTicks()),
                    decimalOrDefault(values, path, "area-radius", defaults.areaRadius()),
                    decimalOrDefault(values, path, "slow-percent", defaults.slowPercent()),
                    integerOrDefault(
                            values,
                            path,
                            "slow-duration-ticks",
                            defaults.slowDurationTicks()),
                    integerOrDefault(values, path, "chain-count", defaults.chainCount()),
                    decimalOrDefault(values, path, "chain-radius", defaults.chainRadius()),
                    decimalOrDefault(
                            values, path, "support-radius", defaults.supportRadius()),
                    decimalOrDefault(
                            values,
                            path,
                            "support-damage-multiplier",
                            defaults.supportDamageMultiplier()),
                    decimalOrDefault(
                            values,
                            path,
                            "support-speed-multiplier",
                            defaults.supportSpeedMultiplier()),
                    decimalOrDefault(
                            values,
                            path,
                            "support-range-multiplier",
                            defaults.supportRangeMultiplier()),
                    integerOrDefault(
                            values,
                            path,
                            "support-stack-limit",
                            defaults.supportStackLimit()),
                    integerOrDefault(
                            values,
                            path,
                            "burn-duration-ticks",
                            defaults.burnDurationTicks()));
        }

        private Map<?, ?> nestedSection(Map<?, ?> parent, String parentPath, String name) {
            Object raw = parent.get(name);
            if (raw == null) {
                return null;
            }
            if (!(raw instanceof Map<?, ?> values)) {
                addProblem(parentPath + "." + name, "must be a map");
                return null;
            }
            return values;
        }

        private Set<String> forbiddenWorlds(Map<?, ?> section) {
            Object raw = section.get("forbidden-worlds");
            if (raw == null) {
                return Set.of();
            }
            if (!(raw instanceof List<?> values)) {
                addProblem("protection.forbidden-worlds", "must be a list");
                return Set.of();
            }

            Set<String> worlds = new LinkedHashSet<>();
            for (int index = 0; index < values.size(); index++) {
                Object value = values.get(index);
                String path = "protection.forbidden-worlds[" + index + "]";
                if (!(value instanceof String world) || world.isBlank()) {
                    addProblem(path, "must be a non-blank string");
                    continue;
                }
                worlds.add(world);
            }
            return Set.copyOf(worlds);
        }

        private List<ForbiddenRegion> forbiddenRegions(Map<?, ?> section) {
            Object raw = section.get("forbidden-regions");
            if (raw == null) {
                return List.of();
            }
            if (!(raw instanceof List<?> values)) {
                addProblem("protection.forbidden-regions", "must be a list");
                return List.of();
            }

            List<ForbiddenRegion> regions = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                String path = "protection.forbidden-regions[" + index + "]";
                Object value = values.get(index);
                if (!(value instanceof Map<?, ?> region)) {
                    addProblem(path, "must be a map");
                    continue;
                }
                String world = text(region, path, "world");
                Double minX = regionDecimal(region, path, "min-x");
                Double minZ = regionDecimal(region, path, "min-z");
                Double maxX = regionDecimal(region, path, "max-x");
                Double maxZ = regionDecimal(region, path, "max-z");
                if (world != null && minX != null && minZ != null && maxX != null && maxZ != null) {
                    regions.add(new ForbiddenRegion(world, minX, minZ, maxX, maxZ));
                }
            }
            return List.copyOf(regions);
        }

        private String text(Map<?, ?> section, String parentPath, String key) {
            String path = parentPath + "." + key;
            Object value = section.get(key);
            if (!(value instanceof String text) || text.isBlank()) {
                addProblem(path, value == null ? "is required" : "must be a non-blank string");
                return null;
            }
            return text;
        }

        private Double regionDecimal(Map<?, ?> section, String parentPath, String key) {
            String path = parentPath + "." + key;
            Object value = section.get(key);
            if (!(value instanceof Number number)) {
                addProblem(path, value == null ? "is required" : "must be a number");
                return null;
            }
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)) {
                addProblem(path, "must be finite (was " + value + ")");
                return null;
            }
            return decimal;
        }

        private Map<?, ?> section(String name) {
            Object value = root.get(name);
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            addProblem(name, value == null ? "section is required" : "must be a map");
            return null;
        }

        private int integer(Map<?, ?> section, String sectionName, String key) {
            String path = sectionName + "." + key;
            Object value = section.get(key);
            if (!(value instanceof Number number)) {
                addProblem(path, value == null ? "is required" : "must be an integer");
                return 0;
            }

            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)
                    || decimal != Math.rint(decimal)
                    || decimal < Integer.MIN_VALUE
                    || decimal > Integer.MAX_VALUE) {
                addProblem(path, "must be a 32-bit integer (was " + value + ")");
                return 0;
            }
            return (int) decimal;
        }

        private int integerOrDefault(
                Map<?, ?> section,
                String sectionName,
                String key,
                int defaultValue) {
            String path = sectionName + "." + key;
            Object value = section.get(key);
            if (value == null) {
                return defaultValue;
            }
            if (!(value instanceof Number number)) {
                addProblem(path, "must be an integer");
                return defaultValue;
            }

            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)
                    || decimal != Math.rint(decimal)
                    || decimal < Integer.MIN_VALUE
                    || decimal > Integer.MAX_VALUE) {
                addProblem(path, "must be a 32-bit integer (was " + value + ")");
                return defaultValue;
            }
            return (int) decimal;
        }

        private String textOrDefault(
                Map<?, ?> section,
                String sectionName,
                String key,
                String defaultValue) {
            String path = sectionName + "." + key;
            Object value = section.get(key);
            if (value == null) {
                return defaultValue;
            }
            if (!(value instanceof String text) || text.isBlank()) {
                addProblem(path, "must be a non-blank string");
                return defaultValue;
            }
            return text;
        }

        private double decimal(Map<?, ?> section, String sectionName, String key) {
            String path = sectionName + "." + key;
            Object value = section.get(key);
            if (!(value instanceof Number number)) {
                addProblem(path, value == null ? "is required" : "must be a number");
                return Double.NaN;
            }
            return number.doubleValue();
        }

        private double decimalOrDefault(
                Map<?, ?> section,
                String sectionName,
                String key,
                double defaultValue) {
            String path = sectionName + "." + key;
            Object value = section.get(key);
            if (value == null) {
                return defaultValue;
            }
            if (!(value instanceof Number number)) {
                addProblem(path, "must be a number");
                return defaultValue;
            }
            return number.doubleValue();
        }

        private boolean booleanOrDefault(
                Map<?, ?> section,
                String sectionName,
                String key,
                boolean defaultValue) {
            String path = sectionName + "." + key;
            Object value = section.get(key);
            if (value == null) {
                return defaultValue;
            }
            if (!(value instanceof Boolean booleanValue)) {
                addProblem(path, "must be a boolean");
                return defaultValue;
            }
            return booleanValue;
        }

        private void addProblem(String path, String problem) {
            unreadablePaths.add(path);
            problems.add(path + ": " + problem);
        }

        private List<String> problems() {
            return List.copyOf(problems);
        }

        private Set<String> unreadablePaths() {
            return Set.copyOf(unreadablePaths);
        }
    }
}
