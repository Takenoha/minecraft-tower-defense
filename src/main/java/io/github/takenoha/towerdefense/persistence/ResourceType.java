package io.github.takenoha.towerdefense.persistence;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Team-scoped point wallets backed by event drops and management payments. */
public enum ResourceType {
    DEFENSE_POINTS("defense_shard", "防衛ポイント"),
    ENHANCEMENT_POINTS("enhancement_core", "強化ポイント");

    private final String itemId;
    private final String displayName;

    ResourceType(String itemId, String displayName) {
        this.itemId = itemId;
        this.displayName = displayName;
    }

    public String itemId() {
        return itemId;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<ResourceType> fromItemId(String itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.itemId.equals(itemId))
                .findFirst();
    }

    public static ResourceType require(ResourceType value) {
        return Objects.requireNonNull(value, "resourceType");
    }
}
