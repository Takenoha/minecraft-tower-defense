package io.github.takenoha.towerdefense.persistence

import java.util.Optional
import kotlin.jvm.JvmRecord

/** Result of a team mutation, including the post-operation durable team when it still exists. */
@JvmRecord
data class TeamMutationResult(
    val outcome: ManagementOutcome,
    val team: Optional<TeamRecord>,
)
