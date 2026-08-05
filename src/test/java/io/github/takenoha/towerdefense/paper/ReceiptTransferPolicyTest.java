package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReceiptTransferPolicyTest {
    @Test
    void blocksReceiptInEitherSideOfNumberKeyOrOffhandSwap() {
        assertTrue(ReceiptTransferPolicy.containsTagged(
                "receipt"::equals, "ordinary", "ordinary", "receipt"));
        assertTrue(ReceiptTransferPolicy.containsTagged(
                "receipt"::equals, "receipt", "ordinary", "ordinary"));
        assertTrue(ReceiptTransferPolicy.containsTagged(
                "receipt"::equals, "ordinary", "receipt", "ordinary"));
        assertFalse(ReceiptTransferPolicy.containsTagged(
                "receipt"::equals, "ordinary", "ordinary", "ordinary"));
    }
}
