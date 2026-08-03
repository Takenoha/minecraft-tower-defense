package io.github.takenoha.towerdefense.paper;

import java.util.Objects;
import java.util.UUID;

/** Durable identity copied onto an inventory stack while its queue delivery is being committed. */
public record RewardQueueReceipt(UUID queueId, UUID operationId) {
    public RewardQueueReceipt {
        Objects.requireNonNull(queueId, "queueId");
        Objects.requireNonNull(operationId, "operationId");
    }
}
