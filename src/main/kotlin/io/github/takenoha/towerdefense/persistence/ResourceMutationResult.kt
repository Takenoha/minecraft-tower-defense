package io.github.takenoha.towerdefense.persistence

import kotlin.jvm.JvmRecord

/** Result of an idempotent wallet mutation. */
@JvmRecord
data class ResourceMutationResult(
    val outcome: OperationOutcome,
    val resources: TeamResourceSnapshot,
)
