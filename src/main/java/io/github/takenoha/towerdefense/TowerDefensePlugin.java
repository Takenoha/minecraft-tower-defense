package io.github.takenoha.towerdefense;

import io.github.takenoha.towerdefense.config.InvalidPluginSettingsException;
import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.paper.CoreProtectionListener;
import io.github.takenoha.towerdefense.paper.CoreItemListener;
import io.github.takenoha.towerdefense.paper.CoreItemTagger;
import io.github.takenoha.towerdefense.paper.EscrowDropListener;
import io.github.takenoha.towerdefense.paper.EscrowDropTagger;
import io.github.takenoha.towerdefense.paper.EventEnemyListener;
import io.github.takenoha.towerdefense.paper.EventEnemyTagger;
import io.github.takenoha.towerdefense.paper.PaperBlockMutationAdapter;
import io.github.takenoha.towerdefense.paper.PaperEscrowDropManager;
import io.github.takenoha.towerdefense.paper.PaperSettingsLoader;
import io.github.takenoha.towerdefense.paper.ProtectedBlockListener;
import io.github.takenoha.towerdefense.paper.RewardQueueDeliveryListener;
import io.github.takenoha.towerdefense.paper.RewardQueueDeliveryManager;
import io.github.takenoha.towerdefense.paper.RewardQueueReceiptTagger;
import io.github.takenoha.towerdefense.paper.TowerDefenseCommand;
import io.github.takenoha.towerdefense.paper.ThirdPartyRegionProtectionAdapter;
import io.github.takenoha.towerdefense.paper.WorldGuardRegionProtectionAdapter;
import io.github.takenoha.towerdefense.persistence.BlockChangeRepository;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.EscrowRepository;
import io.github.takenoha.towerdefense.persistence.StoredDefenseEvent;
import io.github.takenoha.towerdefense.runtime.AsyncDefensePersistenceSink;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point for the defense-foundation walking skeleton. */
public final class TowerDefensePlugin extends JavaPlugin {
    private DatabaseExecutor databaseExecutor;
    private DefenseSessionManager sessions;
    private PaperBlockMutationAdapter blockMutations;
    private PaperEscrowDropManager escrowDrops;
    private RewardQueueDeliveryManager rewardQueues;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginSettings settings;
        try {
            settings = PaperSettingsLoader.load(getConfig());
        } catch (InvalidPluginSettingsException invalidSettings) {
            getLogger().severe(invalidSettings.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Path databasePath;
        try {
            databasePath = resolveDatabasePath();
        } catch (IllegalArgumentException invalidPath) {
            getLogger().severe(invalidPath.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Database database = new Database(databasePath);
        DefenseRepository repository = new DefenseRepository(
                database,
                settings.rewards().teamQueueRetention());
        ThirdPartyRegionProtectionAdapter regionProtection =
                WorldGuardRegionProtectionAdapter.discover(this);
        blockMutations = new PaperBlockMutationAdapter(new BlockChangeRepository(database));
        databaseExecutor = new DatabaseExecutor("tower-defense-db-");
        EscrowRepository escrowRepository = new EscrowRepository(database);
        escrowDrops = new PaperEscrowDropManager(
                this,
                escrowRepository,
                databaseExecutor,
                new EscrowDropTagger(this));
        rewardQueues = new RewardQueueDeliveryManager(
                this,
                escrowRepository,
                databaseExecutor,
                new RewardQueueReceiptTagger(this));
        escrowDrops.removeAllTaggedDisplays();
        EventEnemyTagger tagger = new EventEnemyTagger(this);
        recoverInterruptedEvents(repository, tagger, blockMutations, escrowDrops);

        CoreRegistry coreRegistry = new CoreRegistry();
        coreRegistry.replaceAll(repository.loadAllCores());
        AsyncDefensePersistenceSink persistence = new AsyncDefensePersistenceSink(
                repository, databaseExecutor);
        sessions = new DefenseSessionManager(
                this,
                settings,
                tagger,
                persistence,
                blockMutations,
                escrowDrops,
                rewardQueues,
                coreRegistry,
                regionProtection);

        CoreItemListener coreItems = new CoreItemListener(
                this,
                settings,
                repository,
                databaseExecutor,
                sessions,
                coreRegistry,
                regionProtection,
                new CoreItemTagger(this));
        coreItems.registerRecipe();
        coreItems.recoverPreparedPlacements();
        getServer().getPluginManager().registerEvents(coreItems, this);

        getServer().getPluginManager().registerEvents(
                new CoreProtectionListener(coreRegistry), this);
        getServer().getPluginManager().registerEvents(
                new ProtectedBlockListener(coreRegistry, sessions), this);
        getServer().getPluginManager().registerEvents(
                new EventEnemyListener(
                        tagger,
                        sessions,
                        sessions,
                        sessions.terrainAction()),
                this);
        getServer().getPluginManager().registerEvents(new EscrowDropListener(escrowDrops), this);
        getServer().getPluginManager().registerEvents(
                new RewardQueueDeliveryListener(rewardQueues), this);

        TowerDefenseCommand commandHandler = new TowerDefenseCommand(
                this,
                settings,
                repository,
                databaseExecutor,
                sessions,
                coreRegistry,
                regionProtection);
        PluginCommand command = Objects.requireNonNull(
                getCommand("td"), "the td command is missing from plugin.yml");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        sessions.startTicking();

        getLogger().info(
                "Minecraft Tower Defense foundation enabled for Paper 26.2 build 87; "
                        + "terrain mutation gate=" + sessions.terrainMutationGate().status()
                        + "; persisted reward queues are retried.");
    }

    @Override
    public void onDisable() {
        if (rewardQueues != null) {
            rewardQueues.close();
            rewardQueues = null;
        }
        if (sessions != null) {
            if (sessions.hasActiveSession()) {
                if (blockMutations != null) {
                    try {
                        sessions.status().ifPresent(status -> {
                            if (escrowDrops != null) {
                                escrowDrops.removeEventDisplays(status.eventId());
                            }
                            blockMutations.recoverEvent(status.eventId(), Instant.now());
                        });
                    } catch (RuntimeException recoveryFailure) {
                        getLogger().log(
                                Level.SEVERE,
                                "Could not recover Paper block mutations during shutdown; "
                                        + "the event lock will remain for operator recovery",
                                recoveryFailure);
                    }
                }
                sessions.recoverActiveSession();
            }
            sessions.close();
            sessions = null;
        }
        if (escrowDrops != null) {
            escrowDrops.removeAllTaggedDisplays();
            escrowDrops = null;
        }
        if (databaseExecutor != null) {
            databaseExecutor.close();
            databaseExecutor = null;
        }
        blockMutations = null;
    }

    private Path resolveDatabasePath() {
        String configuredName = getConfig().getString("database.file", "tower-defense.db");
        if (configuredName == null || configuredName.isBlank()) {
            throw new IllegalArgumentException("database.file must not be blank");
        }
        Path dataFolder = getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = dataFolder.resolve(configuredName).normalize();
        if (!resolved.startsWith(dataFolder) || resolved.equals(dataFolder)) {
            throw new IllegalArgumentException(
                    "database.file must resolve to a file inside the plugin data folder");
        }
        return resolved;
    }

    private void recoverInterruptedEvents(
            DefenseRepository repository,
            EventEnemyTagger tagger,
            PaperBlockMutationAdapter blockMutations,
            PaperEscrowDropManager escrowDrops) {
        List<StoredDefenseEvent> unfinished = repository.loadUnfinishedEvents();
        int removedEntities = 0;
        for (World world : getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (tagger.read(entity).isPresent()) {
                    entity.remove();
                    removedEntities++;
                }
            }
        }
        for (StoredDefenseEvent event : unfinished) {
            escrowDrops.removeEventDisplays(event.session().eventId());
            blockMutations.recoverEvent(event.session().eventId(), Instant.now());
            repository.recoverUnfinishedEvent(
                    event.session().eventId(),
                    UUID.randomUUID(),
                    Instant.now());
        }
        if (!unfinished.isEmpty() || removedEntities > 0) {
            getLogger().warning(
                    "Recovered " + unfinished.size() + " interrupted defense event(s) and removed "
                            + removedEntities + " loaded event enemy entity/entities.");
        }
    }
}
