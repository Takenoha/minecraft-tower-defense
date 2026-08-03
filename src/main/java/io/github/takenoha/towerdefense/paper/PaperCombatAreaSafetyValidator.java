package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.ProtectionSettings;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.CombatAreaSafetyValidator;
import io.github.takenoha.towerdefense.domain.WorldBorderSnapshot;
import java.util.List;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Location;

/** Adapts Paper's loaded-world border to the Paper-independent safety validator. */
public final class PaperCombatAreaSafetyValidator {
    private PaperCombatAreaSafetyValidator() {
    }

    /** Returns start/placement violations for a combat circle centered in the loaded world. */
    public static List<String> violations(
            World world,
            double centerX,
            double centerZ,
            CombatArea combatArea,
            ProtectionSettings protection) {
        if (world == null) {
            return List.of("world: is not loaded");
        }
        Objects.requireNonNull(combatArea, "combatArea");
        Objects.requireNonNull(protection, "protection");
        WorldBorder border = Objects.requireNonNull(world.getWorldBorder(), "world border");
        Location borderCenter = Objects.requireNonNull(border.getCenter(), "world border center");
        WorldBorderSnapshot snapshot = new WorldBorderSnapshot(
                borderCenter.getX(),
                borderCenter.getZ(),
                border.getSize());
        return CombatAreaSafetyValidator.violations(
                world.getName(),
                centerX,
                centerZ,
                combatArea,
                protection,
                snapshot);
    }
}
