package io.github.takenoha.towerdefense;

import io.github.takenoha.towerdefense.config.InvalidPluginSettingsException;
import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.paper.CoreProtectionListener;
import io.github.takenoha.towerdefense.paper.EventEnemyListener;
import io.github.takenoha.towerdefense.paper.EventEnemyTagger;
import io.github.takenoha.towerdefense.paper.PaperSettingsLoader;
import io.github.takenoha.towerdefense.paper.TowerDefenseCommand;
import io.github.takenoha.towerdefense.persistence.Database;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
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
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point for the defense-foundation walking skeleton. */
public final class TowerDefensePlugin extends JavaPlugin {
    private DatabaseExecutor databaseExecutor;
    private DefenseSessionManager sessions;

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
        DefenseRepository repository = new DefenseRepository(database);
        EventEnemyTagger tagger = new EventEnemyTagger(this);
        recoverInterruptedEvents(repository, tagger);

        CoreRegistry coreRegistry = new CoreRegistry();
        coreRegistry.replaceAll(repository.loadAllCores());
        databaseExecutor = new DatabaseExecutor("tower-defense-db-");
        AsyncDefensePersistenceSink persistence = new AsyncDefensePersistenceSink(
                repository, databaseExecutor);
        sessions = new DefenseSessionManager(this, settings, tagger, persistence);

        getServer().getPluginManager().registerEvents(
                new CoreProtectionListener(coreRegistry), this);
        getServer().getPluginManager().registerEvents(
                new EventEnemyListener(tagger, sessions, sessions), this);

        TowerDefenseCommand commandHandler = new TowerDefenseCommand(
                this,
                settings,
                repository,
                databaseExecutor,
                sessions,
                coreRegistry);
        PluginCommand command = Objects.requireNonNull(
                getCommand("td"), "the td command is missing from plugin.yml");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        sessions.startTicking();

        getLogger().info(
                "Minecraft Tower Defense foundation enabled for Paper 26.2 build 87; "
                        + "terrain mutation and rewards remain disabled.");
    }

    @Override
    public void onDisable() {
        if (sessions != null) {
            if (sessions.hasActiveSession()) {
                sessions.recoverActiveSession();
            }
            sessions.close();
            sessions = null;
        }
        if (databaseExecutor != null) {
            databaseExecutor.close();
            databaseExecutor = null;
        }
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
            EventEnemyTagger tagger) {
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
