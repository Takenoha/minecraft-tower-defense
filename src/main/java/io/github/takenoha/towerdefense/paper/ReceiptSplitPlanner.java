package io.github.takenoha.towerdefense.paper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure capacity simulation used before a physical receipt split mutates player inventory. */
public final class ReceiptSplitPlanner {
    private ReceiptSplitPlanner() {
    }

    public static boolean canApply(
            List<Stack> contents,
            List<Split> splits) {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(splits, "splits");
        List<Stack> simulated = new ArrayList<>(contents);
        Set<Integer> receiptSlots = new HashSet<>();
        List<Stack> remainders = new ArrayList<>();
        for (Split split : splits) {
            if (split.slot() < 0
                    || split.slot() >= simulated.size()
                    || !receiptSlots.add(split.slot())) {
                return false;
            }
            Stack original = simulated.get(split.slot());
            if (original == null
                    || !original.key().equals(split.key())
                    || split.amount() <= 0
                    || split.amount() > original.amount()) {
                return false;
            }
            simulated.set(
                    split.slot(),
                    new Stack(original.key(), split.amount(), original.maxStackSize()));
            int remainder = original.amount() - split.amount();
            if (remainder > 0) {
                remainders.add(new Stack(original.key(), remainder, original.maxStackSize()));
            }
        }
        for (Stack remainder : remainders) {
            if (!addWithoutDrop(simulated, receiptSlots, remainder)) {
                return false;
            }
        }
        return true;
    }

    private static boolean addWithoutDrop(
            List<Stack> contents,
            Set<Integer> receiptSlots,
            Stack addition) {
        int remaining = addition.amount();
        for (int slot = 0; slot < contents.size() && remaining > 0; slot++) {
            if (receiptSlots.contains(slot)) {
                continue;
            }
            Stack existing = contents.get(slot);
            if (existing == null
                    || !existing.key().equals(addition.key())
                    || existing.amount() >= existing.maxStackSize()) {
                continue;
            }
            int added = Math.min(
                    remaining,
                    existing.maxStackSize() - existing.amount());
            contents.set(
                    slot,
                    new Stack(existing.key(), existing.amount() + added, existing.maxStackSize()));
            remaining -= added;
        }
        for (int slot = 0; slot < contents.size() && remaining > 0; slot++) {
            if (receiptSlots.contains(slot) || contents.get(slot) != null) {
                continue;
            }
            int placed = Math.min(remaining, addition.maxStackSize());
            contents.set(slot, new Stack(addition.key(), placed, addition.maxStackSize()));
            remaining -= placed;
        }
        return remaining == 0;
    }

    public record Stack(String key, int amount, int maxStackSize) {
        public Stack {
            Objects.requireNonNull(key, "key");
            if (amount <= 0 || maxStackSize <= 0 || amount > maxStackSize) {
                throw new IllegalArgumentException("invalid stack capacity");
            }
        }
    }

    public record Split(int slot, int amount, String key) {
        public Split {
            Objects.requireNonNull(key, "key");
            if (slot < 0 || amount <= 0) {
                throw new IllegalArgumentException("invalid receipt split");
            }
        }
    }
}
