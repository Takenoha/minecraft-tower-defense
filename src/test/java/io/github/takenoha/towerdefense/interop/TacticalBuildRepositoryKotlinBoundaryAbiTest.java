package io.github.takenoha.towerdefense.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.TacticalBuildRepository;
import io.github.takenoha.towerdefense.persistence.TacticalSelectionResult;
import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet;
import io.github.takenoha.towerdefense.tactical.TacticalTerminalResult;
import io.github.takenoha.towerdefense.tactical.TacticalUnlockResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java-facing ABI checks for the Kotlin TacticalBuildRepository boundary. */
class TacticalBuildRepositoryKotlinBoundaryAbiTest {
    @Test
    void publicRepositoryMethodsKeepTheirJavaSignatures() throws Exception {
        assertNotNull(TacticalBuildRepository.class.getConstructor(Database.class));
        assertMethod("createCandidates", TacticalCandidateSet.class, TacticalCandidateSet.class);
        assertMethod("findCandidates", Optional.class, UUID.class);
        assertMethod("findGeneratedByTeamAndStage", Optional.class, UUID.class, int.class);
        assertMethod(
                "selectBuild",
                TacticalSelectionResult.class,
                UUID.class,
                UUID.class,
                String.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "selectBuild",
                TacticalSelectionResult.class,
                UUID.class,
                UUID.class,
                String.class,
                String.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "bindToDefense",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod(
                "cancelBeforeSelection",
                OperationOutcome.class,
                UUID.class,
                UUID.class,
                UUID.class,
                Instant.class);
        assertMethod("findActiveByDefense", Optional.class, UUID.class);
        assertMethod(
                "activateAtPreparation",
                TacticalUnlockResult.class,
                UUID.class,
                UUID.class);
        assertMethod(
                "advanceAfterWave",
                TacticalUnlockResult.class,
                UUID.class,
                int.class,
                int.class,
                UUID.class);
        assertMethod(
                "activateFinalTier",
                TacticalUnlockResult.class,
                UUID.class,
                UUID.class);
        assertMethod(
                "markTerminal",
                void.class,
                UUID.class,
                TacticalTerminalResult.class,
                UUID.class);
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        assertEquals(
                returnType,
                TacticalBuildRepository.class.getMethod(name, parameterTypes).getReturnType(),
                name);
    }
}
