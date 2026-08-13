package io.github.takenoha.towerdefense.tactical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import io.github.takenoha.towerdefense.domain.StageWaveSchedule;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.StartRequest;
import io.github.takenoha.towerdefense.persistence.TacticalBuildRepository;
import io.github.takenoha.towerdefense.persistence.TacticalDefinitionCodec;
import io.github.takenoha.towerdefense.persistence.TacticalSelectionResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TacticalBuildFoundationTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsContainSevenCompleteBuildsAndCodecKeepsDefinitionSnapshot() {
        TacticalBuildCatalog catalog = TacticalBuildCatalog.defaults();

        assertEquals(7, catalog.definitions().size());
        assertTrue(catalog.definitions().stream().allMatch(definition -> definition.nodes().size() == 6));
        TacticalBuildDefinition original = catalog.require("ice-lightning");
        assertEquals(original, TacticalDefinitionCodec.decode(
                TacticalDefinitionCodec.encode(original)));
    }

    @Test
    void generatorIsDeterministicAndHasThreeDistinctCandidates() {
        TacticalBuildCatalog catalog = TacticalBuildCatalog.defaults();
        TacticalCandidateGenerator generator = new TacticalCandidateGenerator();
        UUID startOperationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        TacticalCandidateSet first = generator.generate(
                UUID.randomUUID(),
                startOperationId,
                teamId,
                3,
                TacticalBuildCatalog.GENERATOR_VERSION,
                catalog.definitions(),
                NOW);
        TacticalCandidateSet second = generator.generate(
                first.tacticalSessionId(),
                startOperationId,
                teamId,
                3,
                TacticalBuildCatalog.GENERATOR_VERSION,
                catalog.definitions(),
                NOW.plusSeconds(30L));

        assertEquals(
                first.candidates().stream().map(candidate -> candidate.definition().id()).toList(),
                second.candidates().stream().map(candidate -> candidate.definition().id()).toList());
        assertEquals(3, first.candidates().stream()
                .map(candidate -> candidate.definition().id()).distinct().count());
        assertTrue(first.candidates().stream()
                .map(candidate -> candidate.definition().category()).distinct().count() >= 2);
    }

    @Test
    void generatorExcludesDisabledBuildsAndRejectsAnInsufficientPool() {
        TacticalBuildCatalog catalog = TacticalBuildCatalog.defaults();
        TacticalBuildDefinition disabledSource = catalog.require("rapid-fire");
        TacticalBuildDefinition disabled = new TacticalBuildDefinition(
                disabledSource.id(),
                disabledSource.version(),
                disabledSource.displayName(),
                disabledSource.description(),
                disabledSource.category(),
                disabledSource.rarity(),
                false,
                disabledSource.weight(),
                disabledSource.iconMaterial(),
                disabledSource.targetTowerTypes(),
                disabledSource.requiredUnlockId(),
                disabledSource.nodes());
        List<TacticalBuildDefinition> definitions = new ArrayList<>(catalog.definitions());
        definitions.set(0, disabled);

        TacticalCandidateSet candidates = new TacticalCandidateGenerator().generate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                TacticalBuildCatalog.GENERATOR_VERSION,
                definitions,
                NOW);

        assertTrue(candidates.candidates().stream()
                .noneMatch(candidate -> candidate.definition().id().equals("rapid-fire")));
        assertThrows(
                IllegalStateException.class,
                () -> new TacticalCandidateGenerator().generate(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        TacticalBuildCatalog.GENERATOR_VERSION,
                        catalog.definitions().subList(0, 2),
                        NOW));
    }

    @Test
    void ownerSelectionUnlockProgressAndTerminalRecoveryAreIdempotent() {
        Path databaseFile = temporaryDirectory.resolve("tactical.sqlite");
        Database database = new Database(databaseFile);
        DefenseRepository defenses = new DefenseRepository(database);
        TacticalBuildRepository tactical = new TacticalBuildRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        defenses.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                100,
                100,
                NOW,
                NOW);
        defenses.placeCore(core, 0.0d);

        TacticalCandidateSet candidates = new TacticalCandidateGenerator().generate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                teamId,
                1,
                TacticalBuildCatalog.GENERATOR_VERSION,
                TacticalBuildCatalog.defaults().definitions(),
                NOW);
        tactical.createCandidates(candidates);
        assertEquals(
                candidates.candidates().stream().map(TacticalCandidate::definition).toList(),
                tactical.findGeneratedByTeamAndStage(teamId, 1).orElseThrow().candidates().stream()
                        .map(TacticalCandidate::definition).toList());
        TacticalCandidateSet restored = tactical.findCandidates(candidates.tacticalSessionId())
                .orElseThrow();
        assertEquals(
                candidates.candidates().stream().map(TacticalCandidate::definition).toList(),
                restored.candidates().stream().map(TacticalCandidate::definition).toList());
        assertThrows(
                RuntimeException.class,
                () -> tactical.selectBuild(
                        candidates.tacticalSessionId(),
                        UUID.randomUUID(),
                        candidates.candidates().getFirst().definition().id(),
                        UUID.randomUUID(),
                        NOW));
        assertThrows(
                RuntimeException.class,
                () -> tactical.selectBuild(
                        candidates.tacticalSessionId(),
                        ownerId,
                        "missing-build",
                        UUID.randomUUID(),
                        NOW));
        TacticalBuildDefinition selectedDefinition =
                candidates.candidates().getFirst().definition();
        String selectedBuildId = selectedDefinition.id();
        String selectedBranchId = selectedDefinition.branchIds().stream()
                .findFirst()
                .orElse(null);
        UUID selectionOperationId = UUID.randomUUID();
        TacticalSelectionResult selected = tactical.selectBuild(
                candidates.tacticalSessionId(),
                ownerId,
                selectedBuildId,
                selectedBranchId,
                selectionOperationId,
                NOW.plusSeconds(1L));
        assertEquals(OperationOutcome.APPLIED, selected.outcome());
        assertEquals(0, selected.selection().highestUnlockedTier());
        TacticalSelectionResult selectionRetry = tactical.selectBuild(
                candidates.tacticalSessionId(),
                ownerId,
                selectedBuildId,
                selectedBranchId,
                selectionOperationId,
                NOW.plusSeconds(2L));
        assertEquals(OperationOutcome.ALREADY_APPLIED, selectionRetry.outcome());
        assertEquals(selected.selection(), selectionRetry.selection());

        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                teamId,
                1,
                8,
                CoreState.intact(100));
        assertEquals(
                io.github.takenoha.towerdefense.persistence.StartOutcome.STARTED,
                defenses.tryStart(new StartRequest(
                        session.snapshot(),
                        core.id(),
                        "test-config",
                        1,
                        NOW)));
        assertEquals(
                OperationOutcome.APPLIED,
                tactical.bindToDefense(
                        candidates.tacticalSessionId(),
                        session.eventId(),
                        UUID.randomUUID(),
                        NOW.plusSeconds(2L)));
        assertEquals(
                OperationOutcome.APPLIED,
                tactical.activateAtPreparation(session.eventId(), UUID.randomUUID()).outcome());
        assertEquals(1, tactical.findActiveByDefense(session.eventId()).orElseThrow()
                .highestUnlockedTier());
        assertEquals(2, tactical.advanceAfterWave(
                session.eventId(), 1, StageWaveSchedule.wavesFor(1), UUID.randomUUID()).highestUnlockedTier());
        assertEquals(5, tactical.advanceAfterWave(
                session.eventId(), 5, StageWaveSchedule.wavesFor(1), UUID.randomUUID()).highestUnlockedTier());
        assertEquals(6, tactical.activateFinalTier(
                session.eventId(), UUID.randomUUID()).highestUnlockedTier());

        tactical.markTerminal(
                session.eventId(),
                TacticalTerminalResult.VICTORY,
                UUID.randomUUID());
        assertTrue(tactical.findActiveByDefense(session.eventId()).isEmpty());

        TacticalCandidateSet cancelled = new TacticalCandidateGenerator().generate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                teamId,
                1,
                TacticalBuildCatalog.GENERATOR_VERSION,
                TacticalBuildCatalog.defaults().definitions(),
                NOW.plusSeconds(3L));
        tactical.createCandidates(cancelled);
        assertEquals(
                OperationOutcome.APPLIED,
                tactical.cancelBeforeSelection(
                        cancelled.tacticalSessionId(),
                        ownerId,
                        UUID.randomUUID(),
                        NOW.plusSeconds(4L)));
        assertThrows(
                RuntimeException.class,
                () -> tactical.selectBuild(
                        cancelled.tacticalSessionId(),
                        ownerId,
                        cancelled.candidates().getFirst().definition().id(),
                        UUID.randomUUID(),
                        NOW.plusSeconds(5L)));

        TacticalBuildRepository restartedTactical = new TacticalBuildRepository(
                new Database(databaseFile));
        assertEquals(
                restored.candidates().stream().map(TacticalCandidate::definition).toList(),
                restartedTactical.findCandidates(candidates.tacticalSessionId()).orElseThrow()
                        .candidates().stream().map(TacticalCandidate::definition).toList());
    }

    @Test
    void selectedBranchUnlocksOnlyChosenPathAndPersistsAcrossRestart() {
        Path databaseFile = temporaryDirectory.resolve("tactical-branch.sqlite");
        Database database = new Database(databaseFile);
        DefenseRepository defenses = new DefenseRepository(database);
        TacticalBuildRepository tactical = new TacticalBuildRepository(database);
        UUID teamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        defenses.createSoloTeam(teamId, ownerId, NOW);
        CoreRecord core = new CoreRecord(
                UUID.randomUUID(),
                teamId,
                UUID.randomUUID(),
                0,
                64,
                0,
                100,
                100,
                NOW,
                NOW);
        defenses.placeCore(core, 0.0d);

        TacticalBuildCatalog catalog = TacticalBuildCatalog.defaults();
        TacticalBuildDefinition branched = catalog.require("arrow-specialization");
        TacticalCandidateSet candidates = new TacticalCandidateSet(
                UUID.randomUUID(),
                UUID.randomUUID(),
                teamId,
                1,
                42L,
                TacticalBuildCatalog.GENERATOR_VERSION,
                List.of(
                        new TacticalCandidate(0, branched),
                        new TacticalCandidate(1, catalog.require("rapid-fire")),
                        new TacticalCandidate(2, catalog.require("long-range"))),
                NOW);
        tactical.createCandidates(candidates);

        TacticalSelectionResult selected = tactical.selectBuild(
                candidates.tacticalSessionId(),
                ownerId,
                branched.id(),
                "rapid-fire",
                UUID.randomUUID(),
                NOW.plusSeconds(1L));
        assertEquals("rapid-fire", selected.selection().selectedBranchId().orElseThrow());
        assertTrue(selected.selection().unlockedNodeIds().isEmpty());

        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                teamId,
                1,
                8,
                CoreState.intact(100));
        assertEquals(
                io.github.takenoha.towerdefense.persistence.StartOutcome.STARTED,
                defenses.tryStart(new StartRequest(
                        session.snapshot(),
                        core.id(),
                        "test-config",
                        1,
                        NOW)));
        assertEquals(
                OperationOutcome.APPLIED,
                tactical.bindToDefense(
                        candidates.tacticalSessionId(),
                        session.eventId(),
                        UUID.randomUUID(),
                        NOW.plusSeconds(2L)));

        TacticalUnlockResult preparation = tactical.activateAtPreparation(
                session.eventId(), UUID.randomUUID());
        assertEquals(
                List.of("arrow-specialization-rapid-fire-tier-1"),
                preparation.newlyUnlockedNodeIds());
        TacticalBuildSelectionView afterPreparation = tactical.findActiveByDefense(session.eventId())
                .orElseThrow();
        assertEquals(
                Set.of("arrow-specialization-rapid-fire-tier-1"),
                afterPreparation.unlockedNodeIds());
        assertFalse(afterPreparation.unlockedNodeIds().contains(
                "arrow-specialization-range-tier-1"));

        TacticalUnlockResult waveTwo = tactical.advanceAfterWave(
                session.eventId(),
                1,
                StageWaveSchedule.wavesFor(1),
                UUID.randomUUID());
        assertEquals(
                List.of("arrow-specialization-rapid-fire-tier-2"),
                waveTwo.newlyUnlockedNodeIds());
        TacticalUnlockResult finalTier = tactical.activateFinalTier(
                session.eventId(), UUID.randomUUID());
        assertEquals(
                List.of("arrow-specialization-rapid-fire-tier-3"),
                finalTier.newlyUnlockedNodeIds());

        TacticalBuildSelectionView beforeRestart = tactical.findActiveByDefense(session.eventId())
                .orElseThrow();
        assertEquals(6, beforeRestart.highestUnlockedTier());
        assertEquals(
                Set.of(
                        "arrow-specialization-rapid-fire-tier-1",
                        "arrow-specialization-rapid-fire-tier-2",
                        "arrow-specialization-rapid-fire-tier-3"),
                beforeRestart.unlockedNodeIds());
        assertTrue(beforeRestart.unlockedNodeIds().stream()
                .noneMatch(nodeId -> nodeId.contains("-range-")));

        TacticalBuildSelectionView afterRestart = new TacticalBuildRepository(
                new Database(databaseFile))
                .findActiveByDefense(session.eventId())
                .orElseThrow();
        assertEquals("rapid-fire", afterRestart.selectedBranchId().orElseThrow());
        assertEquals(beforeRestart.unlockedNodeIds(), afterRestart.unlockedNodeIds());
    }
}
