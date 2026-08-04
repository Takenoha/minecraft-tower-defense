package io.github.takenoha.towerdefense.config;

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
                reader.towers());

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
                            CoreSettings.DEFAULT_REPAIR_COST_PER_CLEAR_LEVEL));
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
                            EnemySettings.DEFAULT_BUILDER_RATIO));
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
                    integer(values, "rewards", "team-queue-retention-seconds"));
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
                            TowerSettings.DEFAULT_ARROW_ATTACK_INTERVAL_TICKS));
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
