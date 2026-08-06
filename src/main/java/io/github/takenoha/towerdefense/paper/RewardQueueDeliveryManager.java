package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.EscrowRepository;
import io.github.takenoha.towerdefense.persistence.OperationOutcome;
import io.github.takenoha.towerdefense.persistence.RewardQueueEntry;
import io.github.takenoha.towerdefense.persistence.RewardDeliveryOutcome;
import io.github.takenoha.towerdefense.persistence.RewardQueueStatus;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/**
 * Main-thread inventory bridge for database-owned terminal rewards.
 *
 * <p>The database is queried and updated asynchronously. Paper inventory mutation is kept on the
 * main thread. Each accepted stack carries a queue receipt until the database transition commits;
 * a retry counts that receipt before adding more, which closes the server-stop window.</p>
 */
public final class RewardQueueDeliveryManager implements AutoCloseable {
    private final Plugin plugin;
    private final EscrowRepository escrow;
    private final DatabaseExecutor databaseExecutor;
    private final RewardQueueReceiptTagger tagger;
    private final ResearchCrystalTagger researchCrystals;
    private final Map<UUID, DeliveryRun> activeRuns = new HashMap<>();
    private boolean closed;

    public RewardQueueDeliveryManager(
            Plugin plugin,
            EscrowRepository escrow,
            DatabaseExecutor databaseExecutor,
            RewardQueueReceiptTagger tagger) {
        this(plugin, escrow, databaseExecutor, tagger, new ResearchCrystalTagger(plugin));
    }

    public RewardQueueDeliveryManager(
            Plugin plugin,
            EscrowRepository escrow,
            DatabaseExecutor databaseExecutor,
            RewardQueueReceiptTagger tagger,
            ResearchCrystalTagger researchCrystals) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.escrow = Objects.requireNonNull(escrow, "escrow");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
        this.researchCrystals = Objects.requireNonNull(researchCrystals, "researchCrystals");
    }

    /** Retries personal and team queue rows when a player finishes joining. */
    public void onPlayerJoin(Player player) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        request(player);
    }

    /** Releases the in-memory guard so a quick rejoin can start a fresh database read. */
    public void onPlayerQuit(Player player) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        activeRuns.remove(player.getUniqueId());
    }

    /** Tries to deliver newly issued rows to players who are already online. */
    public void onEventSettled(UUID eventId) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        if (closed) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            request(player);
        }
    }

    public RewardQueueReceiptTagger tagger() {
        return tagger;
    }

    @Override
    public void close() {
        requireMainThread();
        closed = true;
        activeRuns.clear();
    }

    private void request(Player player) {
        if (closed || activeRuns.containsKey(player.getUniqueId())) {
            return;
        }
        DeliveryRun run = new DeliveryRun(player.getUniqueId());
        activeRuns.put(run.playerId(), run);
        Set<UUID> receiptQueueIds = receiptQueueIds(player);
        databaseExecutor.submit(() -> loadForPlayer(run.playerId(), receiptQueueIds))
                .whenComplete((loaded, failure) -> runOnMainThread(() -> {
                    if (!isCurrent(run)) {
                        return;
                    }
                    if (failure != null) {
                        logFailure("Could not load pending rewards for " + run.playerId(), failure);
                        finish(run);
                        return;
                    }
                    for (UUID queueId : loaded.cleanupQueueIds()) {
                        stripReceipts(player, queueId);
                    }
                    deliverNext(run, loaded.pendingEntries(), 0);
                }));
    }

    private RewardLoadResult loadForPlayer(UUID playerId, Set<UUID> receiptQueueIds) {
        Set<UUID> cleanupQueueIds = new LinkedHashSet<>();
        for (UUID queueId : receiptQueueIds) {
            RewardQueueStatus status = escrow.findRewardQueue(queueId)
                    .map(RewardQueueEntry::status)
                    .orElse(RewardQueueStatus.VOIDED);
            if (status != RewardQueueStatus.PENDING) {
                cleanupQueueIds.add(queueId);
            }
        }
        return new RewardLoadResult(
                List.copyOf(cleanupQueueIds),
                escrow.loadPendingRewardQueueForPlayer(playerId));
    }

    private void deliverNext(
            DeliveryRun run,
            List<RewardQueueEntry> entries,
            int index) {
        if (!isCurrent(run)) {
            return;
        }
        Player player = Bukkit.getPlayer(run.playerId());
        if (player == null || !player.isOnline() || index >= entries.size()) {
            finish(run);
            return;
        }
        RewardQueueEntry entry = entries.get(index);
        if (entry.status() != RewardQueueStatus.PENDING) {
            deliverNext(run, entries, index + 1);
            return;
        }
        deliverOne(player, entry, () -> deliverNext(run, entries, index + 1), () -> finish(run));
    }

    private void deliverOne(
            Player player,
            RewardQueueEntry entry,
            Runnable continueDelivery,
            Runnable stopDelivery) {
        UUID operationId = deterministicDeliveryOperation(entry.queueId(), player.getUniqueId());
        databaseExecutor.submit(() -> escrow.prepareRewardDelivery(
                        entry.queueId(), player.getUniqueId(), operationId, Instant.now()))
                .whenComplete((outcome, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        logFailure("Could not reserve reward queue " + entry.queueId(), failure);
                        stopDelivery.run();
                        return;
                    }
                    if (outcome == RewardDeliveryOutcome.ACQUIRED
                            || outcome == RewardDeliveryOutcome.ALREADY_ACQUIRED) {
                        deliverReserved(
                                player, entry, continueDelivery, stopDelivery);
                        return;
                    }
                    if (outcome == RewardDeliveryOutcome.ALREADY_DELIVERED
                            || outcome == RewardDeliveryOutcome.VOIDED) {
                        stripReceipts(player, entry.queueId());
                    }
                    if (outcome == RewardDeliveryOutcome.HELD_BY_OTHER) {
                        plugin.getLogger().fine(
                                "Reward queue " + entry.queueId()
                                        + " is reserved by another eligible team member");
                    }
                    stopDelivery.run();
                }));
    }

    private void deliverReserved(
            Player player,
            RewardQueueEntry entry,
            Runnable continueDelivery,
            Runnable stopDelivery) {
        int alreadyAccepted = receiptQuantity(player, entry.queueId());
        int remaining = entry.quantity() - alreadyAccepted;
        if (remaining <= 0) {
            markDelivered(player, entry, continueDelivery, stopDelivery);
            return;
        }

        ItemStack payload;
        try {
            payload = decodePayload(entry);
        } catch (RuntimeException invalidPayload) {
            logFailure("Reward queue " + entry.queueId() + " has an invalid item payload", invalidPayload);
            stopDelivery.run();
            return;
        }
        UUID deliveryOperation = deterministicDeliveryOperation(entry.queueId(), player.getUniqueId());
        List<ItemStack> stacks;
        try {
            RewardQueueReceipt receipt = new RewardQueueReceipt(entry.queueId(), deliveryOperation);
            stacks = "research_crystal".equals(entry.itemId())
                    ? decodeResearchCrystalStacks(entry, remaining, alreadyAccepted, receipt)
                    : splitAndTag(payload, remaining, receipt);
        } catch (RuntimeException invalidItem) {
            logFailure("Reward queue " + entry.queueId() + " cannot create an inventory item", invalidItem);
            stopDelivery.run();
            return;
        }
        Map<Integer, ItemStack> leftovers;
        try {
            leftovers = player.getInventory().addItem(stacks.toArray(ItemStack[]::new));
        } catch (RuntimeException inventoryFailure) {
            logFailure("Could not add reward queue " + entry.queueId() + " to inventory", inventoryFailure);
            stopDelivery.run();
            return;
        }
        if (!leftovers.isEmpty()) {
            plugin.getLogger().fine(
                    "Reward queue " + entry.queueId()
                            + " remains pending because the inventory is full for "
                            + player.getUniqueId());
            stopDelivery.run();
            return;
        }
        markDelivered(player, entry, continueDelivery, stopDelivery);
    }

    private ItemStack decodePayload(RewardQueueEntry entry) {
        if (!"research_crystal".equals(entry.itemId())) {
            return PaperItemStackCodec.decode(entry.itemPayload());
        }
        String[] fields = entry.itemPayload().split(":", -1);
        if (fields.length != 5
                || !"research_crystal".equals(fields[0])
                || (!"v1".equals(fields[1]) && !"v2".equals(fields[1]))) {
            throw new IllegalArgumentException("The research crystal payload is invalid");
        }
        try {
            UUID batchId = UUID.fromString(fields[2]);
            UUID teamId = UUID.fromString(fields[3]);
            int issuedQuantity = Integer.parseInt(fields[4]);
            if (issuedQuantity != entry.quantity()) {
                throw new IllegalArgumentException(
                        "The research crystal payload quantity does not match the queue");
            }
            return researchCrystals.create(batchId, teamId, issuedQuantity);
        } catch (IllegalArgumentException invalidPayload) {
            throw new IllegalArgumentException("The research crystal payload is invalid", invalidPayload);
        }
    }

    private List<ItemStack> decodeResearchCrystalStacks(
            RewardQueueEntry entry,
            int quantity,
            int offset,
            RewardQueueReceipt receipt) {
        String[] fields = entry.itemPayload().split(":", -1);
        if (fields.length != 5
                || !"research_crystal".equals(fields[0])
                || !"v2".equals(fields[1])) {
            return splitAndTag(decodePayload(entry), quantity, receipt);
        }
        UUID batchId;
        UUID teamId;
        int issuedQuantity;
        try {
            batchId = UUID.fromString(fields[2]);
            teamId = UUID.fromString(fields[3]);
            issuedQuantity = Integer.parseInt(fields[4]);
        } catch (IllegalArgumentException invalidPayload) {
            throw new IllegalArgumentException("The research crystal payload is invalid", invalidPayload);
        }
        if (issuedQuantity != entry.quantity()
                || offset < 0
                || quantity < 0
                || (long) offset + quantity > issuedQuantity) {
            throw new IllegalArgumentException("The research crystal payload quantity is invalid");
        }
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = quantity;
        int segmentOffset = offset;
        while (remaining > 0) {
            int segmentQuantity = Math.min(ResearchCrystalTagger.STACK_LIMIT, remaining);
            ItemStack stack = researchCrystals.create(
                    batchId,
                    teamId,
                    issuedQuantity,
                    segmentOffset,
                    segmentQuantity);
            stacks.add(tagger.tag(stack, receipt));
            segmentOffset += segmentQuantity;
            remaining -= segmentQuantity;
        }
        return List.copyOf(stacks);
    }

    private void markDelivered(
            Player player,
            RewardQueueEntry entry,
            Runnable continueDelivery,
            Runnable stopDelivery) {
        UUID operationId = deterministicDeliveryOperation(entry.queueId(), player.getUniqueId());
        databaseExecutor.submit(() -> escrow.markRewardDelivered(
                        entry.queueId(), player.getUniqueId(), operationId, Instant.now()))
                .whenComplete((outcome, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        logFailure("Could not commit reward queue " + entry.queueId(), failure);
                        stopDelivery.run();
                        return;
                    }
                    if (outcome != OperationOutcome.APPLIED
                            && outcome != OperationOutcome.ALREADY_APPLIED) {
                        logFailure(
                                "Reward queue " + entry.queueId()
                                        + " was not delivered: " + outcome,
                                null);
                        stopDelivery.run();
                        return;
                    }
                    stripReceipts(player, entry.queueId());
                    continueDelivery.run();
                }));
    }

    private List<ItemStack> splitAndTag(
            ItemStack payload,
            int quantity,
            RewardQueueReceipt receipt) {
        int stackLimit = payload.getMaxStackSize();
        if (stackLimit <= 0) {
            throw new IllegalArgumentException("The reward item has no positive stack limit");
        }
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = quantity;
        while (remaining > 0) {
            int amount = Math.min(stackLimit, remaining);
            ItemStack stack = payload.clone();
            stack.setAmount(amount);
            stacks.add(tagger.tag(stack, receipt));
            remaining -= amount;
        }
        return List.copyOf(stacks);
    }

    private int receiptQuantity(Player player, UUID queueId) {
        long quantity = 0L;
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getContents()) {
            if (item == null) {
                continue;
            }
            if (tagger.read(item).map(RewardQueueReceipt::queueId)
                    .map(queueId::equals)
                    .orElse(false)) {
                quantity += item.getAmount();
            }
        }
        return quantity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) quantity;
    }

    private Set<UUID> receiptQueueIds(Player player) {
        Set<UUID> queueIds = new LinkedHashSet<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null) {
                tagger.read(item).map(RewardQueueReceipt::queueId).ifPresent(queueIds::add);
            }
        }
        return Set.copyOf(queueIds);
    }

    private void stripReceipts(Player player, UUID queueId) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null) {
                continue;
            }
            if (tagger.read(item).map(RewardQueueReceipt::queueId)
                    .map(queueId::equals)
                    .orElse(false)) {
                inventory.setItem(slot, tagger.strip(item));
            }
        }
    }

    private boolean isCurrent(DeliveryRun run) {
        return !closed && activeRuns.get(run.playerId()) == run;
    }

    private void finish(DeliveryRun run) {
        if (activeRuns.get(run.playerId()) == run) {
            activeRuns.remove(run.playerId());
        }
    }

    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void logFailure(String message, Throwable failure) {
        if (failure == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, rootCause(failure));
        }
    }

    private static UUID deterministicDeliveryOperation(UUID queueId, UUID playerId) {
        return UUID.nameUUIDFromBytes((queueId + "|" + playerId + "|DELIVERY")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Reward queue delivery must run on the Paper main thread");
        }
    }

    private record DeliveryRun(UUID playerId) {
    }

    private record RewardLoadResult(
            List<UUID> cleanupQueueIds,
            List<RewardQueueEntry> pendingEntries) {
    }
}
