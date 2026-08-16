package io.github.takenoha.towerdefense.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.takenoha.towerdefense.domain.WaveMutation;
import org.junit.jupiter.api.Test;

class WaveMutationSettingsTest {
    @Test
    void resolvesOnlyEnabledSelectableCandidates() {
        WaveMutationSettings settings = WaveMutationSettings.defaults();

        assertEquals(
                WaveMutation.SWIFT,
                settings.snapshotFor(WaveMutation.SWIFT).mutation());
        assertEquals(
                1.30d,
                settings.snapshotFor(WaveMutation.REINFORCEMENTS).enemyCountMultiplier());
        assertEquals(WaveMutation.NONE, settings.snapshotFor(WaveMutation.NONE).mutation());
    }

    @Test
    void disabledSettingsRejectNonNeutralSelection() {
        WaveMutationSettings defaults = WaveMutationSettings.defaults();
        WaveMutationSettings disabled = new WaveMutationSettings(
                false,
                defaults.swift(),
                defaults.fortified(),
                defaults.reinforcements());

        assertThrows(
                IllegalArgumentException.class,
                () -> disabled.snapshotFor(WaveMutation.SWIFT));
        assertEquals(WaveMutation.NONE, disabled.snapshotFor(WaveMutation.NONE).mutation());
    }
}
