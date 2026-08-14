package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.ResourceType
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmRecord

/** Parsed, untrusted PDC view of a physical resource voucher. */
@JvmRecord
data class ResourceVoucherItemData(
    val voucherId: UUID,
    val teamId: UUID,
    val resourceType: ResourceType,
    val quantity: Long,
    val deliveryOperationId: Optional<UUID>,
    val redeemOperationId: Optional<UUID>,
) {
    init {
        Objects.requireNonNull(voucherId, "voucherId")
        Objects.requireNonNull(teamId, "teamId")
        ResourceType.require(resourceType)
        Objects.requireNonNull(deliveryOperationId, "deliveryOperationId")
        Objects.requireNonNull(redeemOperationId, "redeemOperationId")
        require(quantity > 0L) { "voucher quantity must be positive" }
        require(!(deliveryOperationId.isPresent && redeemOperationId.isPresent)) {
            "a voucher cannot have two receipt states"
        }
    }

    fun hasReceipt(): Boolean = deliveryOperationId.isPresent || redeemOperationId.isPresent
}
