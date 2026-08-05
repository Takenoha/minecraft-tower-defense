package io.github.takenoha.towerdefense.paper;

/** Directional guard for plain voucher insertion into an anvil-like inventory. */
public final class VoucherContainerPolicy {
    private VoucherContainerPolicy() {
    }

    public static boolean blocksPlainVoucherInsertion(
            boolean forbiddenInventory,
            boolean topTarget,
            boolean shiftClick,
            boolean cursorVoucher,
            boolean numberKeyVoucher,
            boolean offhandSwapVoucher,
            boolean clickedVoucher) {
        if (!forbiddenInventory) {
            return false;
        }
        if (topTarget) {
            return cursorVoucher || numberKeyVoucher || offhandSwapVoucher;
        }
        return shiftClick && clickedVoucher;
    }
}
