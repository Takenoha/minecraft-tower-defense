package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.EnemyAccessPolicy;
import io.github.takenoha.towerdefense.runtime.EnemyPathMetrics;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Read-only Paper integration seam for path inspection and load measurement.
 *
 * <p>All Bukkit reads remain on the main thread. A runtime Paper read failure becomes an
 * unavailable obstacle instead of becoming a terrain action, while the failure and elapsed time
 * remain visible in the in-memory metrics.</p>
 */
public final class PaperEnemyPathIntegrationBoundary {
    private final CoreRegistry cores;
    private final EnemyAccessPolicy accessPolicy;

    public PaperEnemyPathIntegrationBoundary(
            CoreRegistry cores,
            EnemyAccessPolicy accessPolicy) {
        this.cores = Objects.requireNonNull(cores, "cores");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    public EnemyObstacleFacts inspect(
            Entity entity,
            Location destination,
            EnemyRole role,
            EnemyPathMetrics metrics) {
        requireMainThread();
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(metrics, "metrics");
        long startedAt = System.nanoTime();
        try {
            return PaperEnemyPathController.inspect(
                    entity,
                    destination,
                    role,
                    cores,
                    accessPolicy);
        } catch (RuntimeException paperFailure) {
            metrics.recordInspectionFailure();
            return EnemyObstacleFacts.unavailable();
        } finally {
            long elapsed = System.nanoTime() - startedAt;
            metrics.recordInspection(Math.max(0L, elapsed));
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Paper enemy path integration must run on the main thread");
        }
    }
}
