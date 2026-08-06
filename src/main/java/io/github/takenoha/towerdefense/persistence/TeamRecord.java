package io.github.takenoha.towerdefense.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable team ownership, display name, and membership snapshot. */
public record TeamRecord(
        UUID id,
        UUID ownerId,
        Set<UUID> members,
        String displayName,
        Instant createdAt) {
    public static final String DEFAULT_DISPLAY_NAME = "チーム";
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 32;

    /** Keeps the pre-naming constructor source-compatible for persistence fixtures. */
    public TeamRecord(UUID id, UUID ownerId, Set<UUID> members, Instant createdAt) {
        this(id, ownerId, members, DEFAULT_DISPLAY_NAME, createdAt);
    }

    public TeamRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        members = Set.copyOf(Objects.requireNonNull(members, "members"));
        displayName = normalizeDisplayName(displayName);
        Objects.requireNonNull(createdAt, "createdAt");
        if (!members.contains(ownerId)) {
            throw new IllegalArgumentException("The team owner must also be a member");
        }
    }

    /** Trims and validates a player-visible team name before it reaches SQLite or chat. */
    public static String normalizeDisplayName(String value) {
        Objects.requireNonNull(value, "displayName");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Team display name must not be blank");
        }
        if (normalized.codePoints().count() > MAX_DISPLAY_NAME_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Team display name must be at most " + MAX_DISPLAY_NAME_CODE_POINTS
                            + " characters");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Team display name must not contain control characters");
        }
        return normalized;
    }
}
