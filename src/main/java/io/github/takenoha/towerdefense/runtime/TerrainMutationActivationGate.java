package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.config.TerrainMutationSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed activation gate for enemy terrain mutation.
 *
 * <p>The operator request is one gate; independent Paper integration and recovery evidence are
 * the second gate. A missing or stale evidence flag leaves the policy disabled. This class does
 * not run a test or infer evidence from a successful server start; the flags are explicit
 * operator attestations recorded in configuration after the reviewed test procedure completes.
 */
public final class TerrainMutationActivationGate {
    private final TerrainMutationSettings settings;

    public TerrainMutationActivationGate(TerrainMutationSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Returns true only when every independent activation input is true. */
    public boolean enabled() {
        return settings.requested()
                && settings.paperIntegrationVerified()
                && settings.recoveryVerified();
    }

    /** Returns the exact inputs that still block activation. */
    public List<String> blockers() {
        List<String> blockers = new ArrayList<>();
        if (!settings.requested()) {
            blockers.add("requested=false");
        }
        if (!settings.paperIntegrationVerified()) {
            blockers.add("paper-integration-verified=false");
        }
        if (!settings.recoveryVerified()) {
            blockers.add("recovery-verified=false");
        }
        return List.copyOf(blockers);
    }

    /** Returns a compact value suitable for logs and administrator status output. */
    public String status() {
        return enabled() ? "enabled" : "disabled(" + String.join(",", blockers()) + ")";
    }
}
