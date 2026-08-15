package io.github.takenoha.towerdefense.paper

import io.github.takenoha.towerdefense.persistence.DropSourceKind
import io.github.takenoha.towerdefense.persistence.EscrowDrop
import io.github.takenoha.towerdefense.persistence.EscrowDropStatus
import io.github.takenoha.towerdefense.persistence.EscrowRepository
import io.github.takenoha.towerdefense.persistence.OperationOutcome
import io.github.takenoha.towerdefense.persistence.PersistenceConflictException
import io.github.takenoha.towerdefense.persistence.ResourcePickupFeedback
import io.github.takenoha.towerdefense.persistence.ResourceRepository
import io.github.takenoha.towerdefense.runtime.ActionBarBroker
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Objects
import java.util.Optional
import java.util.UUID
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Main-thread bridge between Paper item displays and the database-owned escrow. */
class PaperEscrowDropManager @JvmOverloads constructor(
    plugin: Plugin,
    escrow: EscrowRepository,
    databaseExecutor: DatabaseExecutor,
    tagger: EscrowDropTagger,
    resources: ResourceRepository? = null,
) {
    private val pluginValue = Objects.requireNonNull(plugin, "plugin")
    private val escrowValue = Objects.requireNonNull(escrow, "escrow")
    private val databaseExecutorValue = Objects.requireNonNull(databaseExecutor, "databaseExecutor")
    private val taggerValue = Objects.requireNonNull(tagger, "tagger")
    @Suppress("UNUSED_PARAMETER")
    private val resourcesValue = resources
    private val actionBars = ActionBarBroker()
    private val pendingClaims = HashMap<UUID, MutableSet<UUID>>()
    private val terminalEvents = HashSet<UUID>()

    /** Captures ordinary block drops before the corresponding block WAL apply. */
    fun prepareBlockDrops(
        eventId: UUID,
        sourceId: UUID,
        block: Block,
        occurredAt: Instant,
    ): List<PreparedDrop> {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(sourceId, "sourceId")
        Objects.requireNonNull(block, "block")
        Objects.requireNonNull(occurredAt, "occurredAt")

        val existing = escrowValue.loadDrops(eventId)
            .filter { value -> value.drop().sourceKind() == DropSourceKind.BLOCK }
            .filter { value -> value.drop().sourceId().equals(sourceId) }
        if (existing.isNotEmpty()) {
            if (existing.any { value -> value.status() != EscrowDropStatus.HELD }) {
                throw PersistenceConflictException("A block action already has a terminal escrow drop")
            }
            return java.util.List.copyOf(
                existing.map { value ->
                    PreparedDrop(
                        value.drop(),
                        PaperItemStackCodec.decode(value.drop().itemPayload()),
                    )
                },
            )
        }

        val prepared = ArrayList<PreparedDrop>()
        try {
            var index = 0
            for (itemStack in block.drops) {
                if (itemStack == null || itemStack.type.isAir || itemStack.amount <= 0) {
                    index++
                    continue
                }
                val storedStack = itemStack.clone()
                val dropId = deterministic(sourceId, "BLOCK_DROP", index.toString())
                val operationId = deterministic(sourceId, "DROP_CREATE", index.toString())
                val drop = EscrowDrop(
                    eventId,
                    dropId,
                    DropSourceKind.BLOCK,
                    sourceId,
                    storedStack.type.key.toString(),
                    PaperItemStackCodec.encode(storedStack),
                    storedStack.amount,
                    Optional.empty(),
                )
                escrowValue.prepare(drop, operationId, occurredAt)
                prepared.add(PreparedDrop(drop, storedStack))
                index++
            }
        } catch (prepareFailure: RuntimeException) {
            if (prepared.isNotEmpty()) {
                try {
                    discardPreparedDrops(prepared, occurredAt)
                } catch (discardFailure: RuntimeException) {
                    prepareFailure.addSuppressed(discardFailure)
                }
            }
            throw prepareFailure
        }
        return java.util.List.copyOf(prepared)
    }

    /** Spawns tagged, non-usable physical displays after the block mutation is acknowledged. */
    fun spawnPreparedDrops(sourceBlock: Block, preparedDrops: List<PreparedDrop>) {
        requireMainThread()
        Objects.requireNonNull(sourceBlock, "sourceBlock")
        Objects.requireNonNull(preparedDrops, "preparedDrops")
        for (prepared in preparedDrops) {
            if (findDisplay(prepared.drop.dropId()).isPresent) {
                continue
            }
            val location = sourceBlock.location.add(0.5, 0.5, 0.5)
            val display = sourceBlock.world.spawn(location, Item::class.java) { item ->
                item.itemStack = taggerValue.tag(prepared.itemStack, prepared.drop)
                item.pickupDelay = 0
                item.ticksLived = 1
                taggerValue.tag(item, prepared.drop)
            }
            databaseExecutorValue.execute {
                escrowValue.updateDisplayEntity(
                    prepared.drop.eventId(),
                    prepared.drop.dropId(),
                    Optional.of(display.uniqueId),
                    Instant.now(),
                )
            }
        }
    }

    /** Queues one event-enemy material without exposing a usable ItemStack during the event. */
    fun issueEnemyDrop(
        eventId: UUID,
        sourceId: UUID,
        location: Location,
        itemId: String,
        itemStack: ItemStack,
        createdAt: Instant,
    ) {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        Objects.requireNonNull(sourceId, "sourceId")
        Objects.requireNonNull(location, "location")
        Objects.requireNonNull(itemId, "itemId")
        Objects.requireNonNull(itemStack, "itemStack")
        Objects.requireNonNull(createdAt, "createdAt")
        if (itemStack.amount <= 0 || itemStack.type.isAir) {
            throw IllegalArgumentException("enemy drop item must be usable")
        }
        val dropId = deterministic(sourceId, "ENEMY_DROP", itemId)
        val createOperationId = deterministic(sourceId, "ENEMY_DROP_CREATE", itemId)
        val drop = EscrowDrop(
            eventId,
            dropId,
            DropSourceKind.ENEMY,
            sourceId,
            itemId,
            PaperItemStackCodec.encode(itemStack),
            itemStack.amount,
            Optional.empty(),
        )
        databaseExecutorValue.submit {
            escrowValue.prepare(drop, createOperationId, createdAt)
        }.whenComplete { _, failure ->
            Bukkit.getScheduler().runTask(pluginValue, Runnable {
                if (failure != null) {
                    pluginValue.logger.log(
                        java.util.logging.Level.WARNING,
                        "Could not prepare event enemy drop ${drop.dropId()}",
                        failure,
                    )
                } else {
                    spawnPreparedDrop(location, PreparedDrop(drop, itemStack))
                }
            })
        }
    }

    private fun spawnPreparedDrop(location: Location, prepared: PreparedDrop) {
        if (findDisplay(prepared.drop.dropId()).isPresent) {
            return
        }
        val display = location.world.spawn(
            location.clone().add(0.0, 0.4, 0.0),
            Item::class.java,
        ) { item ->
            item.itemStack = taggerValue.tag(prepared.itemStack, prepared.drop)
            item.pickupDelay = 0
            item.ticksLived = 1
            taggerValue.tag(item, prepared.drop)
        }
        databaseExecutorValue.execute {
            escrowValue.updateDisplayEntity(
                prepared.drop.eventId(),
                prepared.drop.dropId(),
                Optional.of(display.uniqueId),
                Instant.now(),
            )
        }
    }

    /** Voids prepared rows when the corresponding physical block operation cannot be applied. */
    fun discardPreparedDrops(preparedDrops: List<PreparedDrop>, discardedAt: Instant) {
        requireMainThread()
        Objects.requireNonNull(preparedDrops, "preparedDrops")
        Objects.requireNonNull(discardedAt, "discardedAt")
        for (prepared in preparedDrops) {
            val operationId = deterministic(
                prepared.drop.dropId(),
                "DROP_DISCARD",
                prepared.drop.eventId().toString(),
            )
            escrowValue.voidPreparedDrop(
                prepared.drop.eventId(),
                prepared.drop.dropId(),
                operationId,
                discardedAt,
            )
        }
    }

    /** Returns false while a pickup claim is still crossing the async persistence boundary. */
    fun readyForTerminal(eventId: UUID): Boolean {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        val claims = pendingClaims[eventId]
        return claims == null || claims.isEmpty()
    }

    /** Freezes new pickup claims before the physical and database terminal boundaries begin. */
    fun beginTerminal(eventId: UUID): Boolean {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        if (terminalEvents.contains(eventId)) {
            return true
        }
        if (!readyForTerminal(eventId)) {
            return false
        }
        terminalEvents.add(eventId)
        return true
    }

    /** Cancels a pickup and records only the database claim for a registered participant. */
    fun handlePickup(event: EntityPickupItemEvent) {
        requireMainThread()
        Objects.requireNonNull(event, "event")
        val item = event.item
        val tagged = taggerValue.read(item).orElse(null) ?: return
        event.isCancelled = true
        if (terminalEvents.contains(tagged.eventId)) {
            return
        }
        val player = event.entity as? Player ?: return
        val quantity = item.itemStack.amount
        if (quantity <= 0) {
            return
        }
        val claims = pendingClaims.computeIfAbsent(tagged.eventId) { HashSet() }
        if (!claims.add(item.uniqueId)) {
            return
        }
        val operationId = deterministic(
            tagged.dropId,
            "DROP_CLAIM",
            "${player.uniqueId}|$quantity",
        )
        databaseExecutorValue.submit {
            escrowValue.claim(
                tagged.eventId,
                tagged.dropId,
                player.uniqueId,
                quantity,
                operationId,
                Instant.now(),
            )
        }.whenComplete { result, failure ->
            Bukkit.getScheduler().runTask(pluginValue, Runnable {
                val pending = pendingClaims[tagged.eventId]
                if (pending != null) {
                    pending.remove(item.uniqueId)
                    if (pending.isEmpty()) {
                        pendingClaims.remove(tagged.eventId)
                    }
                }
                if (
                    failure == null && result != null &&
                    (result.outcome == OperationOutcome.APPLIED ||
                        result.outcome == OperationOutcome.ALREADY_APPLIED) &&
                    taggerValue.read(item).map { drop -> drop == tagged }.orElse(false)
                ) {
                    item.remove()
                    databaseExecutorValue.execute {
                        escrowValue.clearDisplayEntity(
                            tagged.eventId,
                            tagged.dropId,
                            Instant.now(),
                        )
                    }
                    if (result.outcome == OperationOutcome.APPLIED) {
                        result.pickupFeedback.ifPresent { feedback ->
                            showResourcePickupFeedback(player, tagged.eventId, feedback)
                        }
                    }
                }
            })
        }
    }

    private fun showResourcePickupFeedback(
        player: Player,
        eventId: UUID,
        feedback: ResourcePickupFeedback,
    ) {
        if (!player.isOnline) {
            return
        }
        val message = feedback.resourceType.displayName() + " +" +
            feedback.claimedQuantity +
            "｜この防衛戦の仮確保: " + feedback.eventPlayerTotal + "P"
        player.playSound(
            player.location,
            Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            SoundCategory.PLAYERS,
            0.7f,
            1.2f,
        )
        actionBars.publishPickup(player.uniqueId, eventId, message)
        actionBars.current(player.uniqueId).ifPresent { notice ->
            player.sendActionBar(Component.text(notice.text()))
        }
    }

    /** Removes all loaded displays for one event after normal or technical termination. */
    fun removeEventDisplays(eventId: UUID) {
        requireMainThread()
        Objects.requireNonNull(eventId, "eventId")
        Bukkit.getScheduler().runTaskLater(
            pluginValue,
            Runnable { actionBars.clearEvent(eventId) },
            ActionBarBroker.PICKUP_TTL_TICKS,
        )
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                val item = entity as? Item ?: continue
                if (taggerValue.read(item).map { tag -> tag.eventId == eventId }.orElse(false)) {
                    item.remove()
                }
            }
        }
        pendingClaims.remove(eventId)
        terminalEvents.remove(eventId)
    }

    /** Removes stale displays on plugin startup before unfinished events enter recovery. */
    fun removeAllTaggedDisplays() {
        requireMainThread()
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity is Item && taggerValue.read(entity).isPresent) {
                    entity.remove()
                }
            }
        }
        pendingClaims.clear()
        terminalEvents.clear()
    }

    /** Removes persisted displays from chunks that load after terminal/recovery cleanup. */
    fun removeStaleDisplays(chunk: Chunk) {
        requireMainThread()
        Objects.requireNonNull(chunk, "chunk")
        for (entity in chunk.entities) {
            val item = entity as? Item ?: continue
            val tagged = taggerValue.read(item).orElse(null) ?: continue
            databaseExecutorValue.submit {
                escrowValue.loadDrops(tagged.eventId).filter {
                    value -> value.drop().dropId().equals(tagged.dropId)
                }.any { value -> value.status() == EscrowDropStatus.HELD }
            }.whenComplete { held, failure ->
                Bukkit.getScheduler().runTask(pluginValue, Runnable {
                    if (failure != null || held != true) {
                        item.remove()
                    }
                })
            }
        }
    }

    fun tagger(): EscrowDropTagger = taggerValue

    fun actionBarBroker(): ActionBarBroker = actionBars

    private fun findDisplay(dropId: UUID): Optional<Item> {
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                val item = entity as? Item ?: continue
                if (taggerValue.read(item).map { tag -> tag.dropId == dropId }.orElse(false)) {
                    return Optional.of(item)
                }
            }
        }
        return Optional.empty()
    }

    @JvmRecord
    data class PreparedDrop(
        val drop: EscrowDrop,
        val itemStack: ItemStack,
    ) {
        init {
            Objects.requireNonNull(drop, "drop")
            Objects.requireNonNull(itemStack, "itemStack")
        }
    }

    private companion object {
        @JvmStatic
        private fun deterministic(base: UUID, namespace: String, value: String): UUID =
            UUID.nameUUIDFromBytes(
                ("$base|$namespace|$value").toByteArray(StandardCharsets.UTF_8),
            )

        @JvmStatic
        private fun requireMainThread() {
            if (!Bukkit.isPrimaryThread()) {
                throw IllegalStateException("Escrow display handling must run on the main thread")
            }
        }
    }
}
