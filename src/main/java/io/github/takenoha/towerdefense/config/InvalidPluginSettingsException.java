package io.github.takenoha.towerdefense.config;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/** Raised once with every configuration violation found in a settings snapshot. */
public final class InvalidPluginSettingsException extends IllegalArgumentException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ArrayList<String> violations;

    public InvalidPluginSettingsException(List<String> violations) {
        super(messageFor(violations));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("At least one settings violation is required");
        }
        this.violations = new ArrayList<>(violations);
    }

    /** Returns the violations in deterministic validation order. */
    public List<String> violations() {
        return List.copyOf(violations);
    }

    private static String messageFor(List<String> violations) {
        int count = violations.size();
        String heading = "Invalid plugin settings (" + count + " violation"
                + (count == 1 ? "" : "s") + "):";
        return heading + System.lineSeparator() + " - "
                + String.join(System.lineSeparator() + " - ", violations);
    }
}
