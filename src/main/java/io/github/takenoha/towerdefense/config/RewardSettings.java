package io.github.takenoha.towerdefense.config;

import java.time.Duration;

/** Durable reward-delivery timing settings. */
public record RewardSettings(int teamQueueRetentionSeconds) {
    /** Default retention used by older direct settings construction and migrated databases. */
    public static RewardSettings defaults() {
        return new RewardSettings(7 * 24 * 60 * 60);
    }

    public Duration teamQueueRetention() {
        return Duration.ofSeconds(teamQueueRetentionSeconds);
    }
}
