package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.PaymentMode

/** Selects the explicit wallet/legacy payment path used by the management GUIs. */
class PaymentSelectionPolicy private constructor() {
    companion object {
        @JvmStatic
        fun choose(
            explicitLegacy: Boolean,
            walletSufficient: Boolean,
            legacyEnabled: Boolean,
        ): PaymentMode {
            if (explicitLegacy) {
                if (!legacyEnabled) {
                    throw IllegalStateException(
                        "Legacy resource payments are disabled by configuration"
                    )
                }
                return PaymentMode.LEGACY_ITEMS
            }
            if (walletSufficient) {
                return PaymentMode.POINT_WALLET
            }
            if (legacyEnabled) {
                return PaymentMode.LEGACY_ITEMS
            }
            throw IllegalStateException(
                "Wallet balance is insufficient and legacy resource payments are disabled"
            )
        }
    }
}
