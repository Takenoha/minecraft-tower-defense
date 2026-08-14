package io.github.takenoha.towerdefense.paper

/** Pure entity-boundary rules for protecting voucher items. */
class VoucherEntityPolicy private constructor() {
    companion object {
        @JvmStatic
        fun blocksInteraction(handIsVoucher: Boolean, entityContainsVoucher: Boolean): Boolean =
            handIsVoucher || entityContainsVoucher

        @JvmStatic
        fun blocksHangingBreak(entityContainsVoucher: Boolean): Boolean = entityContainsVoucher
    }
}
