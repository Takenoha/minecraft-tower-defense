package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.takenoha.towerdefense.domain.TowerType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalEffectCacheTest {
    @Test
    void rebuildsFromSelectedSnapshotAndInvalidatesAtTerminal() {
        UUID defenseId = UUID.randomUUID();
        TacticalBuildSelectionView selection = new TacticalBuildSelectionView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "cache-test",
                1,
                1,
                List.of(new TacticalSkillNodeSnapshot(
                        "damage",
                        1,
                        1,
                        "Damage",
                        "test",
                        List.of(new TacticalEffectEntry(
                                TacticalEffectType.DAMAGE_MULTIPLIER,
                                Set.of(TowerType.ARROW),
                                1.25d,
                                TacticalTargetCondition.NONE,
                                null,
                                null)))));
        TacticalEffectCache cache = new TacticalEffectCache(
                ignored -> Optional.of(selection));

        cache.rebuild(defenseId);

        assertEquals(1, cache.size());
        assertEquals(1.25d, cache.currentForDefense(defenseId).damageMultiplier(
                TowerType.ARROW, TacticalTargetContext.neutral()), 0.000001d);

        cache.invalidate(defenseId);

        assertEquals(0, cache.size());
        assertSame(
                EmptyTacticalEffectSnapshot.INSTANCE,
                cache.currentForDefense(defenseId));
    }

    @Test
    void missingStateIsFailClosedAndHotPathDoesNotCallStateProvider() {
        UUID defenseId = UUID.randomUUID();
        int[] providerCalls = {0};
        TacticalEffectCache cache = new TacticalEffectCache(ignored -> {
            providerCalls[0]++;
            return Optional.empty();
        });

        cache.rebuild(defenseId);
        TacticalEffectSnapshot first = cache.currentForDefense(defenseId);
        TacticalEffectSnapshot second = cache.currentForDefense(defenseId);

        assertSame(EmptyTacticalEffectSnapshot.INSTANCE, first);
        assertSame(first, second);
        assertEquals(1, providerCalls[0]);
    }

    @Test
    void failedRebuildRemovesThePreviousSnapshot() {
        UUID defenseId = UUID.randomUUID();
        TacticalBuildSelectionView selection = new TacticalBuildSelectionView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "cache-failure-test",
                1,
                1,
                List.of(new TacticalSkillNodeSnapshot(
                        "damage",
                        1,
                        1,
                        "Damage",
                        "test",
                        List.of(new TacticalEffectEntry(
                                TacticalEffectType.DAMAGE_MULTIPLIER,
                                Set.of(TowerType.ARROW),
                                1.25d,
                                TacticalTargetCondition.NONE,
                                null,
                                null)))));
        boolean[] fail = {false};
        TacticalEffectCache cache = new TacticalEffectCache(ignored -> {
            if (fail[0]) {
                throw new IllegalStateException("state unavailable");
            }
            return Optional.of(selection);
        });

        cache.rebuild(defenseId);
        fail[0] = true;

        assertThrows(IllegalStateException.class, () -> cache.rebuild(defenseId));
        assertSame(EmptyTacticalEffectSnapshot.INSTANCE, cache.currentForDefense(defenseId));
    }
}
