package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.persistence.DropSourceKind;
import io.github.takenoha.towerdefense.persistence.EscrowDrop;
import io.github.takenoha.towerdefense.persistence.EscrowDropStatus;
import io.github.takenoha.towerdefense.persistence.EscrowRepository;
import io.github.takenoha.towerdefense.persistence.PersistenceConflictException;
import io.github.takenoha.towerdefense.persistence.ResourcePickupFeedback;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.persistence.StoredEscrowDrop;
import io.github.takenoha.towerdefense.runtime.ActionBarBroker;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Main-thread bridge between Paper item displays and the database-owned escrow. */
public final class PaperEscrowDropManager {
    private final Plugin plugin;
    private final EscrowRepository escrow;
    private final DatabaseExecutor databaseExecutor;
    private final EscrowDropTagger tagger;
    private final ResourceRepository resources;
    private final ActionBarBroker actionBars = new ActionBarBroker();
    private final Map<UUID, Set<UUID>> pendingClaims = new HashMap<>();
    private final Set<UUID> terminalEvents = new HashSet<>();

    public PaperEscrowDropManager(
            Plugin plugin,
            EscrowRepository escrow,
            DatabaseExecutor databaseExecutor,
            EscrowDropTagger tagger) {
        this(plugin, escrow, databaseExecutor, tagger, null);
    }

    public PaperEscrowDropManager(
            Plugin plugin,
            EscrowRepository escrow,
            DatabaseExecutor databaseExecutor,
            EscrowDropTagger tagger,
            ResourceRepository resources) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.escrow = Objects.requireNonNull(escrow, "escrow");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
        this.resources = resources;
    }

    /** Captures ordinary block drops before the corresponding block WAL apply. */
    public List<PreparedDrop> prepareBlockDrops(
            UUID eventId,
            UUID sourceId,
            Block block,
            Instant occurredAt) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(occurredAt, "occurredAt");

        List<StoredEscrowDrop> existing = escrow.loadDrops(eventId).stream()
                .filter(value -> value.drop().sourceKind() == DropSourceKind.BLOCK)
                .filter(value -> value.drop().sourceId().equals(sourceId))
                .toList();
        if (!existing.isEmpty()) {
            if (existing.stream().anyMatch(value -> value.status() != EscrowDropStatus.HELD)) {
                throw new PersistenceConflictException(
                        "A block action already has a terminal escrow drop");
            }
            return existing.stream()
                    .map(value -> new PreparedDrop(
                            value.drop(),
                            PaperItemStackCodec.decode(value.drop().itemPayload())))
                    .toList();
        }

        List<PreparedDrop> prepared = new ArrayList<>();
        try {
            int index = 0;
            for (ItemStack itemStack : block.getDrops()) {
                if (itemStack == null || itemStack.getType().isAir() || itemStack.getAmount() <= 0) {
                    index++;
                    continue;
                }
                ItemStack storedStack = itemStack.clone();
                UUID dropId = deterministic(sourceId, "BLOCK_DROP", Integer.toString(index));
                UUID operationId = deterministic(sourceId, "DROP_CREATE", Integer.toString(index));
                EscrowDrop drop = new EscrowDrop(
                        eventId,
                        dropId,
                        DropSourceKind.BLOCK,
                        sourceId,
                        storedStack.getType().getKey().toString(),
                        PaperItemStackCodec.encode(storedStack),
                        storedStack.getAmount(),
                        Optional.empty());
                escrow.prepare(drop, operationId, occurredAt);
                prepared.add(new PreparedDrop(drop, storedStack));
                index++;
            }
        } catch (RuntimeException prepareFailure) {
            if (!prepared.isEmpty()) {
                try {
                    discardPreparedDrops(prepared, occurredAt);
                } catch (RuntimeException discardFailure) {
                    prepareFailure.addSuppressed(discardFailure);
                }
            }
            throw prepareFailure;
        }
        return List.copyOf(prepared);
    }

    /** Spawns tagged, non-usable physical displays after the block mutation is acknowledged. */
    public void spawnPreparedDrops(Block sourceBlock, List<PreparedDrop> preparedDrops) {
        requireMainThread();
        Objects.requireNonNull(sourceBlock, "sourceBlock");
        Objects.requireNonNull(preparedDrops, "preparedDrops");
        for (PreparedDrop prepared : preparedDrops) {
            if (findDisplay(prepared.drop().dropId()).isPresent()) {
                continue;
            }
            Location location = sourceBlock.getLocation().add(0.5d, 0.5d, 0.5d);
            Item display = sourceBlock.getWorld().spawn(location, Item.class, item -> {
                item.setItemStack(tagger.tag(prepared.itemStack(), prepared.drop()));
                item.setPickupDelay(0);
                item.setTicksLived(1);
                tagger.tag(item, prepared.drop());
            });
            databaseExecutor.execute(() -> escrow.updateDisplayEntity(
                    prepared.drop().eventId(),
                    prepared.drop().dropId(),
                    Optional.of(display.getUniqueId()),
                    Instant.now()));
        }
    }

    /** Queues one event-enemy material without exposing a usable ItemStack during the event. */
    public void issueEnemyDrop(
            UUID eventId,
            UUID sourceId,
            Location location,
            String itemId,
            ItemStack itemStack,
            Instant createdAt) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(itemStack, "itemStack");
        Objects.requireNonNull(createdAt, "createdAt");
        if (itemStack.getAmount() <= 0 || itemStack.getType().isAir()) {
            throw new IllegalArgumentException("enemy drop item must be usable");
        }
        UUID dropId = deterministic(sourceId, "ENEMY_DROP", itemId);
        UUID createOperationId = deterministic(sourceId, "ENEMY_DROP_CREATE", itemId);
        EscrowDrop drop = new EscrowDrop(
                eventId,
                dropId,
                DropSourceKind.ENEMY,
                sourceId,
                itemId,
                PaperItemStackCodec.encode(itemStack),
                itemStack.getAmount(),
                Optional.empty());
        databaseExecutor.submit(() -> escrow.prepare(drop, createOperationId, createdAt))
                .whenComplete((outcome, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        plugin.getLogger().log(
                                java.util.logging.Level.WARNING,
                                "Could not prepare event enemy drop " + drop.dropId(),
                                failure);
                        return;
                    }
                    spawnPreparedDrop(location, new PreparedDrop(drop, itemStack));
                }));
    }

    private void spawnPreparedDrop(Location location, PreparedDrop prepared) {
        if (findDisplay(prepared.drop().dropId()).isPresent()) {
            return;
        }
        Item display = location.getWorld().spawn(location.clone().add(0.0d, 0.4d, 0.0d), Item.class,
                item -> {
                    item.setItemStack(tagger.tag(prepared.itemStack(), prepared.drop()));
                    item.setPickupDelay(0);
                    item.setTicksLived(1);
                    tagger.tag(item, prepared.drop());
                });
        databaseExecutor.execute(() -> escrow.updateDisplayEntity(
                prepared.drop().eventId(),
                prepared.drop().dropId(),
                Optional.of(display.getUniqueId()),
                Instant.now()));
    }

    /** Voids prepared rows when the corresponding physical block operation cannot be applied. */
    public void discardPreparedDrops(List<PreparedDrop> preparedDrops, Instant discardedAt) {
        requireMainThread();
        Objects.requireNonNull(preparedDrops, "preparedDrops");
        Objects.requireNonNull(discardedAt, "discardedAt");
        for (PreparedDrop prepared : preparedDrops) {
            UUID operationId = deterministic(
                    prepared.drop().dropId(), "DROP_DISCARD", prepared.drop().eventId().toString());
            escrow.voidPreparedDrop(
                    prepared.drop().eventId(),
                    prepared.drop().dropId(),
                    operationId,
                    discardedAt);
        }
    }

    /** Returns false while a pickup claim is still crossing the async persistence boundary. */
    public boolean readyForTerminal(UUID eventId) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        Set<UUID> claims = pendingClaims.get(eventId);
        return claims == null || claims.isEmpty();
    }

    /** Freezes new pickup claims before the physical and database terminal boundaries begin. */
    public boolean beginTerminal(UUID eventId) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        if (terminalEvents.contains(eventId)) {
            return true;
        }
        if (!readyForTerminal(eventId)) {
            return false;
        }
        terminalEvents.add(eventId);
        return true;
    }

    /** Cancels a pickup and records only the database claim for a registered participant. */
    public void handlePickup(EntityPickupItemEvent event) {
        requireMainThread();
        Objects.requireNonNull(event, "event");
        Item item = event.getItem();
        Optional<TaggedEscrowDrop> tagged = tagger.read(item);
        if (tagged.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (terminalEvents.contains(tagged.orElseThrow().eventId())) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        TaggedEscrowDrop drop = tagged.orElseThrow();
        int quantity = item.getItemStack().getAmount();
        if (quantity <= 0) {
            return;
        }
        Set<UUID> claims = pendingClaims.computeIfAbsent(drop.eventId(), ignored -> new HashSet<>());
        if (!claims.add(item.getUniqueId())) {
            return;
        }
        UUID operationId = deterministic(
                drop.dropId(), "DROP_CLAIM", player.getUniqueId() + "|" + quantity);
        databaseExecutor.submit(() -> escrow.claim(
                        drop.eventId(),
                        drop.dropId(),
                        player.getUniqueId(),
                        quantity,
                        operationId,
                        Instant.now()))
                .whenComplete((result, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Set<UUID> pending = pendingClaims.get(drop.eventId());
                    if (pending != null) {
                        pending.remove(item.getUniqueId());
                        if (pending.isEmpty()) {
                            pendingClaims.remove(drop.eventId());
                        }
                    }
                    if (failure == null
                            && result != null
                            && (result.outcome() == io.github.takenoha.towerdefense.persistence
                                    .OperationOutcome.APPLIED
                                    || result.outcome() == io.github.takenoha.towerdefense.persistence
                                            .OperationOutcome.ALREADY_APPLIED)
                            && tagger.read(item)
                            .map(drop::equals)
                            .orElse(false)) {
                        item.remove();
                        databaseExecutor.execute(() -> escrow.clearDisplayEntity(
                                drop.eventId(), drop.dropId(), Instant.now()));
                        if (result.outcome() == io.github.takenoha.towerdefense.persistence
                                .OperationOutcome.APPLIED) {
                            result.pickupFeedback().ifPresent(feedback ->
                                    showResourcePickupFeedback(player, drop.eventId(), feedback));
                        }
                    }
                }));
    }

    private void showResourcePickupFeedback(
            Player player,
            UUID eventId,
            ResourcePickupFeedback feedback) {
        if (!player.isOnline()) {
            return;
        }
        String message = feedback.resourceType().displayName() + " +"
                + feedback.claimedQuantity()
                + "｜この防衛戦の仮確保: "
                + feedback.eventPlayerTotal() + "P";
        player.playSound(
                player.getLocation(),
                Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS,
                0.7f,
                1.2f);
        actionBars.publishPickup(player.getUniqueId(), eventId, message);
        // The claim callback is already on the main thread. Render the broker's winner now so a
        // terminal callback cannot race the next DefenseSessionManager tick and lose the first
        // pickup frame while the ending state is being finalized.
        actionBars.current(player.getUniqueId()).ifPresent(notice ->
                player.sendActionBar(Component.text(notice.text())));
    }

    /** Removes all loaded displays for one event after normal or technical termination. */
    public void removeEventDisplays(UUID eventId) {
        requireMainThread();
        Objects.requireNonNull(eventId, "eventId");
        // Physical drops are terminal immediately, but a claim that completed during ending
        // still owns a 40-tick pickup notice.  Defer only the broker cleanup so the notice can be
        // rendered by DefenseSessionManager's ending path.
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> actionBars.clearEvent(eventId),
                ActionBarBroker.PICKUP_TTL_TICKS);
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item item)) {
                    continue;
                }
                if (tagger.read(item).map(tag -> tag.eventId().equals(eventId)).orElse(false)) {
                    item.remove();
                }
            }
        }
        pendingClaims.remove(eventId);
        terminalEvents.remove(eventId);
    }

    /** Removes stale displays on plugin startup before unfinished events enter recovery. */
    public void removeAllTaggedDisplays() {
        requireMainThread();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item && tagger.read(item).isPresent()) {
                    item.remove();
                }
            }
        }
        pendingClaims.clear();
        terminalEvents.clear();
    }

    /** Removes persisted displays from chunks that load after terminal/recovery cleanup. */
    public void removeStaleDisplays(Chunk chunk) {
        requireMainThread();
        Objects.requireNonNull(chunk, "chunk");
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Item item)) {
                continue;
            }
            Optional<TaggedEscrowDrop> tagged = tagger.read(item);
            if (tagged.isEmpty()) {
                continue;
            }
            TaggedEscrowDrop identity = tagged.orElseThrow();
            databaseExecutor.submit(() -> escrow.loadDrops(identity.eventId()).stream()
                    .filter(value -> value.drop().dropId().equals(identity.dropId()))
                    .anyMatch(value -> value.status() == EscrowDropStatus.HELD))
                    .whenComplete((held, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (failure != null || !Boolean.TRUE.equals(held)) {
                            item.remove();
                        }
                    }));
        }
    }

    public EscrowDropTagger tagger() {
        return tagger;
    }

    public ActionBarBroker actionBarBroker() {
        return actionBars;
    }

    private Optional<Item> findDisplay(UUID dropId) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item
                        && tagger.read(item).map(tag -> tag.dropId().equals(dropId)).orElse(false)) {
                    return Optional.of(item);
                }
            }
        }
        return Optional.empty();
    }

    private static UUID deterministic(UUID base, String namespace, String value) {
        return UUID.nameUUIDFromBytes((base + "|" + namespace + "|" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Escrow display handling must run on the main thread");
        }
    }

    public record PreparedDrop(EscrowDrop drop, ItemStack itemStack) {
        public PreparedDrop {
            Objects.requireNonNull(drop, "drop");
            Objects.requireNonNull(itemStack, "itemStack");
        }
    }

}
