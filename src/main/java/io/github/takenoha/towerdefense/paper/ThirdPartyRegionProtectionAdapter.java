package io.github.takenoha.towerdefense.paper;

import java.util.List;
import java.util.Objects;
import org.bukkit.World;

/** Main-thread adapter for an optional third-party region protection plugin. */
@FunctionalInterface
public interface ThirdPartyRegionProtectionAdapter {
    /** Returns violations when the combat circle overlaps protected third-party regions. */
    List<String> violations(
            World world,
            double centerX,
            double centerZ,
            double radius);

    /** Returns an adapter for servers without a third-party region plugin. */
    static ThirdPartyRegionProtectionAdapter none() {
        return (world, centerX, centerZ, radius) -> List.of();
    }

    /** Returns a fail-closed adapter for an installed but unavailable integration. */
    static ThirdPartyRegionProtectionAdapter unavailable(String reason) {
        String message = Objects.requireNonNull(reason, "reason");
        return (world, centerX, centerZ, radius) -> List.of(message);
    }
}
