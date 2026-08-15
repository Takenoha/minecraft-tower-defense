package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TacticalCandidateGeneratorKotlinBoundaryAbiTest {
    @Test
    void keepsJavaConstructorConstantAndMethods() throws Exception {
        assertTrue(Modifier.isPublic(TacticalCandidateGenerator.class.getModifiers()));
        assertTrue(Modifier.isFinal(TacticalCandidateGenerator.class.getModifiers()));

        var constructor = TacticalCandidateGenerator.class.getConstructor();
        assertTrue(Modifier.isPublic(constructor.getModifiers()));

        var count = TacticalCandidateGenerator.class.getField("CANDIDATE_COUNT");
        assertTrue(Modifier.isPublic(count.getModifiers()));
        assertTrue(Modifier.isStatic(count.getModifiers()));
        assertTrue(Modifier.isFinal(count.getModifiers()));
        assertEquals(int.class, count.getType());
        assertEquals(3, count.getInt(null));

        var generate = TacticalCandidateGenerator.class.getMethod(
                "generate",
                UUID.class,
                UUID.class,
                UUID.class,
                int.class,
                int.class,
                List.class,
                Instant.class);
        assertTrue(Modifier.isPublic(generate.getModifiers()));
        assertEquals(TacticalCandidateSet.class, generate.getReturnType());

        var seedFor = TacticalCandidateGenerator.class.getMethod(
                "seedFor", UUID.class, UUID.class, int.class, int.class);
        assertTrue(Modifier.isPublic(seedFor.getModifiers()));
        assertTrue(Modifier.isStatic(seedFor.getModifiers()));
        assertEquals(long.class, seedFor.getReturnType());
    }

    @Test
    void preservesDeterministicSeedAndCandidateCount() {
        UUID startOperationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID teamId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertEquals(
                TacticalCandidateGenerator.seedFor(startOperationId, teamId, 3, 1),
                TacticalCandidateGenerator.seedFor(startOperationId, teamId, 3, 1));
        assertEquals(3, TacticalCandidateGenerator.CANDIDATE_COUNT);
    }
}
