package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionBarBrokerTest {
    @Test
    void pickupWinsOverCountdownForAtLeastFortyTicksAndRepeatedPickupsCoalesce() {
        ActionBarBroker broker = new ActionBarBroker();
        UUID playerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        broker.publishCountdown(playerId, "準備: 10秒", 100L);
        broker.publishPickup(playerId, eventId, "防衛ポイント +1", 100L);
        broker.publishPickup(playerId, eventId, "防衛ポイント +2", 105L);

        assertEquals(
                "防衛ポイント +1 | 防衛ポイント +2",
                broker.current(playerId, 139L).orElseThrow().text());
        assertEquals(ActionBarBroker.PICKUP_PRIORITY,
                broker.current(playerId, 139L).orElseThrow().priority());
        assertTrue(broker.current(playerId, 145L).isEmpty());
    }

    @Test
    void countdownIsReplaceableAfterPickupExpires() {
        ActionBarBroker broker = new ActionBarBroker();
        UUID playerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        broker.publishCountdown(playerId, "準備: 10秒", 10L);
        broker.publishPickup(playerId, eventId, "防衛ポイント +1", 10L);
        broker.publishCountdown(playerId, "準備: 9秒", 49L);

        assertEquals("防衛ポイント +1", broker.current(playerId, 49L).orElseThrow().text());
        assertEquals("準備: 9秒", broker.current(playerId, 50L).orElseThrow().text());
    }

    @Test
    void expiredPickupDoesNotCoalesceAfterOfflineReconnect() {
        ActionBarBroker broker = new ActionBarBroker();
        UUID playerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        broker.publishPickup(playerId, eventId, "防衛ポイント +1", 10L);
        broker.advance(50L);
        broker.publishPickup(playerId, eventId, "防衛ポイント +2", 50L);

        assertEquals(
                "防衛ポイント +2",
                broker.current(playerId, 50L).orElseThrow().text());
    }
}
