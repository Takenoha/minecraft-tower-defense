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
                reader.enemies());

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
                    integer(values, "core", "damage-per-enemy"));
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
                    decimal(values, "enemies", "move-speed"));
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

        private double decimal(Map<?, ?> section, String sectionName, String key) {
            String path = sectionName + "." + key;
            Object value = section.get(key);
            if (!(value instanceof Number number)) {
                addProblem(path, value == null ? "is required" : "must be a number");
                return Double.NaN;
            }
            return number.doubleValue();
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
