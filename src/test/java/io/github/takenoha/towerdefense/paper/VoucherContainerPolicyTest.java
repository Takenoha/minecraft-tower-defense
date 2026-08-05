package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoucherContainerPolicyTest {
    @Test
    void blocksEveryPlainVoucherInsertionDirection() {
        assertTrue(blocks(true, true, false, true, false, false, false));
        assertTrue(blocks(true, true, false, false, true, false, false));
        assertTrue(blocks(true, true, false, false, false, true, false));
        assertTrue(blocks(true, false, true, false, false, false, true));
    }

    @Test
    void allowsTakingPlainVoucherOutAndOrdinaryItems() {
        assertFalse(blocks(true, true, false, false, false, false, true));
        assertFalse(blocks(true, true, false, false, false, false, false));
        assertFalse(blocks(true, false, false, false, false, false, true));
        assertFalse(blocks(false, true, true, true, true, true, true));
    }

    private static boolean blocks(
            boolean forbiddenInventory,
            boolean topTarget,
            boolean shiftClick,
            boolean cursorVoucher,
            boolean numberKeyVoucher,
            boolean offhandSwapVoucher,
            boolean clickedVoucher) {
        return VoucherContainerPolicy.blocksPlainVoucherInsertion(
                forbiddenInventory,
                topTarget,
                shiftClick,
                cursorVoucher,
                numberKeyVoucher,
                offhandSwapVoucher,
                clickedVoucher);
    }
}
