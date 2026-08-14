package io.github.takenoha.towerdefense.paper

import java.util.ArrayList
import java.util.HashSet
import java.util.Objects
import kotlin.jvm.JvmRecord

/** Pure capacity simulation used before a physical receipt split mutates player inventory. */
class ReceiptSplitPlanner private constructor() {
    companion object {
        @JvmStatic
        fun canApply(
            contents: List<Stack?>,
            splits: List<Split>,
        ): Boolean {
            Objects.requireNonNull(contents, "contents")
            Objects.requireNonNull(splits, "splits")
            val simulated = ArrayList<Stack?>(contents)
            val receiptSlots = HashSet<Int>()
            val remainders = ArrayList<Stack>()
            for (split in splits) {
                if (split.slot < 0 ||
                    split.slot >= simulated.size ||
                    !receiptSlots.add(split.slot)
                ) {
                    return false
                }
                val original = simulated[split.slot]
                if (original == null ||
                    original.key != split.key ||
                    split.amount <= 0 ||
                    split.amount > original.amount
                ) {
                    return false
                }
                simulated[split.slot] = Stack(split.key, split.amount, original.maxStackSize)
                val remainder = original.amount - split.amount
                if (remainder > 0) {
                    remainders.add(Stack(original.key, remainder, original.maxStackSize))
                }
            }
            for (remainder in remainders) {
                if (!addWithoutDrop(simulated, receiptSlots, remainder)) {
                    return false
                }
            }
            return true
        }

        private fun addWithoutDrop(
            contents: MutableList<Stack?>,
            receiptSlots: Set<Int>,
            addition: Stack,
        ): Boolean {
            var remaining = addition.amount
            for (slot in contents.indices) {
                if (remaining <= 0) {
                    break
                }
                if (receiptSlots.contains(slot)) {
                    continue
                }
                val existing = contents[slot]
                if (existing == null ||
                    existing.key != addition.key ||
                    existing.amount >= existing.maxStackSize
                ) {
                    continue
                }
                val added = minOf(remaining, existing.maxStackSize - existing.amount)
                contents[slot] = Stack(existing.key, existing.amount + added, existing.maxStackSize)
                remaining -= added
            }
            for (slot in contents.indices) {
                if (remaining <= 0) {
                    break
                }
                if (receiptSlots.contains(slot) || contents[slot] != null) {
                    continue
                }
                val placed = minOf(remaining, addition.maxStackSize)
                contents[slot] = Stack(addition.key, placed, addition.maxStackSize)
                remaining -= placed
            }
            return remaining == 0
        }
    }

    @JvmRecord
    data class Stack(
        val key: String,
        val amount: Int,
        val maxStackSize: Int,
    ) {
        init {
            Objects.requireNonNull(key, "key")
            if (amount <= 0 || maxStackSize <= 0 || amount > maxStackSize) {
                throw IllegalArgumentException("invalid stack capacity")
            }
        }
    }

    @JvmRecord
    data class Split(
        val slot: Int,
        val amount: Int,
        val key: String,
    ) {
        init {
            Objects.requireNonNull(key, "key")
            if (slot < 0 || amount <= 0) {
                throw IllegalArgumentException("invalid receipt split")
            }
        }
    }
}
