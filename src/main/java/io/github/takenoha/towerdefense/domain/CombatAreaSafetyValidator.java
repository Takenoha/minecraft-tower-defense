package io.github.takenoha.towerdefense.domain;

import io.github.takenoha.towerdefense.config.ForbiddenRegion;
import io.github.takenoha.towerdefense.config.ProtectionSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the immutable inputs required before a combat area can be started.
 * This class deliberately models no database state: callers can run it before taking the
 * single event lock and can repeat it after loading the world on the Paper main thread.
 */
public final class CombatAreaSafetyValidator {
    private CombatAreaSafetyValidator() {
    }

    /**
     * Returns all safety violations for the requested combat area.
     * An empty result means the area is safe with respect to the configured deny-list and border.
     */
    public static List<String> violations(
            String worldName,
            double centerX,
            double centerZ,
            CombatArea combatArea,
            ProtectionSettings protection,
            WorldBorderSnapshot worldBorder) {
        List<String> violations = new ArrayList<>();
        if (worldName == null || worldName.isBlank()) {
            violations.add("world: must be present");
        }
        if (combatArea == null) {
            violations.add("combat-area: must be present");
        }
        if (protection == null) {
            violations.add("protection: must be present");
        }
        if (worldBorder == null) {
            violations.add("world-border: must be present");
        }
        if (!violations.isEmpty()) {
            return List.copyOf(violations);
        }

        if (protection.forbidsWorld(worldName)) {
            violations.add("protection.forbidden-worlds: world is forbidden (" + worldName + ")");
        }
        if (protection.forbiddenRegions() != null) {
            for (int index = 0; index < protection.forbiddenRegions().size(); index++) {
                ForbiddenRegion region = protection.forbiddenRegions().get(index);
                if (region != null
                        && region.intersectsCircle(
                                worldName,
                                centerX,
                                centerZ,
                                combatArea.radius())) {
                    violations.add(
                            "protection.forbidden-regions[" + index
                                    + "]: combat area intersects forbidden region");
                }
            }
        }
        if (!worldBorder.containsCircle(centerX, centerZ, combatArea.radius())) {
            violations.add("world-border: combat area must fit entirely inside the world border");
        }
        return List.copyOf(violations);
    }
}
