package io.github.takenoha.towerdefense.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Small deterministic compositor for competing action-bar producers.
 *
 * <p>Countdown is a replaceable low-priority source. Pickup notices have higher priority and a
 * 40-tick minimum lifetime; repeated notices for one event are coalesced instead of cancelling
 * the previous display.</p>
 */
public final class ActionBarBroker {
    public static final int PICKUP_PRIORITY = 100;
    public static final int TACTICAL_PRIORITY = 50;
    public static final int COUNTDOWN_PRIORITY = 10;
    public static final long PICKUP_TTL_TICKS = 40L;
    public static final long TACTICAL_TTL_TICKS = 40L;

    private final Map<UUID, Map<String, Notice>> notices = new HashMap<>();
    private long sequence;
    private long currentTick;

    public synchronized void advance(long tick) {
        currentTick = tick;
        purgeExpired(tick);
    }

    public synchronized void publishCountdown(UUID playerId, String text, long nowTick) {
        purgeExpired(nowTick);
        publish(
                playerId,
                "countdown",
                text,
                nowTick,
                2L,
                COUNTDOWN_PRIORITY);
    }

    public synchronized void publishTactical(UUID playerId, String text, long nowTick) {
        purgeExpired(nowTick);
        publish(
                playerId,
                "tactical",
                text,
                nowTick,
                TACTICAL_TTL_TICKS,
                TACTICAL_PRIORITY);
    }

    public synchronized void publishPickup(
            UUID playerId,
            UUID eventId,
            String text,
            long nowTick) {
        purgeExpired(nowTick);
        String key = "pickup:" + eventId;
        Map<String, Notice> playerNotices = notices.computeIfAbsent(playerId, ignored -> new HashMap<>());
        Notice previous = playerNotices.get(key);
        String combined = previous == null ? text : previous.text() + " | " + text;
        if (combined.length() > 256) {
            combined = combined.substring(combined.length() - 256);
        }
        long expiry = Math.max(
                nowTick + PICKUP_TTL_TICKS,
                previous == null ? 0L : previous.expiresAtTick());
        playerNotices.put(
                key,
                new Notice(combined, PICKUP_PRIORITY, expiry, ++sequence, eventId, key));
    }

    public synchronized void publishPickup(UUID playerId, UUID eventId, String text) {
        publishPickup(playerId, eventId, text, currentTick);
    }

    public synchronized Optional<Notice> current(UUID playerId, long nowTick) {
        Map<String, Notice> playerNotices = notices.get(playerId);
        if (playerNotices == null) {
            return Optional.empty();
        }
        playerNotices.values().removeIf(notice -> notice.expiresAtTick() <= nowTick);
        if (playerNotices.isEmpty()) {
            notices.remove(playerId);
            return Optional.empty();
        }
        return playerNotices.values().stream()
                .max(Comparator.comparingInt(Notice::priority)
                        .thenComparingLong(Notice::sequence));
    }

    public synchronized Optional<Notice> current(UUID playerId) {
        return current(playerId, currentTick);
    }

    public synchronized void clearEvent(UUID eventId) {
        String prefix = "pickup:" + eventId;
        for (Map<String, Notice> playerNotices : new ArrayList<>(notices.values())) {
            playerNotices.keySet().removeIf(key -> key.equals(prefix));
        }
        notices.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public synchronized void clearPlayer(UUID playerId) {
        notices.remove(playerId);
    }

    private void publish(
            UUID playerId,
            String key,
            String text,
            long nowTick,
            long ttlTicks,
            int priority) {
        notices.computeIfAbsent(playerId, ignored -> new HashMap<>())
                .put(key, new Notice(
                        text,
                        priority,
                        nowTick + ttlTicks,
                        ++sequence,
                        null,
                        key));
    }

    private void purgeExpired(long nowTick) {
        notices.values().forEach(playerNotices ->
                playerNotices.values().removeIf(notice -> notice.expiresAtTick() <= nowTick));
        notices.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public record Notice(
            String text,
            int priority,
            long expiresAtTick,
            long sequence,
            UUID eventId,
            String sourceKey) {
    }
}
