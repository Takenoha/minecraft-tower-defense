package io.github.takenoha.towerdefense.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptInventoryPlannerTest {
    @Test
    void fullInventoryRefusesAStackSplitThatWouldDropTheRemainder() {
        List<ReceiptSplitPlanner.Stack> contents = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            contents.add(new ReceiptSplitPlanner.Stack("COBBLESTONE", 64, 64));
        }
        contents.set(0, new ReceiptSplitPlanner.Stack("IRON_INGOT", 8, 64));

        List<ReceiptSplitPlanner.Split> plan = List.of(
                new ReceiptSplitPlanner.Split(0, 3, "IRON_INGOT"));

        assertFalse(ReceiptSplitPlanner.canApply(contents, plan));
    }

    @Test
    void compatibleSpaceAcceptsTheSplitWithoutGroundDrop() {
        List<ReceiptSplitPlanner.Stack> contents = new ArrayList<>();
        contents.add(new ReceiptSplitPlanner.Stack("IRON_INGOT", 8, 64));
        contents.add(new ReceiptSplitPlanner.Stack("IRON_INGOT", 60, 64));
        while (contents.size() < 36) {
            contents.add(null);
        }

        List<ReceiptSplitPlanner.Split> plan = List.of(
                new ReceiptSplitPlanner.Split(0, 3, "IRON_INGOT"));

        assertTrue(ReceiptSplitPlanner.canApply(contents, plan));
    }
}
