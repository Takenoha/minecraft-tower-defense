package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.takenoha.towerdefense.persistence.PaymentMode;
import org.junit.jupiter.api.Test;

class PaymentSelectionPolicyTest {
    @Test
    void explicitLegacyChoiceWinsEvenWhenWalletHasEnough() {
        assertEquals(
                PaymentMode.LEGACY_ITEMS,
                PaymentSelectionPolicy.choose(true, true, true));
    }

    @Test
    void explicitLegacyChoiceIsRejectedWithoutMutationWhenDisabled() {
        assertThrows(
                IllegalStateException.class,
                () -> PaymentSelectionPolicy.choose(true, true, false));
    }

    @Test
    void insufficientWalletFallsBackOnlyWhenLegacyIsEnabled() {
        assertEquals(
                PaymentMode.LEGACY_ITEMS,
                PaymentSelectionPolicy.choose(false, false, true));
        assertThrows(
                IllegalStateException.class,
                () -> PaymentSelectionPolicy.choose(false, false, false));
    }
}
