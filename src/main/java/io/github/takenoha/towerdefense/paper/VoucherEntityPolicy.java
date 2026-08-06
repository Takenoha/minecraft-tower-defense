package io.github.takenoha.towerdefense.paper;

/** Pure entity-boundary rules for protecting voucher items. */
public final class VoucherEntityPolicy {
    private VoucherEntityPolicy() {
    }

    public static boolean blocksInteraction(boolean handIsVoucher, boolean entityContainsVoucher) {
        return handIsVoucher || entityContainsVoucher;
    }

    public static boolean blocksHangingBreak(boolean entityContainsVoucher) {
        return entityContainsVoucher;
    }
}
