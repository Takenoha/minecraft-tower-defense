package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.EscrowRepository
import io.github.takenoha.towerdefense.persistence.OperationOutcome
import io.github.takenoha.towerdefense.persistence.RewardDeliveryOutcome
import io.github.takenoha.towerdefense.persistence.RewardQueueEntry
import io.github.takenoha.towerdefense.persistence.RewardQueueStatus
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.Objects
import java.util.UUID
import java.util.logging.Level
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.Plugin

/** Main-thread inventory bridge for database-owned terminal rewards. */
class RewardQueueDeliveryManager(
    plugin: Plugin,
    escrow: EscrowRepository,
    databaseExecutor: DatabaseExecutor,
    tagger: RewardQueueReceiptTagger,
    researchCrystals: ResearchCrystalTagger,
) : AutoCloseable {
    constructor(
        plugin: Plugin,
        escrow: EscrowRepository,
        databaseExecutor: DatabaseExecutor,
        tagger: RewardQueueReceiptTagger,
    ) : this(
        plugin,
        escrow,
        databaseExecutor,
        tagger,
        ResearchCrystalTagger(plugin),
    )

    private val pluginValue = Objects.requireNonNull(plugin, "plugin")
    private val escrowValue = Objects.requireNonNull(escrow, "escrow")
    private val databaseExecutorValue = Objects.requireNonNull(databaseExecutor, "databaseExecutor")
    private val taggerValue = Objects.requireNonNull(tagger, "tagger")
    private val researchCrystalsValue = Objects.requireNonNull(researchCrystals, "researchCrystals")
    private val activeRuns = HashMap<UUID, DeliveryRun>()
    private var closed = false

    /** Retries personal and team queue rows when a player finishes joining. */
    fun onPlayerJoin(player: Player) {
        requireMainThread()
        Objects.requireNonNull(player, "player")
        request(player)
    }

    /** Releases the in-memory guard so a quick rejoin can start a fresh database read. */
    fun onPlayerQuit(player: Player) {
        requireMainThread()
        Objects.requireNonNull(player, "player")
        activeRuns.remove(player.uniqueId)
    }

    /** Tries to deliver newly issued rows to players who are already online. */
    fun onEventSettled(eventId: UUID) {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        if (closed) {
            return
        }
        for (player in Bukkit.getOnlinePlayers()) {
            request(player)
        }
    }

    fun tagger(): RewardQueueReceiptTagger = taggerValue

    override fun close() {
        requireMainThread()
        closed = true
        activeRuns.clear()
    }

    private fun request(player: Player) {
        if (closed || activeRuns.containsKey(player.uniqueId)) {
            return
        }
        val run = DeliveryRun(player.uniqueId)
        activeRuns[run.playerId] = run
        val receiptQueueIds = receiptQueueIds(player)
        databaseExecutorValue.submit {
            loadForPlayer(run.playerId, receiptQueueIds)
        }.whenComplete { loaded, failure ->
            runOnMainThread {
                if (!isCurrent(run)) {
                    return@runOnMainThread
                }
                if (failure != null) {
                    logFailure("Could not load pending rewards for ${run.playerId}", failure)
                    finish(run)
                    return@runOnMainThread
                }
                val result = Objects.requireNonNull(loaded, "loaded")
                for (queueId in result.cleanupQueueIds) {
                    stripReceipts(player, queueId)
                }
                deliverNext(run, result.pendingEntries, 0)
            }
        }
    }

    private fun loadForPlayer(
        playerId: UUID,
        receiptQueueIds: Set<UUID>,
    ): RewardLoadResult {
        val cleanupQueueIds = LinkedHashSet<UUID>()
        for (queueId in receiptQueueIds) {
            val status = escrowValue.findRewardQueue(queueId)
                .map { it.status }
                .orElse(RewardQueueStatus.VOIDED)
            if (status != RewardQueueStatus.PENDING) {
                cleanupQueueIds.add(queueId)
            }
        }
        return RewardLoadResult(
            java.util.List.copyOf(cleanupQueueIds),
            java.util.List.copyOf(escrowValue.loadPendingRewardQueueForPlayer(playerId)),
        )
    }

    private fun deliverNext(
        run: DeliveryRun,
        entries: List<RewardQueueEntry>,
        index: Int,
    ) {
        if (!isCurrent(run)) {
            return
        }
        val player = Bukkit.getPlayer(run.playerId)
        if (player == null || !player.isOnline || index >= entries.size) {
            finish(run)
            return
        }
        val entry = entries[index]
        if (entry.status != RewardQueueStatus.PENDING) {
            deliverNext(run, entries, index + 1)
            return
        }
        deliverOne(
            player,
            entry,
            { deliverNext(run, entries, index + 1) },
            { finish(run) },
        )
    }

    private fun deliverOne(
        player: Player,
        entry: RewardQueueEntry,
        continueDelivery: Runnable,
        stopDelivery: Runnable,
    ) {
        val operationId = deterministicDeliveryOperation(entry.queueId, player.uniqueId)
        databaseExecutorValue.submit {
            escrowValue.prepareRewardDelivery(
                entry.queueId,
                player.uniqueId,
                operationId,
                Instant.now(),
            )
        }.whenComplete { outcome, failure ->
            runOnMainThread {
                if (failure != null) {
                    logFailure("Could not reserve reward queue ${entry.queueId}", failure)
                    stopDelivery.run()
                    return@runOnMainThread
                }
                if (outcome == RewardDeliveryOutcome.ACQUIRED ||
                    outcome == RewardDeliveryOutcome.ALREADY_ACQUIRED
                ) {
                    deliverReserved(player, entry, continueDelivery, stopDelivery)
                    return@runOnMainThread
                }
                if (outcome == RewardDeliveryOutcome.ALREADY_DELIVERED ||
                    outcome == RewardDeliveryOutcome.VOIDED
                ) {
                    stripReceipts(player, entry.queueId)
                }
                if (outcome == RewardDeliveryOutcome.HELD_BY_OTHER) {
                    pluginValue.logger.fine(
                        "Reward queue ${entry.queueId} is reserved by another eligible team member",
                    )
                }
                stopDelivery.run()
            }
        }
    }

    private fun deliverReserved(
        player: Player,
        entry: RewardQueueEntry,
        continueDelivery: Runnable,
        stopDelivery: Runnable,
    ) {
        val alreadyAccepted = receiptQuantity(player, entry.queueId)
        val remaining = entry.quantity - alreadyAccepted
        if (remaining <= 0) {
            markDelivered(player, entry, continueDelivery, stopDelivery)
            return
        }

        val payload = try {
            decodePayload(entry)
        } catch (invalidPayload: RuntimeException) {
            logFailure(
                "Reward queue ${entry.queueId} has an invalid item payload",
                invalidPayload,
            )
            stopDelivery.run()
            return
        }
        val deliveryOperation = deterministicDeliveryOperation(entry.queueId, player.uniqueId)
        val stacks = try {
            val receipt = RewardQueueReceipt(entry.queueId, deliveryOperation)
            if (entry.itemId == "research_crystal") {
                decodeResearchCrystalStacks(entry, remaining, alreadyAccepted, receipt)
            } else {
                splitAndTag(payload, remaining, receipt)
            }
        } catch (invalidItem: RuntimeException) {
            logFailure(
                "Reward queue ${entry.queueId} cannot create an inventory item",
                invalidItem,
            )
            stopDelivery.run()
            return
        }
        val leftovers = try {
            player.inventory.addItem(*stacks.toTypedArray())
        } catch (inventoryFailure: RuntimeException) {
            logFailure(
                "Could not add reward queue ${entry.queueId} to inventory",
                inventoryFailure,
            )
            stopDelivery.run()
            return
        }
        if (leftovers.isNotEmpty()) {
            pluginValue.logger.fine(
                "Reward queue ${entry.queueId} remains pending because the inventory is full for " +
                    player.uniqueId,
            )
            stopDelivery.run()
            return
        }
        markDelivered(player, entry, continueDelivery, stopDelivery)
    }

    private fun decodePayload(entry: RewardQueueEntry): ItemStack {
        if (entry.itemId != "research_crystal") {
            return PaperItemStackCodec.decode(entry.itemPayload)
        }
        val fields = entry.itemPayload.split(":", ignoreCase = false, limit = Int.MAX_VALUE)
        if (fields.size != 5 || fields[0] != "research_crystal" ||
            (fields[1] != "v1" && fields[1] != "v2")
        ) {
            throw IllegalArgumentException("The research crystal payload is invalid")
        }
        return try {
            val batchId = UUID.fromString(fields[2])
            val teamId = UUID.fromString(fields[3])
            val issuedQuantity = Integer.parseInt(fields[4])
            if (issuedQuantity != entry.quantity) {
                throw IllegalArgumentException(
                    "The research crystal payload quantity does not match the queue",
                )
            }
            researchCrystalsValue.create(batchId, teamId, issuedQuantity)
        } catch (invalidPayload: IllegalArgumentException) {
            throw IllegalArgumentException("The research crystal payload is invalid", invalidPayload)
        }
    }

    private fun decodeResearchCrystalStacks(
        entry: RewardQueueEntry,
        quantity: Int,
        offset: Int,
        receipt: RewardQueueReceipt,
    ): List<ItemStack> {
        val fields = entry.itemPayload.split(":", ignoreCase = false, limit = Int.MAX_VALUE)
        if (fields.size != 5 || fields[0] != "research_crystal" || fields[1] != "v2") {
            return splitAndTag(decodePayload(entry), quantity, receipt)
        }
        val batchId: UUID
        val teamId: UUID
        val issuedQuantity: Int
        try {
            batchId = UUID.fromString(fields[2])
            teamId = UUID.fromString(fields[3])
            issuedQuantity = Integer.parseInt(fields[4])
        } catch (invalidPayload: IllegalArgumentException) {
            throw IllegalArgumentException("The research crystal payload is invalid", invalidPayload)
        }
        if (issuedQuantity != entry.quantity ||
            offset < 0 ||
            quantity < 0 ||
            offset.toLong() + quantity > issuedQuantity
        ) {
            throw IllegalArgumentException("The research crystal payload quantity is invalid")
        }
        val stacks = ArrayList<ItemStack>()
        var remaining = quantity
        var segmentOffset = offset
        while (remaining > 0) {
            val segmentQuantity = minOf(ResearchCrystalTagger.STACK_LIMIT, remaining)
            val stack = researchCrystalsValue.create(
                batchId,
                teamId,
                issuedQuantity,
                segmentOffset,
                segmentQuantity,
            )
            stacks.add(taggerValue.tag(stack, receipt))
            segmentOffset += segmentQuantity
            remaining -= segmentQuantity
        }
        return java.util.List.copyOf(stacks)
    }

    private fun markDelivered(
        player: Player,
        entry: RewardQueueEntry,
        continueDelivery: Runnable,
        stopDelivery: Runnable,
    ) {
        val operationId = deterministicDeliveryOperation(entry.queueId, player.uniqueId)
        databaseExecutorValue.submit {
            escrowValue.markRewardDelivered(
                entry.queueId,
                player.uniqueId,
                operationId,
                Instant.now(),
            )
        }.whenComplete { outcome, failure ->
            runOnMainThread {
                if (failure != null) {
                    logFailure("Could not commit reward queue ${entry.queueId}", failure)
                    stopDelivery.run()
                    return@runOnMainThread
                }
                if (outcome != OperationOutcome.APPLIED &&
                    outcome != OperationOutcome.ALREADY_APPLIED
                ) {
                    logFailure(
                        "Reward queue ${entry.queueId} was not delivered: $outcome",
                        null,
                    )
                    stopDelivery.run()
                    return@runOnMainThread
                }
                stripReceipts(player, entry.queueId)
                continueDelivery.run()
            }
        }
    }

    private fun splitAndTag(
        payload: ItemStack,
        quantity: Int,
        receipt: RewardQueueReceipt,
    ): List<ItemStack> {
        val stackLimit = payload.maxStackSize
        if (stackLimit <= 0) {
            throw IllegalArgumentException("The reward item has no positive stack limit")
        }
        val stacks = ArrayList<ItemStack>()
        var remaining = quantity
        while (remaining > 0) {
            val amount = minOf(stackLimit, remaining)
            val stack = payload.clone()
            stack.amount = amount
            stacks.add(taggerValue.tag(stack, receipt))
            remaining -= amount
        }
        return java.util.List.copyOf(stacks)
    }

    private fun receiptQuantity(player: Player, queueId: UUID): Int {
        var quantity = 0L
        val inventory: PlayerInventory = player.inventory
        for (item in inventory.contents) {
            if (item == null) {
                continue
            }
            val receipt = taggerValue.read(item)
            if (receipt.isPresent && queueId == receipt.orElseThrow().queueId) {
                quantity += item.amount.toLong()
            }
        }
        return if (quantity > Int.MAX_VALUE) Int.MAX_VALUE else quantity.toInt()
    }

    private fun receiptQueueIds(player: Player): Set<UUID> {
        val queueIds = LinkedHashSet<UUID>()
        for (item in player.inventory.contents) {
            if (item != null) {
                taggerValue.read(item).ifPresent { queueIds.add(it.queueId) }
            }
        }
        return java.util.Set.copyOf(queueIds)
    }

    private fun stripReceipts(player: Player, queueId: UUID) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot)
            if (item == null) {
                continue
            }
            val receipt = taggerValue.read(item)
            if (receipt.isPresent && queueId == receipt.orElseThrow().queueId) {
                inventory.setItem(slot, taggerValue.strip(item))
            }
        }
    }

    private fun isCurrent(run: DeliveryRun): Boolean =
        !closed && activeRuns[run.playerId] === run

    private fun finish(run: DeliveryRun) {
        if (activeRuns[run.playerId] === run) {
            activeRuns.remove(run.playerId)
        }
    }

    private fun runOnMainThread(task: Runnable) {
        if (Bukkit.isPrimaryThread()) {
            task.run()
        } else {
            Bukkit.getScheduler().runTask(pluginValue, task)
        }
    }

    private fun logFailure(message: String, failure: Throwable?) {
        if (failure == null) {
            pluginValue.logger.warning(message)
        } else {
            pluginValue.logger.log(Level.WARNING, message, rootCause(failure))
        }
    }

    private companion object {
        @JvmStatic
        private fun deterministicDeliveryOperation(queueId: UUID, playerId: UUID): UUID =
            UUID.nameUUIDFromBytes(
                (queueId.toString() + "|" + playerId + "|DELIVERY")
                    .toByteArray(StandardCharsets.UTF_8),
            )

        @JvmStatic
        private fun rootCause(failure: Throwable): Throwable {
            var current = failure
            while (current.cause != null) {
                current = current.cause ?: break
            }
            return current
        }

        @JvmStatic
        private fun requireMainThread() {
            if (!Bukkit.isPrimaryThread()) {
                throw IllegalStateException("Reward queue delivery must run on the Paper main thread")
            }
        }
    }

    private data class DeliveryRun(val playerId: UUID)

    private data class RewardLoadResult(
        val cleanupQueueIds: List<UUID>,
        val pendingEntries: List<RewardQueueEntry>,
    )
}
