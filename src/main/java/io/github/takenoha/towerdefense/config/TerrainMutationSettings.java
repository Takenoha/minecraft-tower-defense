package io.github.takenoha.towerdefense.config;

/**
 * Operator-controlled evidence flags for the experimental terrain-mutation path.
 *
 * <p>All flags default to {@code false}. They are deliberately kept separate so that an
 * operator cannot accidentally treat a Paper integration check as proof that recovery is safe.
 */
public record TerrainMutationSettings(
        boolean requested,
        boolean paperIntegrationVerified,
        boolean recoveryVerified) {

    /** Returns the fail-closed default used by older configurations and direct constructors. */
    public static TerrainMutationSettings disabled() {
        return new TerrainMutationSettings(false, false, false);
    }
}
