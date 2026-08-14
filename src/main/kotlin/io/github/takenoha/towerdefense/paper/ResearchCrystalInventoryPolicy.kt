package io.github.takenoha.towerdefense.paper

import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import kotlin.jvm.JvmRecord
import org.bukkit.inventory.ItemStack

/**
 * Selects research-crystal stacks from the player's own storage inventory.
 *
 * The caller deliberately supplies only PlayerInventory#getStorageContents() and the offhand
 * item. Armor, crafting slots, a cursor stack, and an external inventory can therefore never
 * become redemption candidates accidentally.
 */
class ResearchCrystalInventoryPolicy private constructor() {
    companion object {
        /** Scans storage slots first, followed by the offhand slot. */
        @JvmStatic
        fun scan(
            storageContents: Array<ItemStack?>,
            offHand: ItemStack?,
            tagger: ResearchCrystalTagger,
        ): List<Candidate> {
            Objects.requireNonNull(storageContents, "storageContents")
            Objects.requireNonNull(tagger, "tagger")
            val candidates = ArrayList<Candidate>()
            for (slot in storageContents.indices) {
                addCandidate(candidates, storageContents[slot], slot, tagger)
            }
            addCandidate(candidates, offHand, Candidate.OFF_HAND_SLOT, tagger)
            return Collections.unmodifiableList(ArrayList(candidates))
        }

        private fun addCandidate(
            candidates: MutableList<Candidate>,
            item: ItemStack?,
            storageSlot: Int,
            tagger: ResearchCrystalTagger,
        ) {
            if (item == null) {
                return
            }
            val identity = tagger.read(item).orElse(null) ?: return
            val snapshot = item.clone()
            snapshot.amount = item.amount
            candidates.add(Candidate(storageSlot, identity, item.amount, snapshot))
        }
    }

    /** A snapshot of one eligible stack and its PlayerInventory location. */
    @JvmRecord
    data class Candidate(
        val storageSlot: Int,
        val identity: ResearchCrystalItemIdentity,
        val quantity: Int,
        val snapshot: ItemStack,
    ) {
        companion object {
            const val OFF_HAND_SLOT: Int = -1
        }

        init {
            Objects.requireNonNull(identity, "identity")
            Objects.requireNonNull(snapshot, "snapshot")
            if (storageSlot < OFF_HAND_SLOT) {
                throw IllegalArgumentException("storageSlot must be -1 or greater")
            }
            if (quantity <= 0) {
                throw IllegalArgumentException("quantity must be positive")
            }
            snapshot.amount = quantity
        }

        fun isOffHand(): Boolean = storageSlot == OFF_HAND_SLOT
    }
}
