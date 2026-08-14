package io.github.takenoha.towerdefense.persistence

import java.util.Optional
import kotlin.jvm.JvmRecord

/** Result of a UUID-protected core repair, move, or replacement. */
@JvmRecord
data class CoreMutationResult(
    val outcome: ManagementOutcome,
    val core: Optional<CoreRecord>,
)
