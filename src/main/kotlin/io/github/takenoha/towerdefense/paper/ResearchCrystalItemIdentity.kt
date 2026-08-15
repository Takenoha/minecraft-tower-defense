package io.github.takenoha.towerdefense.paper

import java.util.Objects
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Team and issuance-batch identity carried by a delivered research crystal. */
@JvmRecord
data class ResearchCrystalItemIdentity(
    val batchId: UUID,
    val teamId: UUID,
    val issuedQuantity: Int,
    val segmentOffset: Int?,
    val segmentQuantity: Int?,
) {
    constructor(batchId: UUID, teamId: UUID, issuedQuantity: Int) :
        this(batchId, teamId, issuedQuantity, null, null)

    init {
        Objects.requireNonNull(batchId, "batchId")
        Objects.requireNonNull(teamId, "teamId")
        require(issuedQuantity > 0) { "issuedQuantity must be positive" }
        require((segmentOffset == null) == (segmentQuantity == null)) {
            "segmentOffset and segmentQuantity must be supplied together"
        }
        if (segmentOffset != null &&
            (segmentOffset < 0 || segmentQuantity!! <= 0 ||
                segmentOffset.toLong() + segmentQuantity > issuedQuantity)
        ) {
            throw IllegalArgumentException("research crystal segment is outside the batch")
        }
    }

    fun hasSegmentIdentity(): Boolean = segmentOffset != null
}
