package io.github.takenoha.towerdefense.paper

import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import java.util.Optional
import java.util.function.Predicate
import kotlin.jvm.JvmRecord
import org.bukkit.inventory.ItemStack

/**
 * Plans receipt extraction without mutating an inventory.
 *
 * A receipt replaces only part of an ordinary stack in some payment paths. The remainder must
 * have a guaranteed destination before the tagged replacement is written. This helper simulates
 * that split and refuses the operation when no existing compatible stack or empty slot can hold
 * every remainder. It deliberately does not drop anything.
 */
class ReceiptInventoryPlanner private constructor() {
    companion object {
        @JvmStatic
        fun plan(
            contents: Array<ItemStack?>,
            source: Predicate<ItemStack>,
            quantity: Long,
            material: String,
        ): Optional<List<Extraction>> {
            Objects.requireNonNull(contents, "contents")
            Objects.requireNonNull(source, "source")
            Objects.requireNonNull(material, "material")
            if (quantity < 0L) {
                throw IllegalArgumentException("quantity must not be negative")
            }
            if (quantity == 0L) {
                return Optional.of(emptyList())
            }
            if (quantity > Int.MAX_VALUE.toLong()) {
                return Optional.empty()
            }
            val result = ArrayList<Extraction>()
            var remaining = quantity
            for (slot in contents.indices) {
                if (remaining <= 0L) {
                    break
                }
                val item = contents[slot] ?: continue
                if (!source.test(item)) {
                    continue
                }
                val amount = minOf(item.amount.toLong(), remaining).toInt()
                result.add(Extraction(slot, amount, item.clone(), material))
                remaining -= amount
            }
            return if (remaining == 0L) {
                Optional.of(Collections.unmodifiableList(ArrayList(result)))
            } else {
                Optional.empty()
            }
        }

        /** Returns whether all planned split remainders fit without a ground drop. */
        @JvmStatic
        fun canApply(
            contents: Array<ItemStack?>,
            extractions: List<Extraction>,
        ): Boolean {
            Objects.requireNonNull(contents, "contents")
            Objects.requireNonNull(extractions, "extractions")
            val simulated = ArrayList<ReceiptSplitPlanner.Stack?>(contents.size)
            for (item in contents) {
                simulated.add(if (item == null) null else stack(item))
            }
            val splits = ArrayList<ReceiptSplitPlanner.Split>()
            for (extraction in extractions) {
                splits.add(
                    ReceiptSplitPlanner.Split(
                        extraction.slot,
                        extraction.amount,
                        key(extraction.original),
                    ),
                )
            }
            return ReceiptSplitPlanner.canApply(simulated, splits)
        }

        private fun stack(item: ItemStack): ReceiptSplitPlanner.Stack =
            ReceiptSplitPlanner.Stack(key(item), item.amount, item.maxStackSize)

        private fun key(item: ItemStack): String {
            val normalized = item.clone()
            normalized.amount = 1
            return normalized.serialize().toString()
        }
    }

    @JvmRecord
    data class Extraction(
        val slot: Int,
        val amount: Int,
        val original: ItemStack,
        val material: String,
    ) {
        init {
            Objects.requireNonNull(original, "original")
            Objects.requireNonNull(material, "material")
            if (slot < 0 || amount <= 0 || original.amount < amount) {
                throw IllegalArgumentException("invalid receipt extraction")
            }
        }
    }
}
