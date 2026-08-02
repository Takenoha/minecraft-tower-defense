package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import io.github.takenoha.towerdefense.persistence.EnemyLedgerEntry;
import io.github.takenoha.towerdefense.persistence.EnemyStatus;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Asynchronous persistence boundary used by the main-thread encounter runtime. */
public interface DefensePersistenceSink {
    CompletionStage<Void> persistState(
            DefenseSessionSnapshot snapshot,
            UUID operationId);

    CompletionStage<Void> recordEnemySpawned(EnemyLedgerEntry enemy);

    CompletionStage<Void> recordEnemyStatus(
            UUID eventId,
            UUID logicalEnemyId,
            UUID entityId,
            EnemyStatus status);

    CompletionStage<Void> finish(
            DefenseSessionSnapshot snapshot,
            UUID operationId);
}
