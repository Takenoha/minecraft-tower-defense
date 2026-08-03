package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.DefensePhase;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import io.github.takenoha.towerdefense.domain.DefenseSessionSnapshot;
import io.github.takenoha.towerdefense.domain.EnemyObstacleFacts;
import io.github.takenoha.towerdefense.domain.EnemyPathAction;
import io.github.takenoha.towerdefense.domain.EnemyRole;
import io.github.takenoha.towerdefense.domain.EnemyRoleSchedule;
import io.github.takenoha.towerdefense.paper.EventEnemyTagger;
import io.github.takenoha.towerdefense.paper.PaperBlockMutationAdapter;
import io.github.takenoha.towerdefense.paper.PaperCombatAreaSafetyValidator;
import io.github.takenoha.towerdefense.paper.PaperEscrowDropManager;
import io.github.takenoha.towerdefense.paper.PaperEnemyPathIntegrationBoundary;
import io.github.takenoha.towerdefense.paper.PaperEnemyTerrainAction;
import io.github.takenoha.towerdefense.paper.RewardQueueDeliveryManager;
import io.github.takenoha.towerdefense.paper.ThirdPartyRegionProtectionAdapter;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.EnemyLedgerEntry;
import io.github.takenoha.towerdefense.persistence.EnemyStatus;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main-thread walking-skeleton runtime for a single globally locked defense event.
 * Terrain mutation remains disabled by policy; terminal reward rows are delivered through the
 * database-owned queue bridge after the terminal transaction commits.
 */
public final class DefenseSessionManager
        implements EnemyLifecycleSink, EnemyAccessPolicy, AutoCloseable {
    private static final long TICKS_PER_SECOND = 20L;
    private static final long PATH_REFRESH_TICKS = 20L;
    private static final long PATH_STALL_TIMEOUT_TICKS = 45L * TICKS_PER_SECOND;
    private static final double MIN_PATH_PROGRESS = 0.5d;
    private static final int SPAWN_ATTEMPTS_PER_ENEMY = 16;
    private static final long SPAWN_FAILURE_TIMEOUT_TICKS = 10L * TICKS_PER_SECOND;
    private static final long FINISH_RETRY_BASE_TICKS = TICKS_PER_SECOND;
    private static final long FINISH_RETRY_MAX_TICKS = 30L * TICKS_PER_SECOND;
    private static final long MAX_FOUNDATION_WAVE_ENEMIES = 10_000L;
    private static final double CORE_REACH_DISTANCE_SQUARED = 2.75d * 2.75d;

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final CombatArea combatArea;
    private final EventEnemyTagger tagger;
    private final DefensePersistenceSink persistence;
    private final PaperBlockMutationAdapter blockMutations;
    private final PaperEscrowDropManager escrowDrops;
    private final RewardQueueDeliveryManager rewardQueues;
    private final CoreRegistry coreRegistry;
    private final ThirdPartyRegionProtectionAdapter regionProtection;
    private final PaperEnemyPathIntegrationBoundary pathIntegration;
    private final PaperEnemyTerrainAction terrainAction;
    private final TerrainMutationActivationGate terrainMutationGate;
    private final EnemyRoleSchedule enemyRoles;

    private BukkitTask tickTask;
    private ActiveDefense active;
    private long currentTick;

    public DefenseSessionManager(
            JavaPlugin plugin,
            PluginSettings settings,
            EventEnemyTagger tagger,
            DefensePersistenceSink persistence,
            PaperBlockMutationAdapter blockMutations,
            PaperEscrowDropManager escrowDrops,
            RewardQueueDeliveryManager rewardQueues) {
        this(
                plugin,
                settings,
                tagger,
                persistence,
                blockMutations,
                escrowDrops,
                rewardQueues,
                new CoreRegistry(),
                ThirdPartyRegionProtectionAdapter.none());
    }

    public DefenseSessionManager(
            JavaPlugin plugin,
            PluginSettings settings,
            EventEnemyTagger tagger,
            DefensePersistenceSink persistence,
            PaperBlockMutationAdapter blockMutations,
            PaperEscrowDropManager escrowDrops,
            RewardQueueDeliveryManager rewardQueues,
            ThirdPartyRegionProtectionAdapter regionProtection) {
        this(
                plugin,
                settings,
                tagger,
                persistence,
                blockMutations,
                escrowDrops,
                rewardQueues,
                new CoreRegistry(),
                regionProtection);
    }

    public DefenseSessionManager(
            JavaPlugin plugin,
            PluginSettings settings,
            EventEnemyTagger tagger,
            DefensePersistenceSink persistence,
            PaperBlockMutationAdapter blockMutations,
            PaperEscrowDropManager escrowDrops,
            RewardQueueDeliveryManager rewardQueues,
            CoreRegistry coreRegistry) {
        this(
                plugin,
                settings,
                tagger,
                persistence,
                blockMutations,
                escrowDrops,
                rewardQueues,
                coreRegistry,
                ThirdPartyRegionProtectionAdapter.none());
    }

    public DefenseSessionManager(
            JavaPlugin plugin,
            PluginSettings settings,
            EventEnemyTagger tagger,
            DefensePersistenceSink persistence,
            PaperBlockMutationAdapter blockMutations,
            PaperEscrowDropManager escrowDrops,
            RewardQueueDeliveryManager rewardQueues,
            CoreRegistry coreRegistry,
            ThirdPartyRegionProtectionAdapter regionProtection) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.blockMutations = Objects.requireNonNull(blockMutations, "blockMutations");
        this.escrowDrops = Objects.requireNonNull(escrowDrops, "escrowDrops");
        this.rewardQueues = Objects.requireNonNull(rewardQueues, "rewardQueues");
        this.coreRegistry = Objects.requireNonNull(coreRegistry, "coreRegistry");
        this.regionProtection = Objects.requireNonNull(regionProtection, "regionProtection");
        pathIntegration = new PaperEnemyPathIntegrationBoundary(coreRegistry, this);
        combatArea = new CombatArea(
                settings.combat().radius(),
                settings.combat().spawnInner(),
                settings.combat().spawnOuter(),
                settings.combat().minimumCoreDistance(),
                settings.combat().coreGap());
        enemyRoles = new EnemyRoleSchedule(
                settings.enemies().destroyerRatio(),
                settings.enemies().builderRatio());
        terrainMutationGate = new TerrainMutationActivationGate(settings.terrainMutation());
        terrainAction = new PaperEnemyTerrainAction(
                new TerrainMutationPolicy(terrainMutationGate.enabled()),
                blockMutations,
                escrowDrops,
                coreRegistry,
                this);
    }

    /** Returns the shared, production-disabled enemy terrain action boundary. */
    public PaperEnemyTerrainAction terrainAction() {
        return terrainAction;
    }

    /** Returns the immutable activation decision used by the shared terrain-action boundary. */
    public TerrainMutationActivationGate terrainMutationGate() {
        return terrainMutationGate;
    }

    public void startTicking() {
        requireMainThread();
        if (tickTask != null) {
            throw new IllegalStateException("the defense tick task is already running");
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Activates a session only after its global database lock has been acquired. */
    public void activate(
            DefenseSession session,
            CoreRecord core,
            Set<UUID> teamMembers) {
        requireMainThread();
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(teamMembers, "teamMembers");
        if (active != null) {
            throw new IllegalStateException("a defense session is already active in memory");
        }
        if (!session.teamId().equals(core.teamId())) {
            throw new IllegalArgumentException("session and core belong to different teams");
        }
        if (teamMembers.isEmpty()) {
            throw new IllegalArgumentException("teamMembers must not be empty");
        }

        World world = Bukkit.getWorld(core.worldId());
        if (world == null) {
            throw new IllegalStateException("core world is not loaded: " + core.worldId());
        }
        List<String> safetyViolations = PaperCombatAreaSafetyValidator.violations(
                world,
                core.blockX() + 0.5d,
                core.blockZ() + 0.5d,
                combatArea,
                settings.protection(),
                regionProtection);
        if (!safetyViolations.isEmpty()) {
            throw new IllegalStateException(
                    "combat area violates a protection boundary: "
                            + String.join("; ", safetyViolations));
        }
        Location coreTarget = new Location(
                world,
                core.blockX() + 0.5d,
                core.blockY() + 1.0d,
                core.blockZ() + 0.5d);
        ActiveDefense next = new ActiveDefense(
                session,
                core,
                Set.copyOf(teamMembers),
                world,
                coreTarget,
                BossBar.bossBar(
                        Component.text("防衛戦: カウントダウン"),
                        1.0f,
                        BossBar.Color.YELLOW,
                        BossBar.Overlay.PROGRESS));
        active = next;
        addChunkTicket(next, core.blockX() >> 4, core.blockZ() >> 4);
        next.phaseDeadlineTick = deadline(settings.combat().countdownSeconds());
        refreshCandidates(next);
        refreshBossBar(next);
        broadcast(next, Component.text("防衛戦のカウントダウンを開始しました。", NamedTextColor.GOLD));
    }

    public Optional<DefenseRuntimeStatus> status() {
        requireMainThread();
        if (active == null) {
            return Optional.empty();
        }
        DefenseSession session = active.session;
        return Optional.of(new DefenseRuntimeStatus(
                session.eventId(),
                session.teamId(),
                session.stageLevel(),
                session.phase(),
                session.currentWave(),
                session.totalWaves(),
                session.pendingEnemies(),
                session.aliveEnemies(),
                session.coreState().currentHitPoints(),
                session.coreState().maximumHitPoints(),
                active.ending,
                active.persistenceFailure,
                active.pathMetrics.snapshot()));
    }

    public boolean hasActiveSession() {
        requireMainThread();
        return active != null;
    }

    @Override
    public boolean mayAffect(TaggedEnemy taggedEnemy, UUID playerId) {
        requireMainThread();
        Objects.requireNonNull(taggedEnemy, "taggedEnemy");
        Objects.requireNonNull(playerId, "playerId");
        ActiveDefense defense = active;
        if (defense == null
                || defense.ending
                || !defense.session.eventId().equals(taggedEnemy.eventId())) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId);
        return player != null
                && defense.teamMembers.contains(playerId)
                && isInside(defense, player)
                && ensureEffectiveParticipant(defense, playerId);
    }

    @Override
    public boolean mayRemain(TaggedEnemy taggedEnemy, UUID entityId) {
        requireMainThread();
        Objects.requireNonNull(taggedEnemy, "taggedEnemy");
        Objects.requireNonNull(entityId, "entityId");
        ActiveDefense defense = active;
        return defense != null
                && !defense.ending
                && defense.session.eventId().equals(taggedEnemy.eventId())
                && entityId.equals(
                        defense.entitiesByLogicalId.get(taggedEnemy.logicalEnemyId()));
    }

    @Override
    public boolean mayModifyCombatArea(UUID playerId, Location location) {
        requireMainThread();
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(location, "location");
        ActiveDefense defense = active;
        if (defense == null || !isCombatAreaProtected(location)) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null
                || !defense.teamMembers.contains(playerId)
                || !isInside(defense, player)) {
            return false;
        }
        if (defense.session.phase() == DefensePhase.COUNTDOWN) {
            return true;
        }
        return ensureEffectiveParticipant(defense, playerId);
    }

    @Override
    public boolean isCombatAreaProtected(Location location) {
        requireMainThread();
        Objects.requireNonNull(location, "location");
        ActiveDefense defense = active;
        return defense != null
                && !defense.ending
                && defense.world.equals(location.getWorld())
                && combatArea.contains(
                        defense.core.blockX() + 0.5d,
                        defense.core.blockZ() + 0.5d,
                        location.getX(),
                        location.getZ());
    }

    /** Administrator stop uses the technical recovery path, not player defeat. */
    public boolean recoverActiveSession() {
        requireMainThread();
        if (active == null) {
            return false;
        }
        if (active.ending) {
            active.finishRetryTick = currentTick;
            submitFinish(active);
            return true;
        }
        active.session.enterRecovery();
        finish(active, "管理者操作により技術的復旧へ移行しました。");
        return true;
    }

    @Override
    public void onDefeated(Entity entity, TaggedEnemy taggedEnemy) {
        requireMainThread();
        if (active == null
                || active.ending
                || !active.session.eventId().equals(taggedEnemy.eventId())) {
            return;
        }
        UUID expectedEntity = active.entitiesByLogicalId.get(taggedEnemy.logicalEnemyId());
        if (!entity.getUniqueId().equals(expectedEntity)) {
            return;
        }
        active.entitiesByLogicalId.remove(taggedEnemy.logicalEnemyId());
        active.enemyProgress.remove(taggedEnemy.logicalEnemyId());
        active.enemyRolesByLogicalId.remove(taggedEnemy.logicalEnemyId());
        observe(
                active,
                persistence.recordEnemyStatus(
                        taggedEnemy.eventId(),
                        taggedEnemy.logicalEnemyId(),
                        entity.getUniqueId(),
                        EnemyStatus.DEAD));
        if (active.session.phase() != DefensePhase.WAVE_ACTIVE) {
            return;
        }
        boolean waveCleared = active.session.recordEnemyDefeated(1L);
        if (active.session.isTerminal()) {
            onWaveCleared(active);
            return;
        }
        persistTransition(active);
        if (waveCleared) {
            onWaveCleared(active);
        }
    }

    private void tick() {
        requireMainThread();
        currentTick++;
        ActiveDefense defense = active;
        if (defense == null) {
            return;
        }
        if (defense.ending) {
            if (!defense.finishInFlight && currentTick >= defense.finishRetryTick) {
                submitFinish(defense);
            }
            return;
        }
        if (defense.persistenceFailure != null) {
            defense.session.enterRecovery();
            finish(defense, "永続化エラーのため技術的復旧へ移行しました。");
            return;
        }

        refreshBossBar(defense);
        switch (defense.session.phase()) {
            case COUNTDOWN -> tickCountdown(defense);
            case PREPARATION, INTERMISSION -> tickPreparation(defense);
            case WAVE_ACTIVE -> tickActiveWave(defense);
            case VICTORY, DEFEAT, ABORTED, RECOVERY -> finish(
                    defense,
                    "終端状態を検出したため防衛戦を清掃します。");
        }
    }

    private void tickCountdown(ActiveDefense defense) {
        refreshCandidates(defense);
        if (currentTick < defense.phaseDeadlineTick) {
            showCountdownActionBar(defense);
            return;
        }

        List<UUID> participants = selectParticipants(defense);
        if (participants.isEmpty()) {
            defense.session.defeatCountdownForNoCandidates();
            finish(defense, "開始時に範囲内のチームメンバーがいないため敗北しました。");
            return;
        }
        defense.session.completeCountdown(participants);
        defense.phaseDeadlineTick = deadline(settings.combat().preparationSeconds());
        persistTransition(defense);
        broadcast(defense, Component.text("参加者を確定しました。第1ウェーブを準備します。", NamedTextColor.GREEN));
    }

    private void tickPreparation(ActiveDefense defense) {
        if (defeatForAbsenceIfExpired(defense)) {
            return;
        }
        if (currentTick < defense.phaseDeadlineTick) {
            showCountdownActionBar(defense);
            return;
        }

        long enemyCount = enemyCountForNextWave(defense.session);
        defense.session.startWave(enemyCount);
        populateLogicalQueue(defense, enemyCount);
        persistTransition(defense);
        broadcast(
                defense,
                Component.text(
                        "ウェーブ " + defense.session.currentWave() + " を開始します。",
                        NamedTextColor.RED));
    }

    private void tickActiveWave(ActiveDefense defense) {
        if (defeatForAbsenceIfExpired(defense)) {
            return;
        }
        reconcilePhysicalEnemies(defense);
        if (defense.ending || defense.session.phase() != DefensePhase.WAVE_ACTIVE) {
            return;
        }
        spawnPendingEnemies(defense);
    }

    private void spawnPendingEnemies(ActiveDefense defense) {
        int availableSlots = settings.enemies().maxAlive() - defense.entitiesByLogicalId.size();
        int spawnBudget = Math.min(settings.enemies().spawnPerTick(), availableSlots);
        boolean spawnedAny = false;
        while (spawnBudget > 0 && !defense.pendingLogicalIds.isEmpty()) {
            UUID logicalEnemyId = defense.pendingLogicalIds.peekFirst();
            EnemyRole role = defense.enemyRolesByLogicalId.getOrDefault(
                    logicalEnemyId,
                    EnemyRole.NORMAL);
            Optional<Location> spawnLocation = findSpawnLocation(defense);
            if (spawnLocation.isEmpty()) {
                if (defense.spawnFailureSinceTick < 0L) {
                    defense.spawnFailureSinceTick = currentTick;
                } else if (currentTick - defense.spawnFailureSinceTick
                        >= SPAWN_FAILURE_TIMEOUT_TICKS) {
                    defense.session.enterRecovery();
                    finish(defense, "安全な敵出現地点を確保できないため技術的復旧へ移行しました。");
                }
                return;
            }
            defense.spawnFailureSinceTick = -1L;
            Zombie zombie;
            try {
                zombie = defense.world.spawn(
                        spawnLocation.orElseThrow(),
                        Zombie.class,
                        CreatureSpawnEvent.SpawnReason.CUSTOM,
                        entity -> configureEnemy(entity, role));
            } catch (IllegalArgumentException spawnFailure) {
                plugin.getLogger().warning("Could not spawn event enemy: " + spawnFailure.getMessage());
                return;
            }

            TaggedEnemy taggedEnemy = new TaggedEnemy(
                    defense.session.eventId(), logicalEnemyId, role);
            tagger.tag(zombie, taggedEnemy);
            defense.pendingLogicalIds.removeFirst();
            defense.entitiesByLogicalId.put(logicalEnemyId, zombie.getUniqueId());
            defense.enemyProgress.put(
                    logicalEnemyId,
                    new EnemyProgress(
                            zombie.getLocation().distanceSquared(defense.coreTarget),
                            currentTick));
            defense.session.spawnPendingEnemies(1L);
            EnemyLedgerEntry entry = new EnemyLedgerEntry(
                    defense.session.eventId(),
                    logicalEnemyId,
                    zombie.getUniqueId(),
                    role.ledgerType(),
                    defense.session.currentWave(),
                    EnemyStatus.SPAWNED,
                    "{}",
                    1,
                    Instant.now());
            observe(defense, persistence.recordEnemySpawned(entry));
            spawnedAny = true;
            spawnBudget--;
        }
        if (spawnedAny) {
            persistTransition(defense);
        }
    }

    private void configureEnemy(Zombie zombie, EnemyRole role) {
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        zombie.setCanPickupItems(false);
        zombie.setCanBreakDoors(false);
        zombie.setShouldBurnInDay(false);
        zombie.setLootTable(null);
        zombie.getPathfinder().setCanOpenDoors(false);
        zombie.getPathfinder().setCanPassDoors(false);
        if (role == EnemyRole.BOSS) {
            AttributeInstance maximumHealth = Objects.requireNonNull(
                    zombie.getAttribute(Attribute.MAX_HEALTH),
                    "zombie max-health attribute");
            double boostedHealth = maximumHealth.getBaseValue()
                    * settings.enemies().bossHealthMultiplier();
            maximumHealth.setBaseValue(boostedHealth);
            zombie.setHealth(boostedHealth);
            zombie.customName(Component.text("防衛戦ボス", NamedTextColor.DARK_RED));
            zombie.setCustomNameVisible(true);
            zombie.setGlowing(true);
        } else if (role == EnemyRole.DESTROYER) {
            zombie.customName(Component.text("防衛戦破壊兵", NamedTextColor.DARK_RED));
            zombie.setCustomNameVisible(true);
        } else if (role == EnemyRole.BUILDER) {
            zombie.customName(Component.text("防衛戦建築兵", NamedTextColor.BLUE));
            zombie.setCustomNameVisible(true);
        }
    }

    private void reconcilePhysicalEnemies(ActiveDefense defense) {
        List<UUID> logicalIds = new ArrayList<>(defense.entitiesByLogicalId.keySet());
        for (UUID logicalId : logicalIds) {
            UUID entityId = defense.entitiesByLogicalId.get(logicalId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null || !entity.isValid() || entity.isDead()) {
                requeueMissingEnemy(defense, logicalId, entityId);
                continue;
            }
            if (!entity.getWorld().equals(defense.world)
                    || !combatArea.contains(
                            defense.core.blockX() + 0.5d,
                            defense.core.blockZ() + 0.5d,
                            entity.getX(),
                            entity.getZ())) {
                entity.remove();
                requeueMissingEnemy(defense, logicalId, entityId);
                continue;
            }
            if (entity.getLocation().distanceSquared(defense.coreTarget)
                    <= CORE_REACH_DISTANCE_SQUARED) {
                resolveCoreReach(defense, logicalId, entity);
                if (defense.ending || defense.session.phase() != DefensePhase.WAVE_ACTIVE) {
                    return;
                }
                continue;
            }
            EnemyProgress progress = defense.enemyProgress.computeIfAbsent(
                    logicalId,
                    ignored -> new EnemyProgress(
                            entity.getLocation().distanceSquared(defense.coreTarget),
                            currentTick));
            progress.observe(
                    entity.getLocation().distanceSquared(defense.coreTarget),
                    currentTick);
            if (currentTick - progress.lastProgressTick >= PATH_STALL_TIMEOUT_TICKS) {
                defense.session.enterRecovery();
                finish(defense, "イベント敵がコアへの進路を確保できないため技術的復旧へ移行しました。");
                return;
            }
            if (currentTick - defense.lastPathRefreshTick >= PATH_REFRESH_TICKS
                    && entity instanceof Zombie zombie) {
                EnemyRole role = defense.enemyRolesByLogicalId.getOrDefault(
                        logicalId,
                        EnemyRole.NORMAL);
                boolean accepted = zombie.getPathfinder().moveTo(
                        defense.coreTarget,
                        role.navigationSpeed(settings.enemies().moveSpeed()));
                progress.recordPathAttempt(accepted);
                EnemyObstacleFacts obstacleFacts = pathIntegration.inspect(
                        zombie,
                        defense.coreTarget,
                        role,
                        defense.pathMetrics);
                EnemyPathAction pathAction = EnemyPathController.decide(
                        role,
                        accepted,
                        obstacleFacts,
                        progress.consecutivePathFailures);
                defense.pathMetrics.recordDecision(accepted, pathAction);
                if (pathAction == EnemyPathAction.RECOVER) {
                    defense.session.enterRecovery();
                    finish(defense, "イベント敵の経路探索が連続して失敗したため技術的復旧へ移行しました。");
                    return;
                }
                if (pathAction == EnemyPathAction.BREAK_OBSTACLE) {
                    boolean obstacleBroken = terrainAction.tryBreakObstacle(
                            zombie,
                            defense.coreTarget,
                            new TaggedEnemy(defense.session.eventId(), logicalId, role));
                    defense.pathMetrics.recordBreakAttempt(obstacleBroken);
                    if (obstacleBroken) {
                        progress.recordPathAttempt(true);
                    }
                }
                if (pathAction == EnemyPathAction.BUILD_SUPPORT) {
                    boolean bridgePlaced = terrainAction.tryBuildBridge(
                            zombie,
                            defense.coreTarget,
                            new TaggedEnemy(defense.session.eventId(), logicalId, role));
                    defense.pathMetrics.recordBridgeAttempt(bridgePlaced);
                    if (bridgePlaced) {
                        progress.recordPathAttempt(true);
                    }
                }
            }
        }
        if (currentTick - defense.lastPathRefreshTick >= PATH_REFRESH_TICKS) {
            defense.lastPathRefreshTick = currentTick;
        }
    }

    private void requeueMissingEnemy(
            ActiveDefense defense,
            UUID logicalId,
            UUID entityId) {
        if (defense.entitiesByLogicalId.remove(logicalId, entityId)) {
            defense.enemyProgress.remove(logicalId);
            defense.pendingLogicalIds.addLast(logicalId);
            defense.session.returnAliveEnemiesToPending(1L);
            observe(
                    defense,
                    persistence.recordEnemyStatus(
                            defense.session.eventId(),
                            logicalId,
                            entityId,
                            EnemyStatus.DESPAWNED));
            persistTransition(defense);
        }
    }

    private void resolveCoreReach(
            ActiveDefense defense,
            UUID logicalId,
            Entity entity) {
        UUID entityId = entity.getUniqueId();
        entity.remove();
        if (!defense.entitiesByLogicalId.remove(logicalId, entityId)) {
            return;
        }
        defense.enemyProgress.remove(logicalId);
        defense.enemyRolesByLogicalId.remove(logicalId);
        observe(
                defense,
                persistence.recordEnemyStatus(
                        defense.session.eventId(),
                        logicalId,
                        entityId,
                        EnemyStatus.DESPAWNED));
        boolean coreDestroyed = defense.session.damageCore(
                settings.core().damagePerEnemy());
        if (coreDestroyed) {
            finish(defense, "コアが破壊されたため敗北しました。");
            return;
        }
        boolean waveCleared = defense.session.recordEnemyDefeated(1L);
        if (defense.session.isTerminal()) {
            onWaveCleared(defense);
            return;
        }
        persistTransition(defense);
        if (waveCleared) {
            onWaveCleared(defense);
        }
    }

    private void onWaveCleared(ActiveDefense defense) {
        if (defense.session.phase() == DefensePhase.VICTORY) {
            finish(defense, "全ウェーブを突破しました。勝利です。");
            return;
        }
        defense.phaseDeadlineTick = deadline(settings.combat().intermissionSeconds());
        broadcast(defense, Component.text("ウェーブを突破しました。次を準備します。", NamedTextColor.AQUA));
    }

    private boolean defeatForAbsenceIfExpired(ActiveDefense defense) {
        boolean anyRegisteredPresent = false;
        for (UUID participant : defense.session.registeredParticipants()) {
            Player player = Bukkit.getPlayer(participant);
            if (player != null && isInside(defense, player)) {
                anyRegisteredPresent = true;
                break;
            }
        }
        if (anyRegisteredPresent) {
            defense.absentSinceTick = -1L;
            return false;
        }
        if (defense.absentSinceTick < 0L) {
            defense.absentSinceTick = currentTick;
            return false;
        }
        long grace = secondsToTicks(settings.combat().absenceGraceSeconds());
        if (currentTick - defense.absentSinceTick < grace) {
            return false;
        }
        defense.session.defeatIfNoRegisteredParticipantsPresent(Set.of());
        finish(defense, "登録参加者が全員不在になったため敗北しました。");
        return true;
    }

    private void populateLogicalQueue(ActiveDefense defense, long enemyCount) {
        defense.pendingLogicalIds.clear();
        defense.entitiesByLogicalId.clear();
        defense.enemyRolesByLogicalId.clear();
        int enemyCountInt = Math.toIntExact(enemyCount);
        List<EnemyRole> roles = enemyRoles.forWave(
                defense.session.stageLevel(),
                defense.session.currentWave(),
                enemyCountInt,
                defense.session.currentWave() == defense.session.totalWaves());
        for (int index = 0; index < enemyCountInt; index++) {
            UUID logicalEnemyId = UUID.randomUUID();
            defense.pendingLogicalIds.addLast(logicalEnemyId);
            EnemyRole role = roles.get(index);
            defense.enemyRolesByLogicalId.put(logicalEnemyId, role);
        }
    }

    private long enemyCountForNextWave(DefenseSession session) {
        long nextWave = session.currentWave() + 1L;
        long perParticipant = enemyCountPerParticipant(nextWave);
        long total = Math.multiplyExact(perParticipant, session.effectiveParticipants().size());
        if (total <= 0L || total > MAX_FOUNDATION_WAVE_ENEMIES) {
            throw new IllegalStateException(
                    "calculated wave enemy count is outside the foundation safety cap: " + total);
        }
        return total;
    }

    private long enemyCountPerParticipant(long wave) {
        long base = settings.enemies().basePerWave();
        long increment = Math.multiplyExact(
                settings.enemies().addedPerWave(), wave - 1L);
        long perParticipant = Math.addExact(base, increment);
        if (perParticipant <= 0L || perParticipant > MAX_FOUNDATION_WAVE_ENEMIES) {
            throw new IllegalStateException(
                    "calculated per-participant enemy count is outside the foundation safety cap: "
                            + perParticipant);
        }
        return perParticipant;
    }

    private Optional<Location> findSpawnLocation(ActiveDefense defense) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS_PER_ENEMY; attempt++) {
            double radius = random.nextDouble(
                    combatArea.spawnInner(), combatArea.spawnOuter());
            double angle = random.nextDouble(0.0d, Math.PI * 2.0d);
            int x = (int) Math.floor(defense.coreTarget.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(defense.coreTarget.getZ() + Math.sin(angle) * radius);
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (!defense.world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            Block surface = defense.world.getHighestBlockAt(
                    x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block feet = surface.getRelative(BlockFace.UP);
            if (!surface.getType().isSolid()
                    || !feet.isPassable()
                    || !feet.getRelative(BlockFace.UP).isPassable()) {
                continue;
            }
            addChunkTicket(defense, chunkX, chunkZ);
            return Optional.of(feet.getLocation().add(0.5d, 0.0d, 0.5d));
        }
        return Optional.empty();
    }

    private void finish(ActiveDefense defense, String message) {
        if (defense.ending) {
            return;
        }
        defense.ending = true;
        logPathMetrics(defense);
        broadcast(defense, Component.text(message, NamedTextColor.GOLD));
        cleanupWorldState(defense);
        defense.finishOperationId = UUID.randomUUID();
        defense.finishSnapshot = defense.session.snapshot();
        defense.finishRetryTick = currentTick;
        submitFinish(defense);
    }

    private void logPathMetrics(ActiveDefense defense) {
        EnemyPathMetrics.Snapshot metrics = defense.pathMetrics.snapshot();
        plugin.getLogger().info(
                "Enemy path metrics for event " + defense.session.eventId()
                        + ": inspections=" + metrics.inspectionCount()
                        + ", inspectionFailures=" + metrics.inspectionFailureCount()
                        + ", averageNanos=" + metrics.averageInspectionNanos()
                        + ", maxNanos=" + metrics.maxInspectionNanos()
                        + ", directPaths=" + metrics.directPathAcceptedCount()
                        + ", advance=" + metrics.advanceDecisionCount()
                        + ", break=" + metrics.breakObstacleDecisionCount()
                        + ", build=" + metrics.buildSupportDecisionCount()
                        + ", recalculate=" + metrics.recalculateDecisionCount()
                        + ", recover=" + metrics.recoverDecisionCount()
                        + ", bridgeAttempts=" + metrics.bridgeAttemptCount()
                        + ", bridgePlacements=" + metrics.bridgePlacementCount()
                        + ", breakAttempts=" + metrics.breakAttemptCount()
                        + ", breakSuccesses=" + metrics.breakSuccessCount());
    }

    private void submitFinish(ActiveDefense defense) {
        if (active != defense || !defense.ending || defense.finishInFlight) {
            return;
        }
        if (!prepareTerrainSettlement(defense)) {
            return;
        }
        defense.finishInFlight = true;
        defense.finishAttempts++;
        CompletionStage<Void> completion = persistence.finish(
                Objects.requireNonNull(defense.finishSnapshot, "finishSnapshot"),
                Objects.requireNonNull(defense.finishOperationId, "finishOperationId"));
        completion.whenComplete((ignored, failure) -> runOnMainThread(() -> {
            if (active != defense) {
                return;
            }
            defense.finishInFlight = false;
            if (failure != null) {
                defense.persistenceFailure = rootMessage(failure);
                defense.finishRetryTick = currentTick + finishRetryDelay(defense.finishAttempts);
                plugin.getLogger().severe(
                        "Could not finish defense event " + defense.session.eventId()
                                + " on attempt " + defense.finishAttempts
                                + "; it will retry automatically: "
                                + defense.persistenceFailure);
                return;
            }
            escrowDrops.removeEventDisplays(defense.session.eventId());
            if (defense.finishSnapshot.phase() != DefensePhase.RECOVERY) {
                rewardQueues.onEventSettled(defense.session.eventId());
            }
            active = null;
        }));
    }

    private boolean prepareTerrainSettlement(ActiveDefense defense) {
        if (defense.terrainSettlementComplete || defense.finishSnapshot == null) {
            return true;
        }
        if (!escrowDrops.beginTerminal(defense.finishSnapshot.eventId())) {
            defense.persistenceFailure = "an escrow pickup claim is still in flight";
            defense.finishRetryTick = currentTick + finishRetryDelay(defense.finishAttempts + 1);
            return false;
        }
        if (defense.finishSnapshot.phase() == DefensePhase.RECOVERY) {
            return true;
        }
        try {
            blockMutations.settleEvent(
                    defense.finishSnapshot.eventId(),
                    defense.finishSnapshot.phase(),
                    Instant.now());
            defense.terrainSettlementComplete = true;
            return true;
        } catch (RuntimeException settlementFailure) {
            defense.persistenceFailure = rootMessage(settlementFailure);
            defense.finishRetryTick = currentTick + finishRetryDelay(defense.finishAttempts + 1);
            plugin.getLogger().severe(
                    "Could not settle Paper terrain for defense event "
                            + defense.session.eventId()
                            + "; it will retry automatically: "
                            + defense.persistenceFailure);
            return false;
        }
    }

    private static long finishRetryDelay(int attempts) {
        long multiplier = Math.min(30L, Math.max(1L, attempts));
        return Math.min(FINISH_RETRY_MAX_TICKS, FINISH_RETRY_BASE_TICKS * multiplier);
    }

    private void cleanupWorldState(ActiveDefense defense) {
        for (Map.Entry<UUID, UUID> entry : defense.entitiesByLogicalId.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getValue());
            if (entity != null) {
                entity.remove();
            }
            observe(
                    defense,
                    persistence.recordEnemyStatus(
                            defense.session.eventId(),
                            entry.getKey(),
                            entry.getValue(),
                            EnemyStatus.DESPAWNED));
        }
        defense.entitiesByLogicalId.clear();
        defense.enemyProgress.clear();
        defense.enemyRolesByLogicalId.clear();
        defense.pendingLogicalIds.clear();
        for (UUID playerId : defense.bossBarViewers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.hideBossBar(defense.bossBar);
            }
        }
        defense.bossBarViewers.clear();
        for (long chunkKey : defense.chunkTickets) {
            int chunkX = (int) chunkKey;
            int chunkZ = (int) (chunkKey >> 32);
            defense.world.removePluginChunkTicket(chunkX, chunkZ, plugin);
        }
        defense.chunkTickets.clear();
    }

    private void persistTransition(ActiveDefense defense) {
        observe(
                defense,
                persistence.persistState(
                        defense.session.snapshot(), UUID.randomUUID()));
    }

    private boolean ensureEffectiveParticipant(ActiveDefense defense, UUID playerId) {
        if (defense.session.isEffectiveParticipant(playerId)) {
            return true;
        }
        DefensePhase phase = defense.session.phase();
        if (phase != DefensePhase.PREPARATION
                && phase != DefensePhase.WAVE_ACTIVE
                && phase != DefensePhase.INTERMISSION) {
            return false;
        }
        if (defense.session.effectiveParticipants().size()
                >= settings.combat().maxParticipants()) {
            return false;
        }

        long additionalEnemies = phase == DefensePhase.WAVE_ACTIVE
                ? enemyCountPerParticipant(defense.session.currentWave())
                : 0L;
        if (additionalEnemies > 0L) {
            long remaining = defense.session.remainingLogicalEnemies();
            if (remaining > MAX_FOUNDATION_WAVE_ENEMIES - additionalEnemies) {
                plugin.getLogger().warning(
                        "Denied late participant because the wave safety cap would be exceeded: "
                                + playerId);
                return false;
            }
        }
        boolean added = defense.session.addEffectiveParticipant(playerId, additionalEnemies);
        if (!added) {
            return true;
        }
        for (long index = 0L; index < additionalEnemies; index++) {
            defense.pendingLogicalIds.addLast(UUID.randomUUID());
        }
        persistTransition(defense);
        broadcast(
                defense,
                Component.text(
                        "途中参加者を有効参加人数へ追加し、難易度を上方補正しました。",
                        NamedTextColor.YELLOW));
        return true;
    }

    private void observe(ActiveDefense defense, CompletionStage<Void> completion) {
        completion.whenComplete((ignored, failure) -> {
            if (failure == null) {
                return;
            }
            runOnMainThread(() -> {
                if (active == defense && defense.persistenceFailure == null) {
                    defense.persistenceFailure = rootMessage(failure);
                    plugin.getLogger().severe(
                            "Defense persistence operation failed for "
                                    + defense.session.eventId() + ": "
                                    + defense.persistenceFailure);
                }
            });
        });
    }

    private void refreshCandidates(ActiveDefense defense) {
        for (UUID memberId : defense.teamMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null && isInside(defense, player)) {
                defense.candidateEntryTick.putIfAbsent(memberId, currentTick);
            }
        }
    }

    private List<UUID> selectParticipants(ActiveDefense defense) {
        return defense.teamMembers.stream()
                .filter(memberId -> {
                    Player player = Bukkit.getPlayer(memberId);
                    return player != null && isInside(defense, player);
                })
                .sorted(Comparator
                        .comparingLong((UUID playerId) ->
                                defense.candidateEntryTick.getOrDefault(
                                        playerId, Long.MAX_VALUE))
                        .thenComparing(UUID::toString))
                .limit(settings.combat().maxParticipants())
                .toList();
    }

    private void refreshBossBar(ActiveDefense defense) {
        Set<UUID> shouldSee = new HashSet<>();
        for (UUID memberId : defense.teamMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null && isInside(defense, player)) {
                shouldSee.add(memberId);
                if (defense.bossBarViewers.add(memberId)) {
                    player.showBossBar(defense.bossBar);
                }
            }
        }
        for (UUID viewerId : new HashSet<>(defense.bossBarViewers)) {
            if (!shouldSee.contains(viewerId)) {
                Player player = Bukkit.getPlayer(viewerId);
                if (player != null) {
                    player.hideBossBar(defense.bossBar);
                }
                defense.bossBarViewers.remove(viewerId);
            }
        }

        DefenseSession session = defense.session;
        float progress = (float) ((double) session.coreState().currentHitPoints()
                / session.coreState().maximumHitPoints());
        defense.bossBar.progress(Math.max(0.0f, Math.min(1.0f, progress)));
        defense.bossBar.color(session.phase() == DefensePhase.WAVE_ACTIVE
                ? BossBar.Color.RED
                : BossBar.Color.YELLOW);
        defense.bossBar.name(Component.text(
                "防衛戦 " + session.phase()
                        + " | Wave " + session.currentWave() + "/" + session.totalWaves()
                        + " | 敵 " + session.remainingLogicalEnemies()
                        + " | Core " + session.coreState().currentHitPoints()
                        + "/" + session.coreState().maximumHitPoints()));
    }

    private void showCountdownActionBar(ActiveDefense defense) {
        long remainingTicks = Math.max(0L, defense.phaseDeadlineTick - currentTick);
        long remainingSeconds = (remainingTicks + TICKS_PER_SECOND - 1L) / TICKS_PER_SECOND;
        Component message = Component.text("準備: " + remainingSeconds + "秒", NamedTextColor.YELLOW);
        for (UUID memberId : defense.teamMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null && isInside(defense, player)) {
                player.sendActionBar(message);
            }
        }
    }

    private void broadcast(ActiveDefense defense, Component message) {
        for (UUID memberId : defense.teamMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private boolean isInside(ActiveDefense defense, Player player) {
        return player.isOnline()
                && player.getWorld().equals(defense.world)
                && combatArea.contains(
                        defense.core.blockX() + 0.5d,
                        defense.core.blockZ() + 0.5d,
                        player.getX(),
                        player.getZ());
    }

    private void addChunkTicket(ActiveDefense defense, int chunkX, int chunkZ) {
        long key = Chunk.getChunkKey(chunkX, chunkZ);
        if (defense.chunkTickets.add(key)) {
            defense.world.addPluginChunkTicket(chunkX, chunkZ, plugin);
        }
    }

    private long deadline(int seconds) {
        return Math.addExact(currentTick, secondsToTicks(seconds));
    }

    private static long secondsToTicks(int seconds) {
        return Math.multiplyExact((long) seconds, TICKS_PER_SECOND);
    }

    private void runOnMainThread(Runnable work) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, work);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Paper API access must occur on the main thread");
        }
    }

    @Override
    public void close() {
        requireMainThread();
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (active != null) {
            if (!active.session.isTerminal()) {
                active.session.enterRecovery();
            }
            active.ending = true;
            cleanupWorldState(active);
        }
    }

    private static final class ActiveDefense {
        private final DefenseSession session;
        private final CoreRecord core;
        private final Set<UUID> teamMembers;
        private final World world;
        private final Location coreTarget;
        private final BossBar bossBar;
        private final Map<UUID, Long> candidateEntryTick = new HashMap<>();
        private final Deque<UUID> pendingLogicalIds = new ArrayDeque<>();
        private final Map<UUID, UUID> entitiesByLogicalId = new LinkedHashMap<>();
        private final Map<UUID, EnemyProgress> enemyProgress = new HashMap<>();
        private final Map<UUID, EnemyRole> enemyRolesByLogicalId = new HashMap<>();
        private final Set<Long> chunkTickets = new HashSet<>();
        private final Set<UUID> bossBarViewers = new HashSet<>();
        private final EnemyPathMetrics pathMetrics = new EnemyPathMetrics();

        private long phaseDeadlineTick;
        private long absentSinceTick = -1L;
        private long lastPathRefreshTick;
        private long spawnFailureSinceTick = -1L;
        private boolean ending;
        private boolean finishInFlight;
        private int finishAttempts;
        private long finishRetryTick;
        private UUID finishOperationId;
        private DefenseSessionSnapshot finishSnapshot;
        private boolean terrainSettlementComplete;
        private String persistenceFailure;

        private ActiveDefense(
                DefenseSession session,
                CoreRecord core,
                Set<UUID> teamMembers,
                World world,
                Location coreTarget,
                BossBar bossBar) {
            this.session = session;
            this.core = core;
            this.teamMembers = teamMembers;
            this.world = world;
            this.coreTarget = coreTarget;
            this.bossBar = bossBar;
        }
    }

    private static final class EnemyProgress {
        private double bestDistance;
        private long lastProgressTick;
        private int consecutivePathFailures;

        private EnemyProgress(double distanceSquared, long currentTick) {
            bestDistance = Math.sqrt(distanceSquared);
            lastProgressTick = currentTick;
        }

        private void observe(double distanceSquared, long currentTick) {
            double distance = Math.sqrt(distanceSquared);
            if (distance + MIN_PATH_PROGRESS < bestDistance) {
                bestDistance = distance;
                lastProgressTick = currentTick;
            }
        }

        private void recordPathAttempt(boolean accepted) {
            consecutivePathFailures = accepted ? 0 : consecutivePathFailures + 1;
        }
    }
}
