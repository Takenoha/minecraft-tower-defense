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
}
