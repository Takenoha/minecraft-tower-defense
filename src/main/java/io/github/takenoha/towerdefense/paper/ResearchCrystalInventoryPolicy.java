package io.github.takenoha.towerdefense.paper;

import java.util.List;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/**
 * Java compatibility facade for the Kotlin research-crystal inventory scan.
 *
 * <p>The nested record remains Java because its compact canonical constructor must clone the
 * incoming ItemStack before normalizing its amount. The scan implementation itself is Kotlin.</p>
 */
public final class ResearchCrystalInventoryPolicy {
    private ResearchCrystalInventoryPolicy() {
    }

    /** Scans storage slots first, followed by the offhand slot. */
    public static List<Candidate> scan(
            ItemStack[] storageContents,
            ItemStack offHand,
            ResearchCrystalTagger tagger) {
        return ResearchCrystalInventoryPolicyKotlinBridge.scan(storageContents, offHand, tagger);
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
