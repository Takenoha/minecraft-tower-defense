package io.github.takenoha.towerdefense.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/**
 * Selects research-crystal stacks from the player's own storage inventory.
 *
 * <p>The caller deliberately supplies only {@code PlayerInventory#getStorageContents()} and the
 * offhand item. Armor, crafting slots, a cursor stack, and an external inventory can therefore
 * never become redemption candidates accidentally.</p>
 */
public final class ResearchCrystalInventoryPolicy {
    private ResearchCrystalInventoryPolicy() {
    }

    /** Scans storage slots first, followed by the offhand slot. */
    public static List<Candidate> scan(
            ItemStack[] storageContents,
            ItemStack offHand,
            ResearchCrystalTagger tagger) {
        Objects.requireNonNull(storageContents, "storageContents");
        Objects.requireNonNull(tagger, "tagger");
        List<Candidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < storageContents.length; slot++) {
            addCandidate(candidates, storageContents[slot], slot, tagger);
        }
        addCandidate(candidates, offHand, Candidate.OFF_HAND_SLOT, tagger);
        return List.copyOf(candidates);
    }

    private static void addCandidate(
            List<Candidate> candidates,
            ItemStack item,
            int storageSlot,
            ResearchCrystalTagger tagger) {
        tagger.read(item).ifPresent(identity -> candidates.add(
                new Candidate(storageSlot, identity, item.getAmount(), item)));
    }

    /** A snapshot of one eligible stack and its PlayerInventory location. */
    public record Candidate(
            int storageSlot,
            ResearchCrystalItemIdentity identity,
            int quantity,
            ItemStack snapshot) {
        public static final int OFF_HAND_SLOT = -1;

        public Candidate {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(snapshot, "snapshot");
            if (storageSlot < OFF_HAND_SLOT) {
                throw new IllegalArgumentException("storageSlot must be -1 or greater");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            snapshot = snapshot.clone();
            snapshot.setAmount(quantity);
        }

        public boolean isOffHand() {
            return storageSlot == OFF_HAND_SLOT;
        }
    }
}
