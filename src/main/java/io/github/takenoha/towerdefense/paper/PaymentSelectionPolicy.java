package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.PaymentMode;

/** Selects the explicit wallet/legacy payment path used by the management GUIs. */
public final class PaymentSelectionPolicy {
    private PaymentSelectionPolicy() {
    }

    public static PaymentMode choose(
            boolean explicitLegacy,
            boolean walletSufficient,
            boolean legacyEnabled) {
        if (explicitLegacy) {
            if (!legacyEnabled) {
                throw new IllegalStateException(
                        "Legacy resource payments are disabled by configuration");
            }
            return PaymentMode.LEGACY_ITEMS;
        }
        if (walletSufficient) {
            return PaymentMode.POINT_WALLET;
        }
        if (legacyEnabled) {
            return PaymentMode.LEGACY_ITEMS;
        }
        throw new IllegalStateException(
                "Wallet balance is insufficient and legacy resource payments are disabled");
    }
}
