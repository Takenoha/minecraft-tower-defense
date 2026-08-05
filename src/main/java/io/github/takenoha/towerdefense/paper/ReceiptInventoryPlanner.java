package io.github.takenoha.towerdefense.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.bukkit.inventory.ItemStack;

/**
 * Plans receipt extraction without mutating an inventory.
 *
 * <p>A receipt replaces only part of an ordinary stack in some payment paths.  The remainder
 * must have a guaranteed destination before the tagged replacement is written.  This helper
 * simulates that split and refuses the operation when no existing compatible stack or empty slot
 * can hold every remainder.  It deliberately does not drop anything.</p>
 */
public final class ReceiptInventoryPlanner {
    private ReceiptInventoryPlanner() {
    }

    public static Optional<List<Extraction>> plan(
            ItemStack[] contents,
            Predicate<ItemStack> source,
            long quantity,
            String material) {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(material, "material");
        if (quantity < 0L) {
            throw new IllegalArgumentException("quantity must not be negative");
        }
        if (quantity == 0L) {
            return Optional.of(List.of());
        }
        if (quantity > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        List<Extraction> result = new ArrayList<>();
        long remaining = quantity;
        for (int slot = 0; slot < contents.length && remaining > 0L; slot++) {
            ItemStack item = contents[slot];
            if (item == null || !source.test(item)) {
                continue;
            }
            int amount = (int) Math.min((long) item.getAmount(), remaining);
            result.add(new Extraction(slot, amount, item.clone(), material));
            remaining -= amount;
        }
        return remaining == 0L ? Optional.of(List.copyOf(result)) : Optional.empty();
    }

    /** Returns whether all planned split remainders fit without a ground drop. */
    public static boolean canApply(ItemStack[] contents, List<Extraction> extractions) {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(extractions, "extractions");
        List<ReceiptSplitPlanner.Stack> simulated = new ArrayList<>(contents.length);
        for (ItemStack item : contents) {
            simulated.add(item == null ? null : stack(item));
        }
        List<ReceiptSplitPlanner.Split> splits = new ArrayList<>();
        for (Extraction extraction : extractions) {
            splits.add(new ReceiptSplitPlanner.Split(
                    extraction.slot(),
                    extraction.amount(),
                    key(extraction.original())));
        }
        return ReceiptSplitPlanner.canApply(simulated, splits);
    }

    private static ReceiptSplitPlanner.Stack stack(ItemStack item) {
        return new ReceiptSplitPlanner.Stack(
                key(item),
                item.getAmount(),
                item.getMaxStackSize());
    }

    private static String key(ItemStack item) {
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        return normalized.serialize().toString();
    }

    public record Extraction(int slot, int amount, ItemStack original, String material) {
        public Extraction {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(material, "material");
            if (slot < 0 || amount <= 0 || original.getAmount() < amount) {
                throw new IllegalArgumentException("invalid receipt extraction");
            }
        }
    }
}
