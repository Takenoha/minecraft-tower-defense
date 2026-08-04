package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;

/** The stable identity carried by one physical "襲撃の印" item. */
public record RaidSealItemIdentity(UUID sealId, long stageLevel) {
    public RaidSealItemIdentity {
        Objects.requireNonNull(sealId, "sealId");
        if (stageLevel <= 0L) {
            throw new IllegalArgumentException("stageLevel must be positive");
        }
    }
}
