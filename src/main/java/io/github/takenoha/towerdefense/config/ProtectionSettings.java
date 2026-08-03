package io.github.takenoha.towerdefense.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** World and region deny-lists used by the pre-start combat-area safety check. */
public record ProtectionSettings(
        Set<String> forbiddenWorlds,
        List<ForbiddenRegion> forbiddenRegions) {

    public ProtectionSettings {
        if (forbiddenWorlds != null) {
            forbiddenWorlds = Set.copyOf(forbiddenWorlds);
        }
        if (forbiddenRegions != null) {
            forbiddenRegions = List.copyOf(forbiddenRegions);
        }
    }

    /** Returns an empty deny-list for backwards-compatible settings construction. */
    public static ProtectionSettings empty() {
        return new ProtectionSettings(Set.of(), List.of());
    }

    /** Returns whether a world name is present in the case-insensitive deny-list. */
    public boolean forbidsWorld(String worldName) {
        if (worldName == null || forbiddenWorlds == null) {
            return false;
        }
        String normalized = worldName.toLowerCase(Locale.ROOT);
        return forbiddenWorlds.stream()
                .filter(value -> value != null)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }
}
