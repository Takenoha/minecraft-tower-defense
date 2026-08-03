package io.github.takenoha.towerdefense.paper;

import io.github.takenoha.towerdefense.config.PluginSettings;
import io.github.takenoha.towerdefense.domain.CombatArea;
import io.github.takenoha.towerdefense.domain.CoreState;
import io.github.takenoha.towerdefense.domain.DefenseSession;
import io.github.takenoha.towerdefense.persistence.CoreRecord;
import io.github.takenoha.towerdefense.persistence.DefenseRepository;
import io.github.takenoha.towerdefense.persistence.StartOutcome;
import io.github.takenoha.towerdefense.persistence.StartRequest;
import io.github.takenoha.towerdefense.persistence.TeamRecord;
import io.github.takenoha.towerdefense.runtime.CoreRegistry;
import io.github.takenoha.towerdefense.runtime.DatabaseExecutor;
import io.github.takenoha.towerdefense.runtime.DefenseRuntimeStatus;
import io.github.takenoha.towerdefense.runtime.DefenseSessionManager;
import io.github.takenoha.towerdefense.runtime.TerrainMutationActivationGate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Administrator-only entry point for the first walking-skeleton milestone. */
public final class TowerDefenseCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN_PERMISSION = "towerdefense.admin";
    private static final long FOUNDATION_STAGE = 1L;

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final CombatArea combatArea;
    private final DefenseRepository repository;
    private final DatabaseExecutor databaseExecutor;
    private final DefenseSessionManager sessions;
    private final CoreRegistry cores;
    private final ThirdPartyRegionProtectionAdapter regionProtection;
    private boolean startInFlight;
    private boolean startCancellationRequested;
    private boolean startRecoveryInFlight;
    private DefenseSession pendingRecoverySession;

    public TowerDefenseCommand(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores) {
        this(
                plugin,
                settings,
                repository,
                databaseExecutor,
                sessions,
                cores,
                ThirdPartyRegionProtectionAdapter.none());
    }

    public TowerDefenseCommand(
            JavaPlugin plugin,
            PluginSettings settings,
            DefenseRepository repository,
            DatabaseExecutor databaseExecutor,
            DefenseSessionManager sessions,
            CoreRegistry cores,
            ThirdPartyRegionProtectionAdapter regionProtection) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cores = Objects.requireNonNull(cores, "cores");
        this.regionProtection = Objects.requireNonNull(regionProtection, "regionProtection");
        combatArea = new CombatArea(
                settings.combat().radius(),
                settings.combat().spawnInner(),
                settings.combat().spawnOuter(),
                settings.combat().minimumCoreDistance(),
                settings.combat().coreGap());
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED));
            return true;
        }
        if (arguments.length < 2 || !arguments[0].equalsIgnoreCase("admin")) {
            sendUsage(sender);
            return true;
        }
        return switch (arguments[1].toLowerCase(java.util.Locale.ROOT)) {
            case "core" -> registerCore(sender);
            case "simulate" -> simulate(sender, arguments);
            case "status" -> showStatus(sender);
            case "abort" -> recover(sender);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean registerCore(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("コア登録はプレイヤーから実行してください。", NamedTextColor.RED));
            return true;
        }
        if (sessions.hasActiveSession() || startInFlight) {
            sender.sendMessage(Component.text("防衛戦中はコアを登録できません。", NamedTextColor.RED));
            return true;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || !target.getType().isSolid()) {
            sender.sendMessage(Component.text("6ブロック以内の固体ブロックを見てください。", NamedTextColor.RED));
            return true;
        }
        if (target.getWorld().getEnvironment() != World.Environment.NORMAL) {
            sender.sendMessage(Component.text("初版のコアはOverworldだけに登録できます。", NamedTextColor.RED));
            return true;
        }

        List<String> safetyViolations = PaperCombatAreaSafetyValidator.violations(
                target.getWorld(),
                target.getX() + 0.5d,
                target.getZ() + 0.5d,
                combatArea,
                settings.protection(),
                regionProtection);
        if (!safetyViolations.isEmpty()) {
            sender.sendMessage(Component.text(
                    "コア周辺が防衛戦の保護境界を満たしません: "
                            + String.join("; ", safetyViolations),
                    NamedTextColor.RED));
            return true;
        }

        UUID ownerId = player.getUniqueId();
        UUID teamId = soloTeamId(ownerId);
        UUID worldId = target.getWorld().getUID();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        long maximumHitPoints = settings.core().maxHealth();
        player.sendMessage(Component.text("コア位置を検証しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            TeamRecord team = repository.findTeamByOwner(ownerId)
                    .orElseGet(() -> repository.createSoloTeam(teamId, ownerId, Instant.now()));
            Instant now = Instant.now();
            CoreRecord core = new CoreRecord(
                    UUID.randomUUID(),
                    team.id(),
                    worldId,
                    x,
                    y,
                    z,
                    maximumHitPoints,
                    maximumHitPoints,
                    now,
                    now);
            return repository.placeCore(
                    ownerId,
                    core,
                    settings.combat().minimumCoreDistance(),
                    UUID.randomUUID(),
                    now);
        }).whenComplete((placement, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                player.sendMessage(Component.text(
                        "コアを登録できません: " + rootMessage(failure),
                        NamedTextColor.RED));
                return;
            }
            CoreRecord core = placement.core().orElseThrow(
                    () -> new IllegalStateException("Core placement returned no core"));
            cores.replace(core);
            player.sendMessage(Component.text(
                    "このブロックをテスト用コアとして登録しました。",
                    NamedTextColor.GREEN));
        }));
        return true;
    }

    private boolean simulate(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("シミュレーションはプレイヤーから開始してください。", NamedTextColor.RED));
            return true;
        }
        if (sessions.hasActiveSession() || startInFlight) {
            sender.sendMessage(Component.text("すでに防衛戦が進行中です。", NamedTextColor.RED));
            return true;
        }
        long stage = parseStage(arguments);
        if (stage != FOUNDATION_STAGE) {
            sender.sendMessage(Component.text(
                    "このwalking skeletonでプレイ可能なのはステージ1だけです。",
                    NamedTextColor.RED));
            return true;
        }

        startInFlight = true;
        startCancellationRequested = false;
        UUID ownerId = player.getUniqueId();
        player.sendMessage(Component.text("永続データと開始ロックを確認しています…", NamedTextColor.GRAY));
        databaseExecutor.submit(() -> {
            TeamRecord team = repository.findTeamByOwner(ownerId)
                    .orElseThrow(() -> new IllegalStateException("先に /td admin core を実行してください"));
            CoreRecord core = repository.findCoreByTeam(team.id())
                    .orElseThrow(() -> new IllegalStateException("先に /td admin core を実行してください"));
            return new StartData(team, core);
        }).whenComplete((startData, lookupFailure) -> runOnMainThread(() -> {
            if (lookupFailure != null) {
                completeStartOperation();
                player.sendMessage(Component.text(rootMessage(lookupFailure), NamedTextColor.RED));
                return;
            }
            if (startCancellationRequested) {
                completeStartOperation();
                player.sendMessage(Component.text("防衛戦の開始を取り消しました。", NamedTextColor.YELLOW));
                return;
            }
            beginStartTransaction(player, startData);
        }));
        return true;
    }

    private void beginStartTransaction(Player player, StartData startData) {
        if (sessions.hasActiveSession()) {
            completeStartOperation();
            player.sendMessage(Component.text("すでに防衛戦が進行中です。", NamedTextColor.RED));
            return;
        }
        CoreRecord core = startData.core();
        World world = Bukkit.getWorld(core.worldId());
        if (world == null) {
            completeStartOperation();
            player.sendMessage(Component.text("コアのワールドが読み込まれていません。", NamedTextColor.RED));
            return;
        }
        List<String> safetyViolations = PaperCombatAreaSafetyValidator.violations(
                world,
                core.blockX() + 0.5d,
                core.blockZ() + 0.5d,
                combatArea,
                settings.protection(),
                regionProtection);
        if (!safetyViolations.isEmpty()) {
            completeStartOperation();
            player.sendMessage(Component.text(
                    "防衛戦を開始できません。戦闘領域が保護境界に接触します: "
                            + String.join("; ", safetyViolations),
                    NamedTextColor.RED));
            return;
        }
        if (core.currentHitPoints() <= 0L) {
            completeStartOperation();
            player.sendMessage(Component.text("コアは破壊済みです。", NamedTextColor.RED));
            return;
        }
        if (!player.getWorld().equals(world)
                || !combatArea.contains(
                        core.blockX() + 0.5d,
                        core.blockZ() + 0.5d,
                        player.getX(),
                        player.getZ())) {
            completeStartOperation();
            player.sendMessage(Component.text("コアの戦闘範囲内に入ってください。", NamedTextColor.RED));
            return;
        }

        DefenseSession session = new DefenseSession(
                UUID.randomUUID(),
                startData.team().id(),
                FOUNDATION_STAGE,
                settings.combat().maxParticipants(),
                new CoreState(
                        core.maximumHitPoints(),
                        core.currentHitPoints(),
                        true));
        StartRequest request = new StartRequest(
                session.snapshot(),
                core.id(),
                settings.toString(),
                1,
                Instant.now());
        databaseExecutor.submit(() -> repository.tryStart(request))
                .whenComplete((outcome, failure) -> runOnMainThread(() -> {
                    if (failure != null) {
                        completeStartOperation();
                        player.sendMessage(Component.text(
                                "防衛戦を開始できません: " + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    if (outcome != StartOutcome.STARTED) {
                        completeStartOperation();
                        player.sendMessage(Component.text(
                                "別の防衛戦がデータベース上で進行中です。",
                                NamedTextColor.RED));
                        return;
                    }
                    if (startCancellationRequested) {
                        recoverUnactivatedSession(player, session, "防衛戦の開始を取り消しました。");
                        return;
                    }
                    try {
                        sessions.activate(session, core, startData.team().members());
                        completeStartOperation();
                    } catch (RuntimeException activationFailure) {
                        player.sendMessage(Component.text(
                                "Paper実行層を開始できないため復旧します: "
                                        + activationFailure.getMessage(),
                                NamedTextColor.RED));
                        recoverUnactivatedSession(player, session, "Paper実行層の開始失敗を復旧しました。");
                    }
                }));
    }

    private void recoverUnactivatedSession(
            CommandSender requester,
            DefenseSession session,
            String successMessage) {
        if (startRecoveryInFlight) {
            return;
        }
        pendingRecoverySession = session;
        startRecoveryInFlight = true;
        databaseExecutor.execute(() -> repository.recoverUnfinishedEvent(
                        session.eventId(), UUID.randomUUID(), Instant.now()))
                .whenComplete((ignored, failure) -> runOnMainThread(() -> {
                    startRecoveryInFlight = false;
                    if (failure != null) {
                        requester.sendMessage(Component.text(
                                "開始済みイベントの復旧に失敗しました。/td admin abort で再試行できます: "
                                        + rootMessage(failure),
                                NamedTextColor.RED));
                        return;
                    }
                    completeStartOperation();
                    requester.sendMessage(Component.text(successMessage, NamedTextColor.YELLOW));
                }));
    }

    private void completeStartOperation() {
        startInFlight = false;
        startCancellationRequested = false;
        startRecoveryInFlight = false;
        pendingRecoverySession = null;
    }

    private boolean showStatus(CommandSender sender) {
        Optional<DefenseRuntimeStatus> status = sessions.status();
        if (status.isEmpty()) {
            if (startInFlight) {
                sender.sendMessage(Component.text(
                        "防衛戦の開始または開始後復旧を処理中です。",
                        NamedTextColor.YELLOW));
                return true;
            }
            sender.sendMessage(Component.text("実行中の防衛戦はありません。", NamedTextColor.GRAY));
            return true;
        }
        DefenseRuntimeStatus value = status.orElseThrow();
        sender.sendMessage(Component.text(
                "event=" + value.eventId()
                        + " phase=" + value.phase()
                        + " wave=" + value.currentWave() + "/" + value.totalWaves()
                        + " enemies=" + value.aliveEnemies() + "+" + value.pendingEnemies()
                        + " core=" + value.coreHitPoints() + "/" + value.coreMaximumHitPoints()
                        + (value.ending() ? " ending" : "")
                        + " pathInspections=" + value.pathMetrics().inspectionCount()
                        + " pathFailures=" + value.pathMetrics().inspectionFailureCount()
                        + " pathAvgNanos=" + value.pathMetrics().averageInspectionNanos()
                        + " pathMaxNanos=" + value.pathMetrics().maxInspectionNanos()
                        + " breakAttempts=" + value.pathMetrics().breakAttemptCount()
                        + " breakSuccesses=" + value.pathMetrics().breakSuccessCount()
                        + " bridgeAttempts=" + value.pathMetrics().bridgeAttemptCount()
                        + " bridgePlacements=" + value.pathMetrics().bridgePlacementCount()
                        + " terrainMutation="
                        + new TerrainMutationActivationGate(settings.terrainMutation()).status()
                        + (value.persistenceFailure() == null
                                ? ""
                                : " persistenceError=" + value.persistenceFailure()),
                value.persistenceFailure() == null
                        ? NamedTextColor.AQUA
                        : NamedTextColor.RED));
        return true;
    }

    private boolean recover(CommandSender sender) {
        if (sessions.recoverActiveSession()) {
            sender.sendMessage(Component.text("技術的復旧と清掃を開始しました。", NamedTextColor.YELLOW));
        } else if (startInFlight) {
            startCancellationRequested = true;
            if (pendingRecoverySession != null && !startRecoveryInFlight) {
                recoverUnactivatedSession(
                        sender,
                        pendingRecoverySession,
                        "開始済みイベントの技術的復旧を完了しました。");
            }
            sender.sendMessage(Component.text(
                    "開始処理を取り消し、DBロック取得済みなら直後に技術的復旧します。",
                    NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text("復旧対象の防衛戦はありません。", NamedTextColor.GRAY));
        }
        return true;
    }

    private static long parseStage(String[] arguments) {
        if (arguments.length < 3) {
            return FOUNDATION_STAGE;
        }
        try {
            return Long.parseLong(arguments[2]);
        } catch (NumberFormatException invalidStage) {
            return Long.MIN_VALUE;
        }
    }

    private static UUID soloTeamId(UUID ownerId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-tower-defense:solo:" + ownerId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        if (root instanceof CompletionException && root.getCause() != null) {
            root = root.getCause();
        }
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/td admin <core|simulate [1]|status|abort>",
                NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] arguments) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (arguments.length == 1) {
            return matching(arguments[0], List.of("admin"));
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("admin")) {
            return matching(arguments[1], List.of("core", "simulate", "status", "abort"));
        }
        if (arguments.length == 3 && arguments[1].equalsIgnoreCase("simulate")) {
            return matching(arguments[2], List.of("1"));
        }
        return List.of();
    }

    private static List<String> matching(String prefix, List<String> candidates) {
        String normalized = prefix.toLowerCase(java.util.Locale.ROOT);
        return candidates.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private record StartData(TeamRecord team, CoreRecord core) {
        private StartData {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(core, "core");
        }
    }
}
