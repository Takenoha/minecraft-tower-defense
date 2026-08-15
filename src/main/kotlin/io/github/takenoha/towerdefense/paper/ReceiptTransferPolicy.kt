package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.function.Predicate

/** Shared bidirectional protection rule for inventory transfer sources. */
class ReceiptTransferPolicy private constructor() {
    companion object {
        @JvmStatic
        fun <T> containsTagged(
            tagged: Predicate<T>,
            current: T,
            cursor: T,
            auxiliary: T,
        ): Boolean {
            Objects.requireNonNull(tagged, "tagged")
            return tagged.test(current) || tagged.test(cursor) || tagged.test(auxiliary)
        }

        /** Checks both sides of a bidirectional inventory operation plus the hand/source slots. */
        @JvmStatic
        fun <T> containsTagged(
            tagged: Predicate<T>,
            current: T,
            cursor: T,
            hotbar: T,
            offhand: T,
        ): Boolean {
            Objects.requireNonNull(tagged, "tagged")
            return tagged.test(current) ||
                tagged.test(cursor) ||
                tagged.test(hotbar) ||
                tagged.test(offhand)
        }
    }
}
