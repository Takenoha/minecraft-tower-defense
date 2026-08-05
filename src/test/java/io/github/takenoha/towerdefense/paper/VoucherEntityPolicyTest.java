package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoucherEntityPolicyTest {
    @Test
    void blocksVoucherInEitherHandOrItemFrame() {
        assertTrue(VoucherEntityPolicy.blocksInteraction(true, false));
        assertTrue(VoucherEntityPolicy.blocksInteraction(false, true));
        assertTrue(VoucherEntityPolicy.blocksInteraction(true, true));
        assertFalse(VoucherEntityPolicy.blocksInteraction(false, false));
    }

    @Test
    void blocksBreakingAnItemFrameThatContainsVoucher() {
        assertTrue(VoucherEntityPolicy.blocksHangingBreak(true));
        assertFalse(VoucherEntityPolicy.blocksHangingBreak(false));
    }
}
