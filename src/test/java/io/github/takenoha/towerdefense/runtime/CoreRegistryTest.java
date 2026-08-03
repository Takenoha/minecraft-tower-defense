package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.persistence.CoreRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CoreRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T04:00:00Z");

    @Test
    void destroyedRowsAreNotRegisteredAndReplacementUpdatesBothIndexes() {
        UUID destroyedTeam = UUID.randomUUID();
        UUID intactTeam = UUID.randomUUID();
        CoreRecord destroyed = new CoreRecord(
                UUID.randomUUID(),
                destroyedTeam,
                UUID.randomUUID(),
                0,
                64,
                0,
                0L,
                100L,
                NOW,
                NOW);
        CoreRecord intact = new CoreRecord(
                UUID.randomUUID(),
                intactTeam,
                UUID.randomUUID(),
                10,
                64,
                10,
                100L,
                100L,
                NOW,
                NOW);

        CoreRegistry registry = new CoreRegistry();
        registry.replaceAll(List.of(destroyed, intact));
        assertTrue(registry.forTeam(destroyedTeam).isEmpty());
        assertEquals(intact, registry.forTeam(intactTeam).orElseThrow());

        CoreRecord replacement = new CoreRecord(
                intact.id(),
                intact.teamId(),
                intact.worldId(),
                20,
                64,
                20,
                100L,
                100L,
                intact.createdAt(),
                NOW.plusSeconds(1L));
        registry.replace(replacement);
        assertEquals(replacement, registry.forTeam(intactTeam).orElseThrow());
        registry.unregister(replacement.id());
        assertTrue(registry.forTeam(intactTeam).isEmpty());
    }
}
