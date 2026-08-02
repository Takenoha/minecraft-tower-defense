package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable team ownership and membership snapshot. */
public record TeamRecord(UUID id, UUID ownerId, Set<UUID> members, Instant createdAt) {
    public TeamRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        members = Set.copyOf(Objects.requireNonNull(members, "members"));
        Objects.requireNonNull(createdAt, "createdAt");
        if (!members.contains(ownerId)) {
            throw new IllegalArgumentException("The team owner must also be a member");
        }
    }
}
