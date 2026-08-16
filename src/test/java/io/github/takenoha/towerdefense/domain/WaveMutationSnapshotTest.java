package io.github.takenoha.towerdefense.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WaveMutationSnapshotTest {
    @Test
    void scalesEnemyCountsAndRewardsFromTheImmutableCoefficients() {
        WaveMutationSnapshot snapshot = new WaveMutationSnapshot(
                WaveMutation.REINFORCEMENTS,
                1.0d,
                1.0d,
                1.30d,
                1.25d);

        assertEquals(13L, snapshot.scaleEnemyCount(10L));
        assertEquals(13, snapshot.scaleReward(10));
        assertEquals(13L, snapshot.scaleReward(10L));
        assertEquals(13, snapshot.scalePercent(10));
    }

    @Test
    void preservesNeutralLegacyBehavior() {
        WaveMutationSnapshot snapshot = WaveMutationSnapshot.none();

        assertEquals(10L, snapshot.scaleEnemyCount(10L));
        assertEquals(10, snapshot.scaleReward(10));
        assertEquals(10, snapshot.scalePercent(10));
    }

    @Test
    void rejectsInvalidAndNonNeutralNoneSnapshots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WaveMutationSnapshot(
                        WaveMutation.SWIFT,
                        Double.NaN,
                        1.0d,
                        1.0d,
                        1.0d));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WaveMutationSnapshot(
                        WaveMutation.NONE,
                        1.25d,
                        1.0d,
                        1.0d,
                        1.0d));
    }
}
