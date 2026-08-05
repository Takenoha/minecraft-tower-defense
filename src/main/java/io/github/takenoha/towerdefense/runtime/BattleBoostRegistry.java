package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.persistence.BattleBoost;
import io.github.takenoha.towerdefense.persistence.BattleBoostKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Main-thread cache of the event-scoped boosts used by the Paper combat loop. */
public final class BattleBoostRegistry {
    private final Map<UUID, Map<BattleBoostKind, BattleBoost>> byTower = new HashMap<>();

    public void replaceAll(List<BattleBoost> boosts) {
        Objects.requireNonNull(boosts, "boosts");
        byTower.clear();
        boosts.forEach(this::replace);
    }

    public void replace(BattleBoost boost) {
        Objects.requireNonNull(boost, "boost");
        byTower.computeIfAbsent(boost.towerId(), ignored -> new HashMap<>())
                .put(boost.kind(), boost);
    }

    public void clear() {
        byTower.clear();
    }

    public boolean isEmpty() {
        return byTower.isEmpty();
    }

    public int level(UUID towerId, BattleBoostKind kind) {
        return find(towerId, kind).map(BattleBoost::level).orElse(0);
    }

    public double multiplier(UUID towerId, BattleBoostKind kind) {
        return find(towerId, kind).map(BattleBoost::multiplier).orElse(1.0d);
    }

    private java.util.Optional<BattleBoost> find(UUID towerId, BattleBoostKind kind) {
        Map<BattleBoostKind, BattleBoost> boosts = byTower.get(towerId);
        return boosts == null
                ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(boosts.get(kind));
    }
}
