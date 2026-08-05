package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.EnemyLedgerEntry;
import io.github.takenoha.towerdefense.persistence.EnemyStatus;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.PersistenceException;
import io.github.takenoha.towerdefense.persistence.StoredDefenseEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Serial asynchronous adapter from the Paper runtime to {@link DefenseRepository}. */
public final class AsyncDefensePersistenceSink implements DefensePersistenceSink {
    private final DefenseRepository repository;
    private final DatabaseExecutor executor;

    public AsyncDefensePersistenceSink(
            DefenseRepository repository,
            DatabaseExecutor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<Void> persistState(
            DefenseSessionSnapshot snapshot,
            UUID operationId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(operationId, "operationId");
        return executor.execute(() -> {
            StoredDefenseEvent current = repository.findEvent(snapshot.eventId())
                    .orElseThrow(() -> new PersistenceException(
                            "Defense event disappeared: " + snapshot.eventId(), null));
            OperationOutcome outcome;
            if (current.session().phase() == snapshot.phase()) {
                outcome = repository.saveSnapshot(snapshot, Instant.now());
            } else {
                outcome = repository.saveTransition(snapshot, operationId, Instant.now());
            }
            requireNonTerminalApplied("persist defense state", outcome);
        });
    }

    @Override
    public CompletionStage<Void> recordEnemySpawned(EnemyLedgerEntry enemy) {
        Objects.requireNonNull(enemy, "enemy");
        return executor.execute(() -> repository.upsertEnemy(enemy));
    }

    @Override
    public CompletionStage<Void> recordEnemyStatus(
            UUID eventId,
            UUID logicalEnemyId,
            UUID entityId,
            EnemyStatus status) {
        return executor.execute(() -> repository.updateEnemyStatus(
                eventId,
                logicalEnemyId,
                entityId,
                status,
                Instant.now()));
    }

    @Override
    public CompletionStage<Void> creditBattleFunds(
            UUID eventId,
            UUID teamId,
            UUID operationId,
            String operationKind,
            long amount) {
        return executor.execute(() -> repository.creditBattleFunds(
                eventId,
                teamId,
                operationId,
                operationKind,
                amount,
                Instant.now()));
    }

    @Override
    public CompletionStage<Void> finish(
            DefenseSessionSnapshot snapshot,
            UUID operationId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(operationId, "operationId");
        return executor.execute(() -> {
            OperationOutcome outcome = snapshot.phase() == DefensePhase.RECOVERY
                    ? repository.recoverUnfinishedEvent(
                            snapshot.eventId(), operationId, Instant.now())
                    : repository.finishEvent(snapshot, operationId, Instant.now());
            if (outcome == OperationOutcome.STATE_MISMATCH) {
                throw new PersistenceException(
                        "Terminal state did not match the persisted lifecycle", null);
            }
        });
    }

    private static void requireNonTerminalApplied(
            String operation,
            OperationOutcome outcome) {
        if (outcome != OperationOutcome.APPLIED
                && outcome != OperationOutcome.ALREADY_APPLIED) {
            throw new PersistenceException(operation + " failed with " + outcome, null);
        }
    }
}
