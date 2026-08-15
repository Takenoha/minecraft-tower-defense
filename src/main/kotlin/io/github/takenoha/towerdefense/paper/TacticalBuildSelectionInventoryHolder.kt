package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.tactical.TacticalCandidateSet
import java.util.Objects
import java.util.Optional
import java.util.UUID
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** Identifies the pre-defense tactical selection GUI and its non-consumptive start context. */
class TacticalBuildSelectionInventoryHolder(
    tacticalSessionId: UUID,
    coreId: UUID,
    stage: Long,
    sealId: UUID,
    ownerId: UUID,
    candidates: TacticalCandidateSet,
) : InventoryHolder {
    private val tacticalSessionIdValue = Objects.requireNonNull(tacticalSessionId, "tacticalSessionId")
    private val coreIdValue = Objects.requireNonNull(coreId, "coreId")
    private val stageValue = stage
    private val sealIdValue = Objects.requireNonNull(sealId, "sealId")
    private val ownerIdValue = Objects.requireNonNull(ownerId, "ownerId")
    private val candidatesValue = Objects.requireNonNull(candidates, "candidates")
    private var inventory: Inventory? = null
    private var selectedBuildIdValue: String? = null
    private var selectedBranchIdValue: String? = null
    private var confirmingValue = false

    init {
        if (stage <= 0L || stage != candidates.stage().toLong()) {
            throw IllegalArgumentException("selection stage does not match candidates")
        }
    }

    fun tacticalSessionId(): UUID = tacticalSessionIdValue

    fun coreId(): UUID = coreIdValue

    fun stage(): Long = stageValue

    fun sealId(): UUID = sealIdValue

    fun ownerId(): UUID = ownerIdValue

    fun candidates(): TacticalCandidateSet = candidatesValue

    fun selectedBuildId(): Optional<String> = Optional.ofNullable(selectedBuildIdValue)

    fun selectedBranchId(): Optional<String> = Optional.ofNullable(selectedBranchIdValue)

    fun branchRequired(): Boolean = selectedBuildIdValue?.let {
        candidatesValue.requireBuild(it).branchIds().isNotEmpty()
    } ?: false

    fun select(buildId: String?) {
        if (buildId == null) {
            throw IllegalArgumentException("build is not a candidate: null")
        }
        candidatesValue.requireBuild(buildId)
        if (buildId != selectedBuildIdValue) {
            selectedBranchIdValue = null
        }
        selectedBuildIdValue = buildId
    }

    fun selectBranch(branchId: String?) {
        val checkedBranchId = Objects.requireNonNull(branchId, "branchId") as String
        if (checkedBranchId.isBlank()) {
            throw IllegalArgumentException("branchId must not be blank")
        }
        val selectedBuildId = selectedBuildIdValue
            ?: throw IllegalStateException("select a tactical build before selecting a branch")
        if (!candidatesValue.requireBuild(selectedBuildId).branchIds().contains(checkedBranchId)) {
            throw IllegalArgumentException("branch is not available for the selected build")
        }
        selectedBranchIdValue = checkedBranchId
    }

    fun markConfirming() {
        confirmingValue = true
    }

    fun confirming(): Boolean = confirmingValue

    /** Publicized for Java callers because Kotlin has no package-private declaration. */
    fun attach(inventory: Inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory")
    }

    override fun getInventory(): Inventory =
        inventory ?: throw IllegalStateException("the tactical selection inventory has not been attached")
}
