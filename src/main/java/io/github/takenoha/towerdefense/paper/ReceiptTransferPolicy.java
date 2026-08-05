package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.function.Predicate;

/** Shared bidirectional protection rule for inventory transfer sources. */
public final class ReceiptTransferPolicy {
    private ReceiptTransferPolicy() {
    }

    public static <T> boolean containsTagged(
            Predicate<T> tagged,
            T current,
            T cursor,
            T auxiliary) {
        Objects.requireNonNull(tagged, "tagged");
        return tagged.test(current) || tagged.test(cursor) || tagged.test(auxiliary);
    }

    /**
     * Checks both sides of a bidirectional inventory operation plus the two hand/source slots.
     * Keeping every candidate instead of overwriting the clicked item prevents NUMBER_KEY and
     * SWAP_OFFHAND from losing the receipt that was on the other side of the operation.
     */
    public static <T> boolean containsTagged(
            Predicate<T> tagged,
            T current,
            T cursor,
            T hotbar,
            T offhand) {
        Objects.requireNonNull(tagged, "tagged");
        return tagged.test(current)
                || tagged.test(cursor)
                || tagged.test(hotbar)
                || tagged.test(offhand);
    }
}
