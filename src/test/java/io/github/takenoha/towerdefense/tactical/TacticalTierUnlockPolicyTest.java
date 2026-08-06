package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TacticalTierUnlockPolicyTest {
    @Test
    void usesTwentyPercentBoundariesWithoutFloatingPointDrift() {
        assertEquals(1, TacticalTierUnlockPolicy.highestProgressTier(0, 5));
        assertEquals(2, TacticalTierUnlockPolicy.highestProgressTier(1, 5));
        assertEquals(3, TacticalTierUnlockPolicy.highestProgressTier(2, 5));
        assertEquals(4, TacticalTierUnlockPolicy.highestProgressTier(3, 5));
        assertEquals(5, TacticalTierUnlockPolicy.highestProgressTier(4, 5));
        assertEquals(5, TacticalTierUnlockPolicy.highestProgressTier(5, 5));
    }

    @Test
    void shortStagesUnlockEveryReachedTierInOrderAndDoNotIncludeFinalTier() {
        assertEquals(
                List.of(2, 3, 4, 5),
                TacticalTierUnlockPolicy.newlyReachedProgressTiers(1, 1, 1));
        assertEquals(
                List.of(),
                TacticalTierUnlockPolicy.newlyReachedProgressTiers(5, 1, 1));
    }
}
