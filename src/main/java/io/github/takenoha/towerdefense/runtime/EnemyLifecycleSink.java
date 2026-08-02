package io.github.takenoha.towerdefense.runtime;

import org.bukkit.entity.Entity;

/** Receives main-thread physical enemy lifecycle callbacks. */
@FunctionalInterface
public interface EnemyLifecycleSink {
    void onDefeated(Entity entity, TaggedEnemy taggedEnemy);
}

