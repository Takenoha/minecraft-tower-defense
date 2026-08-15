package io.github.takenoha.towerdefense.runtime;

import io.github.takenoha.towerdefense.config.CoreWarningSoundResolver;
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
import io.github.takenoha.towerdefense.paper.DefenseShardTagger;
import io.github.takenoha.towerdefense.paper.EnhancementCoreTagger;
import io.github.takenoha.towerdefense.paper.PaperBlockMutationAdapter;
import io.github.takenoha.towerdefense.paper.PaperCombatAreaSafetyValidator;
import io.github.takenoha.towerdefense.paper.PaperEscrowDropManager;
import io.github.takenoha.towerdefense.paper.PaperEnemyPathIntegrationBoundary;
import io.github.takenoha.towerdefense.paper.PaperEnemyTerrainAction;
import io.github.takenoha.towerdefense.paper.RewardQueueDeliveryManager;
import io.github.takenoha.towerdefense.tactical.TacticalBuildRuntime;
import io.github.takenoha.towerdefense.tactical.TacticalTerminalResult;
import io.github.takenoha.towerdefense.tactical.TacticalUnlockResult;
import io.github.takenoha.towerdefense.paper.ThirdPartyRegionProtectionAdapter;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.EnemyLedgerEntry;
import io.github.takenoha.towerdefense.persistence.EnemyStatus;
import io.github.takenoha.towerdefense.persistence.ResourceRepository;
import io.github.takenoha.towerdefense.persistence.TeamResourceSettlement;
import java.nio.charset.StandardCharsets;
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
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.ZombieVillager;
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
    /** A pathfinder acceptance without distance progress is not a usable direct path forever. */
    private static final long PATH_STALL_ACTION_TICKS = 2L * PATH_REFRESH_TICKS;
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
    private final DefenseShardTagger defenseShards;
    private final EnhancementCoreTagger enhancementCores;
    private final ResourceRepository resources;
    private final ActionBarBroker actionBars;
    private final TacticalBuildRuntime tacticalRuntime;

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
        this(
                plugin,
                settings,
                tagger,
                persistence,
                blockMutations,
                escrowDrops,
                rewardQueues,
                coreRegistry,
                regionProtection,
                null);
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
            ThirdPartyRegionProtectionAdapter regionProtection,
            ResourceRepository resources) {
        this(
                plugin,
                settings,
                tagger,
                persistence,
                blockMutations,
                escrowDrops,
                rewardQueues,
                coreRegistry,
                regionProtection,
                resources,
                new ActionBarBroker());
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
            ThirdPartyRegionProtectionAdapter regionProtection,
            ResourceRepository resources,
            ActionBarBroker actionBars) {
        this(
                plugin,
                settings,
                tagger,
                persistence,
                blockMutations,
                escrowDrops,
                rewardQueues,
                coreRegistry,
                regionProtection,
                resources,
                actionBars,
                TacticalBuildRuntime.disabled());
    }

    /** Full constructor used by the tactical build integration. */
    public DefenseSessionManager(
            JavaPlugin plugin,
            PluginSettings settings,
            EventEnemyTagger tagger,
            DefensePersistenceSink persistence,
            PaperBlockMutationAdapter blockMutations,
            PaperEscrowDropManager escrowDrops,
            RewardQueueDeliveryManager rewardQueues,
            CoreRegistry coreRegistry,
            ThirdPartyRegionProtectionAdapter regionProtection,
            ResourceRepository resources,
            ActionBarBroker actionBars,
            TacticalBuildRuntime tacticalRuntime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tagger = Objects.requireNonNull(tagger, "tagger");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.blockMutations = Objects.requireNonNull(blockMutations, "blockMutations");
        this.escrowDrops = Objects.requireNonNull(escrowDrops, "escrowDrops");
        this.rewardQueues = Objects.requireNonNull(rewardQueues, "rewardQueues");
        this.coreRegistry = Objects.requireNonNull(coreRegistry, "coreRegistry");
        this.regionProtection = Objects.requireNonNull(regionProtection, "regionProtection");
        this.resources = resources;
        this.actionBars = Objects.requireNonNull(actionBars, "actionBars");
        this.tacticalRuntime = Objects.requireNonNull(tacticalRuntime, "tacticalRuntime");
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
        defenseShards = new DefenseShardTagger(plugin);
        enhancementCores = new EnhancementCoreTagger(plugin);
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
                        BossBar.Overlay.PROGRESS),
                new CoreWarningSoundGate(settings.core().warningMinIntervalTicks()));
        active = next;
        try {
            tacticalRuntime.rebuild(session.eventId());
        } catch (RuntimeException recoveryFailure) {
            // Unknown tactical state is fail-closed: the defense remains playable without
            // tactical boosts until the next explicit lifecycle operation can rebuild it.
            tacticalRuntime.invalidate(session.eventId());
            plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Could not rebuild tactical effects for defense " + session.eventId(),
                    recoveryFailure);
        }
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
                active.coreAttackSchedules.size(),
                active.coreAttackCount,
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
    public boolean mayAffectFromTower(TaggedEnemy taggedEnemy, UUID teamId) {
        requireMainThread();
        Objects.requireNonNull(taggedEnemy, "taggedEnemy");
        Objects.requireNonNull(teamId, "teamId");
        ActiveDefense defense = active;
        return defense != null
                && !defense.ending
                && defense.session.phase() == DefensePhase.WAVE_ACTIVE
                && defense.session.eventId().equals(taggedEnemy.eventId())
                && defense.session.teamId().equals(teamId)
                && defense.entitiesByLogicalId.containsKey(taggedEnemy.logicalEnemyId());
    }

    /** Returns whether a team may install a tower in the current lifecycle window. */
    public boolean mayPlaceTower(UUID teamId) {
        requireMainThread();
        Objects.requireNonNull(teamId, "teamId");
        ActiveDefense defense = active;
        if (defense == null) {
            return true;
        }
        return !defense.ending
                && defense.session.teamId().equals(teamId)
                && (defense.session.phase() == DefensePhase.PREPARATION
                        || defense.session.phase() == DefensePhase.INTERMISSION);
    }

    /** Returns whether a tower's individual level may change for this team right now. */
    public boolean mayUpgradeTower(UUID teamId) {
        requireMainThread();
        Objects.requireNonNull(teamId, "teamId");
        ActiveDefense defense = active;
        if (defense == null) {
            return true;
        }
        return !defense.ending
                && defense.session.teamId().equals(teamId)
                && (defense.session.phase() == DefensePhase.PREPARATION
                        || defense.session.phase() == DefensePhase.INTERMISSION);
    }

    /** Returns whether event-scoped funds may be spent by this team's management GUI. */
    public boolean maySpendBattleFunds(UUID teamId) {
        requireMainThread();
        Objects.requireNonNull(teamId, "teamId");
        ActiveDefense defense = active;
        return defense != null
                && !defense.ending
                && defense.session.teamId().equals(teamId)
                && (defense.session.phase() == DefensePhase.PREPARATION
                        || defense.session.phase() == DefensePhase.INTERMISSION);
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
        active.coreAttackSchedules.remove(taggedEnemy.logicalEnemyId());
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
        int shardQuantity = settings.rewards().defenseShardsFor(taggedEnemy.role());
        if (shardQuantity > 0) {
            escrowDrops.issueEnemyDrop(
                    taggedEnemy.eventId(),
                    taggedEnemy.logicalEnemyId(),
                    entity.getLocation(),
                    "defense_shard",
                    defenseShards.create(
                            deterministicOperation(
                                    taggedEnemy.eventId(),
                                    "DEFENSE_SHARD_ITEM",
                                    taggedEnemy.logicalEnemyId().toString()),
                            shardQuantity),
                    Instant.now());
        }
        if (taggedEnemy.role() != EnemyRole.NORMAL
                && settings.rewards().enhancementCoreDropPercent() > 0
                && ThreadLocalRandom.current().nextInt(100)
                        < settings.rewards().enhancementCoreDropPercent()) {
            escrowDrops.issueEnemyDrop(
                    taggedEnemy.eventId(),
                    taggedEnemy.logicalEnemyId(),
                    entity.getLocation(),
                    "enhancement_core",
                    enhancementCores.create(
                            deterministicOperation(
                                    taggedEnemy.eventId(),
                                    "ENHANCEMENT_CORE_ITEM",
                                    taggedEnemy.logicalEnemyId().toString()),
                            1),
                    Instant.now());
        }
        int funds = settings.rewards().battleFundsFor(taggedEnemy.role());
        if (funds > 0) {
            observe(
                    active,
                    persistence.creditBattleFunds(
                            taggedEnemy.eventId(),
                            active.session.teamId(),
                            deterministicOperation(
                                    taggedEnemy.eventId(),
                                    "BATTLE_FUNDS_ENEMY",
                                    taggedEnemy.logicalEnemyId().toString()),
                            "ENEMY_" + taggedEnemy.role().id()
                                    + ":" + taggedEnemy.logicalEnemyId(),
                            funds));
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
        actionBars.advance(currentTick);
        ActiveDefense defense = active;
        if (defense == null) {
            return;
        }
        if (defense.ending) {
            // Claims can complete while terminal persistence is draining.  Keep rendering the
            // broker here so the pickup notice receives its full TTL instead of being lost behind
            // the ending short-circuit.
            renderActionBars(defense);
            if (defense.finishComplete) {
                if (currentTick >= defense.finishActionBarDeadlineTick) {
                    active = null;
                }
                return;
            }
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
        renderActionBars(defense);
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
        notifyTacticalUnlock(
                defense,
                activateTacticalAtPreparation(defense));
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
        if (defense.session.currentWave() == defense.session.totalWaves()) {
            notifyTacticalUnlock(
                    defense,
                    activateFinalTacticalTier(defense));
        }
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
                Location location = spawnLocation.orElseThrow();
                boolean finalWave = defense.session.currentWave()
                        == defense.session.totalWaves();
                zombie = switch (role) {
                    case DESTROYER -> defense.world.spawn(
                            location,
                            Husk.class,
                            CreatureSpawnEvent.SpawnReason.CUSTOM,
                            entity -> configureEnemy(entity, role, finalWave));
                    case BUILDER -> defense.world.spawn(
                            location,
                            ZombieVillager.class,
                            CreatureSpawnEvent.SpawnReason.CUSTOM,
                            entity -> configureEnemy(entity, role, finalWave));
                    case NORMAL, BOSS -> defense.world.spawn(
                            location,
                            Zombie.class,
                            CreatureSpawnEvent.SpawnReason.CUSTOM,
                            entity -> configureEnemy(entity, role, finalWave));
                };
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

    private void configureEnemy(Zombie zombie, EnemyRole role, boolean finalWave) {
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        zombie.setCanPickupItems(false);
        zombie.setCanBreakDoors(false);
        zombie.setShouldBurnInDay(false);
        zombie.setLootTable(null);
        zombie.getPathfinder().setCanOpenDoors(false);
        zombie.getPathfinder().setCanPassDoors(false);
        if (EventEnemyVisualPolicy.shouldGlow(role)) {
            zombie.setGlowing(true);
        }
        if (role == EnemyRole.BOSS) {
            AttributeInstance maximumHealth = Objects.requireNonNull(
                    zombie.getAttribute(Attribute.MAX_HEALTH),
                    "zombie max-health attribute");
            double boostedHealth = maximumHealth.getBaseValue()
                    * settings.enemies().bossHealthMultiplier();
            maximumHealth.setBaseValue(boostedHealth);
            zombie.setHealth(boostedHealth);
        }
        refreshEnemyHealthBar(zombie, role, finalWave);
    }

    private void refreshEnemyHealthBar(Entity entity, EnemyRole role, boolean finalWave) {
        if (!(entity instanceof LivingEntity livingEntity)) {
            return;
        }
        AttributeInstance maximumHealth = livingEntity.getAttribute(Attribute.MAX_HEALTH);
        if (maximumHealth == null) {
            return;
        }
        Component displayName = EnemyHealthBar.displayName(
                role,
                finalWave,
                livingEntity.getHealth(),
                maximumHealth.getValue());
        if (!displayName.equals(livingEntity.customName())) {
            livingEntity.customName(displayName);
        }
        if (!livingEntity.isCustomNameVisible()) {
            livingEntity.setCustomNameVisible(true);
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
            EnemyRole role = defense.enemyRolesByLogicalId.getOrDefault(
                    logicalId,
                    EnemyRole.NORMAL);
            refreshEnemyHealthBar(
                    entity,
                    role,
                    defense.session.currentWave() == defense.session.totalWaves());
            if (EventEnemyVisualPolicy.shouldGlow(role)) {
                entity.setGlowing(true);
            }
            boolean atCore = entity.getLocation().distanceSquared(defense.coreTarget)
                    <= CORE_REACH_DISTANCE_SQUARED;
            if (defense.coreAttackSchedules.containsKey(logicalId) && !atCore) {
                defense.coreAttackSchedules.remove(logicalId);
            }
            if (defense.coreAttackSchedules.containsKey(logicalId)) {
                holdAtCore(entity);
                attackCoreIfDue(defense, logicalId);
                if (defense.ending || defense.session.phase() != DefensePhase.WAVE_ACTIVE) {
                    return;
                }
                continue;
            }
            if (atCore) {
                beginCoreAttack(defense, logicalId, entity);
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
                boolean accepted = zombie.getPathfinder().moveTo(
                        defense.coreTarget,
                        role.navigationSpeed(settings.enemies().moveSpeed()));
                progress.recordPathAttempt(accepted);
                EnemyObstacleFacts obstacleFacts = pathIntegration.inspect(
                        zombie,
                        defense.coreTarget,
                        role,
                        defense.pathMetrics);
                boolean pathStalled = currentTick - progress.lastProgressTick
                        >= PATH_STALL_ACTION_TICKS;
                EnemyPathAction pathAction = EnemyPathController.decide(
                        role,
                        accepted && !pathStalled,
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
            defense.coreAttackSchedules.remove(logicalId);
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

    private void beginCoreAttack(
            ActiveDefense defense,
            UUID logicalId,
            Entity entity) {
        if (!defense.entitiesByLogicalId.containsKey(logicalId)) {
            return;
        }
        defense.enemyProgress.remove(logicalId);
        defense.coreAttackSchedules.computeIfAbsent(
                logicalId,
                ignored -> new CoreAttackSchedule(settings.core().attackIntervalTicks()));
        holdAtCore(entity);
        attackCoreIfDue(defense, logicalId);
    }

    private void holdAtCore(Entity entity) {
        if (entity instanceof Zombie zombie) {
            zombie.getPathfinder().stopPathfinding();
        }
    }

    private void attackCoreIfDue(ActiveDefense defense, UUID logicalId) {
        CoreAttackSchedule schedule = defense.coreAttackSchedules.get(logicalId);
        if (schedule == null || !schedule.tryClaim(currentTick)) {
            return;
        }
        defense.coreAttackCount = increment(defense.coreAttackCount);
        long beforeHitPoints = defense.session.coreState().currentHitPoints();
        boolean coreDestroyed = defense.session.damageCore(
                settings.core().damagePerEnemy());
        if (defense.session.coreState().currentHitPoints() < beforeHitPoints) {
            playCoreWarningIfDue(defense);
        }
        if (coreDestroyed) {
            finish(defense, "コアが破壊されたため敗北しました。");
            return;
        }
        persistTransition(defense);
    }

    private void playCoreWarningIfDue(ActiveDefense defense) {
        if (defense.ending
                || defense.session.phase() != DefensePhase.WAVE_ACTIVE
                || !defense.coreWarningSoundGate.tryClaim(currentTick)) {
            return;
        }
        var sound = CoreWarningSoundResolver.resolve(settings.core().warningSound());
        if (sound.isEmpty()) {
            plugin.getLogger().warning(
                    "Skipping invalid core warning sound " + settings.core().warningSound());
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline() && isInside(defense, player)) {
                player.playSound(
                        defense.coreTarget,
                        sound.orElseThrow(),
                        (float) settings.core().warningVolume(),
                        (float) settings.core().warningPitch());
            }
        }
    }

    private void onWaveCleared(ActiveDefense defense) {
        int waveFunds = settings.rewards().battleFundsPerWave();
        if (waveFunds > 0 && defense.session.currentWave() > 0) {
            observe(
                    defense,
                    persistence.creditBattleFunds(
                            defense.session.eventId(),
                            defense.session.teamId(),
                            deterministicOperation(
                                    defense.session.eventId(),
                                    "BATTLE_FUNDS_WAVE",
                                    Integer.toString(defense.session.currentWave())),
                            "WAVE_CLEAR:" + defense.session.currentWave(),
                            waveFunds));
        }
        if (defense.session.phase() == DefensePhase.VICTORY) {
            finish(defense, "全ウェーブを突破しました。勝利です。");
            return;
        }
        notifyTacticalUnlock(
                defense,
                advanceTacticalAfterWave(defense));
        defense.phaseDeadlineTick = deadline(settings.combat().intermissionSeconds());
        broadcast(defense, Component.text("ウェーブを突破しました。次を準備します。", NamedTextColor.AQUA));
    }

    private TacticalUnlockResult activateTacticalAtPreparation(ActiveDefense defense) {
        try {
            return tacticalRuntime.activateAtPreparation(
                    defense.session.eventId(),
                    deterministicOperation(
                            defense.session.eventId(),
                            "TACTICAL_UNLOCK_PREPARATION",
                            "1"));
        } catch (RuntimeException failure) {
            recordTacticalFailure(defense, "preparation unlock", failure);
            return TacticalUnlockResult.unchanged(0);
        }
    }

    private TacticalUnlockResult advanceTacticalAfterWave(ActiveDefense defense) {
        try {
            return tacticalRuntime.advanceAfterWave(
                    defense.session.eventId(),
                    defense.session.currentWave(),
                    defense.session.totalWaves(),
                    deterministicOperation(
                            defense.session.eventId(),
                            "TACTICAL_UNLOCK_WAVE",
                            Integer.toString(defense.session.currentWave())));
        } catch (RuntimeException failure) {
            recordTacticalFailure(defense, "wave unlock", failure);
            return TacticalUnlockResult.unchanged(0);
        }
    }

    private TacticalUnlockResult activateFinalTacticalTier(ActiveDefense defense) {
        try {
            return tacticalRuntime.activateFinalTier(
                    defense.session.eventId(),
                    deterministicOperation(
                            defense.session.eventId(),
                            "TACTICAL_UNLOCK_FINAL",
                            Integer.toString(defense.session.totalWaves())));
        } catch (RuntimeException failure) {
            recordTacticalFailure(defense, "final unlock", failure);
            return TacticalUnlockResult.unchanged(0);
        }
    }

    private void notifyTacticalUnlock(ActiveDefense defense, TacticalUnlockResult result) {
        if (result == null || result.newlyUnlockedNodeIds().isEmpty()) {
            return;
        }
        String message = "戦術ビルド Tier " + result.highestUnlockedTier() + " を解放しました。";
        broadcast(defense, Component.text(message, NamedTextColor.LIGHT_PURPLE));
        for (UUID memberId : defense.teamMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            actionBars.publishTactical(memberId, message, currentTick);
            try {
                player.playSound(
                        player.getLocation(),
                        Sound.BLOCK_NOTE_BLOCK_PLING,
                        0.7f,
                        1.35f);
            } catch (RuntimeException notificationFailure) {
                // Notifications are advisory; a Paper-side sound failure must not roll back
                // the already committed tactical unlock.
                plugin.getLogger().log(
                        java.util.logging.Level.FINE,
                        "Could not play tactical unlock sound for " + memberId,
                        notificationFailure);
            }
        }
    }

    private void recordTacticalFailure(
            ActiveDefense defense,
            String operation,
            RuntimeException failure) {
        if (defense.persistenceFailure == null) {
            defense.persistenceFailure = rootMessage(failure);
        }
        plugin.getLogger().log(
                java.util.logging.Level.SEVERE,
                "Could not complete tactical " + operation + " for defense "
                        + defense.session.eventId(),
                failure);
    }

    private void markTacticalTerminal(ActiveDefense defense) {
        if (defense.tacticalTerminalMarked) {
            return;
        }
        defense.tacticalTerminalMarked = true;
        try {
            tacticalRuntime.markTerminal(
                    defense.session.eventId(),
                    terminalResult(defense.session.phase()),
                    Objects.requireNonNull(
                            defense.finishOperationId,
                            "finishOperationId"));
        } catch (RuntimeException failure) {
            // Tactical cache invalidation is performed in TacticalBuildRuntime's finally block;
            // terminal persistence failure is logged while the existing finish retry proceeds.
            plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Could not mark tactical terminal state for defense "
                            + defense.session.eventId(),
                    failure);
        }
    }

    private static TacticalTerminalResult terminalResult(DefensePhase phase) {
        return switch (phase) {
            case VICTORY -> TacticalTerminalResult.VICTORY;
            case DEFEAT -> TacticalTerminalResult.DEFEAT;
            case ABORTED -> TacticalTerminalResult.ABORTED;
            case RECOVERY -> TacticalTerminalResult.RECOVERY;
            default -> throw new IllegalArgumentException(
                    "non-terminal phase cannot be a tactical terminal result: " + phase);
        };
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
        defense.coreAttackSchedules.clear();
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
        markTacticalTerminal(defense);
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
                        + ", breakSuccesses=" + metrics.breakSuccessCount()
                        + ", coreAttackers=" + defense.coreAttackSchedules.size()
                        + ", coreAttacks=" + defense.coreAttackCount);
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
                notifyResourceSettlement(defense);
            } else {
                broadcast(
                        defense,
                        Component.text(
                                "技術的復旧のため、今回の仮確保ポイントは失効しました。",
                                NamedTextColor.YELLOW));
            }
            // Keep the ending state alive while the shared broker renders a pickup notice that
            // completed at the terminal boundary. The event lock is released after the same
            // 40-tick TTL used by the broker and PaperEscrowDropManager cleanup.
            defense.finishComplete = true;
            defense.finishActionBarDeadlineTick = currentTick
                    + ActionBarBroker.PICKUP_TTL_TICKS;
        }));
    }

    private void notifyResourceSettlement(ActiveDefense defense) {
        if (resources == null || defense.finishSnapshot == null) {
            return;
        }
        UUID eventId = defense.session.eventId();
        DefensePhase phase = defense.finishSnapshot.phase();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                TeamResourceSettlement settlement = resources.loadTerminalSettlement(eventId, phase);
                runOnMainThread(() -> broadcast(
                        defense,
                        Component.text(
                                "資源庫へ確定しました。防衛ポイント +"
                                        + settlement.defensePoints()
                                        + "P / 強化ポイント +"
                                        + settlement.enhancementPoints() + "P",
                                NamedTextColor.GREEN)));
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                        java.util.logging.Level.WARNING,
                        "Could not load resource settlement message for " + eventId,
                        failure);
            }
        });
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

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static UUID deterministicOperation(UUID eventId, String namespace, String value) {
        return UUID.nameUUIDFromBytes((eventId + "|" + namespace + "|" + value)
                .getBytes(StandardCharsets.UTF_8));
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
        defense.coreAttackSchedules.clear();
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
        String message = "準備: " + remainingSeconds + "秒";
        for (UUID memberId : defense.teamMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null && isInside(defense, player)) {
                actionBars.publishCountdown(memberId, message, currentTick);
            }
        }
    }

    private void renderActionBars(ActiveDefense defense) {
        for (UUID memberId : defense.teamMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            actionBars.current(memberId).ifPresent(notice ->
                    player.sendActionBar(Component.text(notice.text())));
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
            if (active.finishOperationId == null) {
                active.finishOperationId = deterministicOperation(
                        active.session.eventId(),
                        "TACTICAL_CLOSE_RECOVERY",
                        "1");
            }
            markTacticalTerminal(active);
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
        private final CoreWarningSoundGate coreWarningSoundGate;
        private final Map<UUID, Long> candidateEntryTick = new HashMap<>();
        private final Deque<UUID> pendingLogicalIds = new ArrayDeque<>();
        private final Map<UUID, UUID> entitiesByLogicalId = new LinkedHashMap<>();
        private final Map<UUID, EnemyProgress> enemyProgress = new HashMap<>();
        private final Map<UUID, EnemyRole> enemyRolesByLogicalId = new HashMap<>();
        private final Map<UUID, CoreAttackSchedule> coreAttackSchedules = new HashMap<>();
        private final Set<Long> chunkTickets = new HashSet<>();
        private final Set<UUID> bossBarViewers = new HashSet<>();
        private final EnemyPathMetrics pathMetrics = new EnemyPathMetrics();

        private long phaseDeadlineTick;
        private long absentSinceTick = -1L;
        private long lastPathRefreshTick;
        private long spawnFailureSinceTick = -1L;
        private long coreAttackCount;
        private boolean ending;
        private boolean finishInFlight;
        private boolean finishComplete;
        private boolean tacticalTerminalMarked;
        private int finishAttempts;
        private long finishRetryTick;
        private long finishActionBarDeadlineTick;
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
                BossBar bossBar,
                CoreWarningSoundGate coreWarningSoundGate) {
            this.session = session;
            this.core = core;
            this.teamMembers = teamMembers;
            this.world = world;
            this.coreTarget = coreTarget;
            this.bossBar = bossBar;
            this.coreWarningSoundGate = coreWarningSoundGate;
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
