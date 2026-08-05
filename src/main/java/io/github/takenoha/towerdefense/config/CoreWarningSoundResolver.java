package io.github.takenoha.towerdefense.config;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.Sound;

/** Resolves a configured Paper sound by enum-style name or registry key. */
public final class CoreWarningSoundResolver {
    private CoreWarningSoundResolver() {
    }

    @SuppressWarnings("removal")
    public static Optional<Sound> resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        String normalized = configured.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("MINECRAFT:")) {
            normalized = normalized.substring("MINECRAFT:".length());
        }
        normalized = normalized.replace('.', '_');
        try {
            return Optional.of(Sound.valueOf(normalized));
        } catch (IllegalArgumentException invalidSound) {
            return Optional.empty();
        }
    }
}
