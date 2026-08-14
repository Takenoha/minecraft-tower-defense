package io.github.takenoha.towerdefense.paper

/** Directional guard for plain voucher insertion into an anvil-like inventory. */
class VoucherContainerPolicy private constructor() {
    companion object {
        @JvmStatic
        fun blocksPlainVoucherInsertion(
            forbiddenInventory: Boolean,
            topTarget: Boolean,
            shiftClick: Boolean,
            cursorVoucher: Boolean,
            numberKeyVoucher: Boolean,
            offhandSwapVoucher: Boolean,
            clickedVoucher: Boolean,
        ): Boolean {
            if (!forbiddenInventory) {
                return false
            }
            return if (topTarget) {
                cursorVoucher || numberKeyVoucher || offhandSwapVoucher
            } else {
                shiftClick && clickedVoucher
            }
        }
    }
}
