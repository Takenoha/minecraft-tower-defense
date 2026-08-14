package io.github.takenoha.towerdefense.persistence

import java.util.Optional
import kotlin.jvm.JvmRecord

/** Result of an idempotent team-invitation mutation. */
@JvmRecord
data class TeamInvitationMutationResult(
    val outcome: ManagementOutcome,
    val invitation: TeamInvitation,
    val team: Optional<TeamRecord>,
)
