package io.github.takenoha.towerdefense.persistence

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** A database-derived team wallet view plus the viewer's active-event provisional claims. */
@JvmRecord
data class TeamResourceSnapshot(
    val teamId: UUID,
    val defensePoints: Long,
    val enhancementPoints: Long,
    val teamProvisionalDefensePoints: Long,
    val teamProvisionalEnhancementPoints: Long,
    val viewerProvisionalDefensePoints: Long,
    val viewerProvisionalEnhancementPoints: Long,
) {
    /** Compatibility constructor for callers that only supplied viewer provisional totals. */
    constructor(
        teamId: UUID,
        defensePoints: Long,
        enhancementPoints: Long,
        provisionalDefensePoints: Long,
        provisionalEnhancementPoints: Long,
    ) : this(
        teamId,
        defensePoints,
        enhancementPoints,
        provisionalDefensePoints,
        provisionalEnhancementPoints,
        provisionalDefensePoints,
        provisionalEnhancementPoints,
    )

    init {
        Objects.requireNonNull(teamId, "teamId")
        if (defensePoints < 0L || enhancementPoints < 0L ||
            teamProvisionalDefensePoints < 0L || teamProvisionalEnhancementPoints < 0L ||
            viewerProvisionalDefensePoints < 0L || viewerProvisionalEnhancementPoints < 0L
        ) {
            throw IllegalArgumentException("resource quantities must not be negative")
        }
    }

    fun balance(type: ResourceType): Long = when (ResourceType.require(type)) {
        ResourceType.DEFENSE_POINTS -> defensePoints
        ResourceType.ENHANCEMENT_POINTS -> enhancementPoints
    }

    fun provisional(type: ResourceType): Long = when (ResourceType.require(type)) {
        ResourceType.DEFENSE_POINTS -> viewerProvisionalDefensePoints
        ResourceType.ENHANCEMENT_POINTS -> viewerProvisionalEnhancementPoints
    }

    fun teamProvisional(type: ResourceType): Long = when (ResourceType.require(type)) {
        ResourceType.DEFENSE_POINTS -> teamProvisionalDefensePoints
        ResourceType.ENHANCEMENT_POINTS -> teamProvisionalEnhancementPoints
    }
}
