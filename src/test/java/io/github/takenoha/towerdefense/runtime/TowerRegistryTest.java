package io.github.takenoha.towerdefense.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.takenoha.towerdefense.domain.TowerType;
import io.github.takenoha.towerdefense.persistence.TowerRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TowerRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void indexesTowerIdentityAndCountsByTeam() {
        UUID team = UUID.randomUUID();
        TowerRecord first = tower(team, 1, 64, 1);
        TowerRecord second = tower(team, 2, 64, 1);
        TowerRegistry registry = new TowerRegistry();

        registry.replaceAll(List.of(first, second));

        assertEquals(2L, registry.countForTeam(team));
        assertEquals(first, registry.find(first.id()).orElseThrow());
        registry.unregister(first.id());
        assertEquals(1L, registry.countForTeam(team));
    }

    @Test
    void rejectsDuplicateEntityIdentity() {
        UUID team = UUID.randomUUID();
        TowerRecord first = tower(team, 1, 64, 1);
        TowerRecord duplicateEntity = new TowerRecord(
                UUID.randomUUID(), team, first.worldId(), 2, 64, 1, TowerType.ARROW, 1,
                first.entityId(), NOW, NOW);
        TowerRegistry registry = new TowerRegistry();
        registry.register(first);

        assertThrows(IllegalStateException.class, () -> registry.register(duplicateEntity));
    }

    private static TowerRecord tower(UUID team, int x, int y, int z) {
        return new TowerRecord(
                UUID.randomUUID(), team, UUID.randomUUID(), x, y, z, TowerType.ARROW, 1,
                UUID.randomUUID(), NOW, NOW);
    }
}
