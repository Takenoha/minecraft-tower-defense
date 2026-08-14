package io.github.takenoha.towerdefense.paper

import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import org.bukkit.inventory.ItemStack

/** Kotlin implementation behind the Java record-compatible policy facade. */
class ResearchCrystalInventoryPolicyKotlinBridge private constructor() {
    companion object {
        @JvmStatic
        fun scan(
            storageContents: Array<ItemStack?>,
            offHand: ItemStack?,
            tagger: ResearchCrystalTagger,
        ): List<ResearchCrystalInventoryPolicy.Candidate> {
            Objects.requireNonNull(storageContents, "storageContents")
            Objects.requireNonNull(tagger, "tagger")
            val candidates = ArrayList<ResearchCrystalInventoryPolicy.Candidate>()
            for (slot in storageContents.indices) {
                addCandidate(candidates, storageContents[slot], slot, tagger)
            }
            addCandidate(candidates, offHand, ResearchCrystalInventoryPolicy.Candidate.OFF_HAND_SLOT, tagger)
            return Collections.unmodifiableList(ArrayList(candidates))
        }

        private fun addCandidate(
            candidates: MutableList<ResearchCrystalInventoryPolicy.Candidate>,
            item: ItemStack?,
            storageSlot: Int,
            tagger: ResearchCrystalTagger,
        ) {
            if (item == null) {
                return
            }
            val identity = tagger.read(item).orElse(null) ?: return
            candidates.add(
                ResearchCrystalInventoryPolicy.Candidate(
                    storageSlot,
                    identity,
                    item.amount,
                    item,
                ),
            )
        }
    }
}
